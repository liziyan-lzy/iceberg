/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.flink.sink;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.core.io.SimpleVersionedSerialization;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.table.runtime.typeutils.SortedMapTypeInfo;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.ReplacePartitions;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.SnapshotUpdate;
import org.apache.iceberg.Table;
import org.apache.iceberg.flink.TableLoader;
import org.apache.iceberg.io.WriteResult;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Strings;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Comparators;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.iceberg.util.ThreadPools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class IcebergFilesCommitter extends AbstractStreamOperator<Void>
    implements OneInputStreamOperator<FlinkWriteResult, Void>, BoundedOneInput {

  private static final long serialVersionUID = 1L;
  private static final long INITIAL_CHECKPOINT_ID = -1L;
  private static final byte[] EMPTY_MANIFEST_DATA = new byte[0];

  private static final Logger LOG = LoggerFactory.getLogger(IcebergFilesCommitter.class);
  private static final String FLINK_JOB_ID = "flink.job-id";
  private static final String OPERATOR_ID = "flink.operator-id";

  // The max checkpoint id we've committed to iceberg table. As the flink's checkpoint is always
  // increasing, so we could correctly commit all the data files whose checkpoint id is greater than
  // the max committed one to iceberg table, for avoiding committing the same data files twice. This
  // id will be attached to iceberg's meta when committing the iceberg transaction.
  private static final String MAX_COMMITTED_CHECKPOINT_ID = "flink.max-committed-checkpoint-id";
  static final String MAX_CONTINUOUS_EMPTY_COMMITS = "flink.max-continuous-empty-commits";

  // TableLoader to load iceberg table lazily.
  private final TableLoader tableLoader;
  private final boolean replacePartitions;
  private final Map<String, String> snapshotProperties;

  // A sorted map to maintain the completed data files for each pending checkpointId (which have not
  // been committed to iceberg table). We need a sorted map here because there's possible that few
  // checkpoints snapshot failed, for example: the 1st checkpoint have 2 data files <1, <file0,
  // file1>>, the 2st checkpoint have 1 data files <2, <file3>>. Snapshot for checkpoint#1
  // interrupted because of network/disk failure etc, while we don't expect any data loss in iceberg
  // table. So we keep the finished files <1, <file0, file1>> in memory and retry to commit iceberg
  // table when the next checkpoint happen.
  private final NavigableMap<Long, byte[]> dataFilesPerCheckpoint = Maps.newTreeMap();

  // The completed files cache for current checkpoint. Once the snapshot barrier received, it will
  // be flushed to the 'dataFilesPerCheckpoint'.
  private final Map<Long, List<WriteResult>> writeResultsSinceLastSnapshot = Maps.newHashMap();
  private final String branch;

  // It will have an unique identifier for one job.
  private transient String flinkJobId;
  private transient String operatorUniqueId;
  private transient Table table;
  private transient IcebergFilesCommitterMetrics committerMetrics;
  private transient ManifestOutputFileFactory manifestOutputFileFactory;
  private transient long maxCommittedCheckpointId;
  private transient int continuousEmptyCheckpoints;
  private transient int maxContinuousEmptyCommits;
  // There're two cases that we restore from flink checkpoints: the first case is restoring from
  // snapshot created by the same flink job; another case is restoring from snapshot created by
  // another different job. For the second case, we need to maintain the old flink job's id in flink
  // state backend to find the max-committed-checkpoint-id when traversing iceberg table's
  // snapshots.
  private static final ListStateDescriptor<String> JOB_ID_DESCRIPTOR =
      new ListStateDescriptor<>("iceberg-flink-job-id", BasicTypeInfo.STRING_TYPE_INFO);
  private transient ListState<String> jobIdState;
  // All pending checkpoints states for this function.
  private static final ListStateDescriptor<SortedMap<Long, byte[]>> STATE_DESCRIPTOR =
      buildStateDescriptor();
  private transient ListState<SortedMap<Long, byte[]>> checkpointsState;

  private final Integer workerPoolSize;
  private final PartitionSpec spec;
  private transient ExecutorService workerPool;

  IcebergFilesCommitter(
      StreamOperatorParameters<Void> parameters,
      TableLoader tableLoader,
      boolean replacePartitions,
      Map<String, String> snapshotProperties,
      Integer workerPoolSize,
      String branch,
      PartitionSpec spec) {
    super(parameters);
    this.tableLoader = tableLoader;
    this.replacePartitions = replacePartitions;
    this.snapshotProperties = snapshotProperties;
    this.workerPoolSize = workerPoolSize;
    this.branch = branch;
    this.spec = spec;
  }

  @Override
  public void initializeState(StateInitializationContext context) throws Exception {
    super.initializeState(context);
    this.flinkJobId = getContainingTask().getEnvironment().getJobID().toString();
    this.operatorUniqueId = getRuntimeContext().getOperatorUniqueID();

    // Open the table loader and load the table.
    this.tableLoader.open();
    this.table = tableLoader.loadTable();
    this.committerMetrics = new IcebergFilesCommitterMetrics(super.metrics, table.name());

    maxContinuousEmptyCommits =
        PropertyUtil.propertyAsInt(table.properties(), MAX_CONTINUOUS_EMPTY_COMMITS, 10);
    Preconditions.checkArgument(
        maxContinuousEmptyCommits > 0, MAX_CONTINUOUS_EMPTY_COMMITS + " must be positive");

    int subTaskId = getRuntimeContext().getTaskInfo().getIndexOfThisSubtask();
    int attemptId = getRuntimeContext().getTaskInfo().getAttemptNumber();
    this.manifestOutputFileFactory =
        FlinkManifestUtil.createOutputFileFactory(
            () -> table, table.properties(), flinkJobId, operatorUniqueId, subTaskId, attemptId);
    this.maxCommittedCheckpointId = INITIAL_CHECKPOINT_ID;

    this.checkpointsState = context.getOperatorStateStore().getListState(STATE_DESCRIPTOR);
    this.jobIdState = context.getOperatorStateStore().getListState(JOB_ID_DESCRIPTOR);
    if (context.isRestored()) {
      Iterable<String> jobIdIterable = jobIdState.get();
      if (jobIdIterable == null || !jobIdIterable.iterator().hasNext()) {
        LOG.warn(
            "Failed to restore committer state. This can happen when operator uid changed and Flink "
                + "allowNonRestoredState is enabled. Best practice is to explicitly set the operator id "
                + "via FlinkSink#Builder#uidPrefix() so that the committer operator uid is stable. "
                + "Otherwise, Flink auto generate an operator uid based on job topology."
                + "With that, operator uid is subjective to change upon topology change.");
        return;
      }

      String restoredFlinkJobId = jobIdIterable.iterator().next();
      Preconditions.checkState(
          !Strings.isNullOrEmpty(restoredFlinkJobId),
          "Flink job id parsed from checkpoint snapshot shouldn't be null or empty");

      // Since flink's checkpoint id will start from the max-committed-checkpoint-id + 1 in the new
      // flink job even if it's restored from a snapshot created by another different flink job, so
      // it's safe to assign the max committed checkpoint id from restored flink job to the current
      // flink job.
      this.maxCommittedCheckpointId =
          SinkUtil.getMaxCommittedCheckpointId(table, restoredFlinkJobId, operatorUniqueId, branch);

      NavigableMap<Long, byte[]> uncommittedDataFiles =
          Maps.newTreeMap(checkpointsState.get().iterator().next())
              .tailMap(maxCommittedCheckpointId, false);
      if (!uncommittedDataFiles.isEmpty()) {
        // Committed all uncommitted data files from the old flink job to iceberg table.
        long maxUncommittedCheckpointId = uncommittedDataFiles.lastKey();
        commitUpToCheckpoint(
            uncommittedDataFiles, restoredFlinkJobId, operatorUniqueId, maxUncommittedCheckpointId);
      }
    }
  }

  @Override
  public void snapshotState(StateSnapshotContext context) throws Exception {
    super.snapshotState(context);
    long checkpointId = context.getCheckpointId();
    LOG.info(
        "Start to flush snapshot state to state backend, table: {}, checkpointId: {}",
        table,
        checkpointId);

    // Update the checkpoint state.
    long startNano = System.nanoTime();
    writeToManifestUptoLatestCheckpoint(checkpointId);

    // Reset the snapshot state to the latest state.
    checkpointsState.clear();
    checkpointsState.add(dataFilesPerCheckpoint);

    jobIdState.clear();
    jobIdState.add(flinkJobId);

    committerMetrics.checkpointDuration(
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano));
  }

  @Override
  public void notifyCheckpointComplete(long checkpointId) throws Exception {
    super.notifyCheckpointComplete(checkpointId);
    // It's possible that we have the following events:
    //   1. snapshotState(ckpId);
    //   2. snapshotState(ckpId+1);
    //   3. notifyCheckpointComplete(ckpId+1);
    //   4. notifyCheckpointComplete(ckpId);
    // For step#4, we don't need to commit iceberg table again because in step#3 we've committed all
    // the files,
    // Besides, we need to maintain the max-committed-checkpoint-id to be increasing.
    if (checkpointId > maxCommittedCheckpointId) {
      LOG.info("Checkpoint {} completed. Attempting commit.", checkpointId);
      commitUpToCheckpoint(dataFilesPerCheckpoint, flinkJobId, operatorUniqueId, checkpointId);
      this.maxCommittedCheckpointId = checkpointId;
    } else {
      LOG.info(
          "Skipping committing checkpoint {}. {} is already committed.",
          checkpointId,
          maxCommittedCheckpointId);
    }

    // reload the table in case new configuration is needed
    this.table = tableLoader.loadTable();
  }

  private void commitUpToCheckpoint(
      NavigableMap<Long, byte[]> deltaManifestsMap,
      String newFlinkJobId,
      String operatorId,
      long checkpointId)
      throws IOException {
    NavigableMap<Long, byte[]> pendingMap = deltaManifestsMap.headMap(checkpointId, true);
    List<ManifestFile> manifests = Lists.newArrayList();
    NavigableMap<Long, WriteResult> pendingResults = Maps.newTreeMap();
    for (Map.Entry<Long, byte[]> e : pendingMap.entrySet()) {
      if (Arrays.equals(EMPTY_MANIFEST_DATA, e.getValue())) {
        // Skip the empty flink manifest.
        continue;
      }

      DeltaManifests deltaManifests =
          SimpleVersionedSerialization.readVersionAndDeSerialize(
              DeltaManifestsSerializer.INSTANCE, e.getValue());
      pendingResults.put(
          e.getKey(),
          FlinkManifestUtil.readCompletedFiles(deltaManifests, table.io(), table.specs()));
      manifests.addAll(deltaManifests.manifests());
    }

    CommitSummary summary = new CommitSummary(pendingResults);
    commitPendingResult(pendingResults, summary, newFlinkJobId, operatorId, checkpointId);
    committerMetrics.updateCommitSummary(summary);
    pendingMap.clear();
    FlinkManifestUtil.deleteCommittedManifests(table, manifests, newFlinkJobId, checkpointId);
  }

  private void commitPendingResult(
      NavigableMap<Long, WriteResult> pendingResults,
      CommitSummary summary,
      String newFlinkJobId,
      String operatorId,
      long checkpointId) {
    long totalFiles = summary.dataFilesCount() + summary.deleteFilesCount();
    continuousEmptyCheckpoints = totalFiles == 0 ? continuousEmptyCheckpoints + 1 : 0;
    if (totalFiles != 0 || continuousEmptyCheckpoints % maxContinuousEmptyCommits == 0) {
      if (replacePartitions) {
        replacePartitions(pendingResults, summary, newFlinkJobId, operatorId, checkpointId);
      } else {
        commitDeltaTxn(pendingResults, summary, newFlinkJobId, operatorId, checkpointId);
      }
      continuousEmptyCheckpoints = 0;
    } else {
      LOG.info("Skip commit for checkpoint {} due to no data files or delete files.", checkpointId);
    }
  }

  private void replacePartitions(
      NavigableMap<Long, WriteResult> pendingResults,
      CommitSummary summary,
      String newFlinkJobId,
      String operatorId,
      long checkpointId) {
    Preconditions.checkState(
        summary.deleteFilesCount() == 0, "Cannot overwrite partitions with delete files.");
    // Commit the overwrite transaction.
    ReplacePartitions dynamicOverwrite = table.newReplacePartitions().scanManifestsWith(workerPool);
    for (WriteResult result : pendingResults.values()) {
      Preconditions.checkState(
          result.referencedDataFiles().length == 0, "Should have no referenced data files.");
      Arrays.stream(result.dataFiles()).forEach(dynamicOverwrite::addFile);
    }

    commitOperation(
        dynamicOverwrite,
        summary,
        "dynamic partition overwrite",
        newFlinkJobId,
        operatorId,
        checkpointId);
  }

  private void commitDeltaTxn(
      NavigableMap<Long, WriteResult> pendingResults,
      CommitSummary summary,
      String newFlinkJobId,
      String operatorId,
      long checkpointId) {
    if (summary.deleteFilesCount() == 0) {
      // To be compatible with iceberg format V1.
      AppendFiles appendFiles = table.newAppend().scanManifestsWith(workerPool);
      for (WriteResult result : pendingResults.values()) {
        Preconditions.checkState(
            result.referencedDataFiles().length == 0,
            "Should have no referenced data files for append.");
        Arrays.stream(result.dataFiles()).forEach(appendFiles::appendFile);
      }
      commitOperation(appendFiles, summary, "append", newFlinkJobId, operatorId, checkpointId);
    } else {
      // To be compatible with iceberg format V2.
      for (Map.Entry<Long, WriteResult> e : pendingResults.entrySet()) {
        // We don't commit the merged result into a single transaction because for the sequential
        // transaction txn1 and txn2, the equality-delete files of txn2 are required to be applied
        // to data files from txn1. Committing the merged one will lead to the incorrect delete
        // semantic.
        WriteResult result = e.getValue();

        // Row delta validations are not needed for streaming changes that write equality deletes.
        // Equality deletes are applied to data in all previous sequence numbers, so retries may
        // push deletes further in the future, but do not affect correctness. Position deletes
        // committed to the table in this path are used only to delete rows from data files that are
        // being added in this commit. There is no way for data files added along with the delete
        // files to be concurrently removed, so there is no need to validate the files referenced by
        // the position delete files that are being committed.
        RowDelta rowDelta = table.newRowDelta().scanManifestsWith(workerPool);

        Arrays.stream(result.dataFiles()).forEach(rowDelta::addRows);
        Arrays.stream(result.deleteFiles()).forEach(rowDelta::addDeletes);
        commitOperation(rowDelta, summary, "rowDelta", newFlinkJobId, operatorId, e.getKey());
      }
    }
  }

  private void commitOperation(
      SnapshotUpdate<?> operation,
      CommitSummary summary,
      String description,
      String newFlinkJobId,
      String operatorId,
      long checkpointId) {
    LOG.info(
        "Committing {} for checkpoint {} to table {} branch {} with summary: {}",
        description,
        checkpointId,
        table.name(),
        branch,
        summary);
    snapshotProperties.forEach(operation::set);
    // custom snapshot metadata properties will be overridden if they conflict with internal ones
    // used by the sink.
    operation.set(MAX_COMMITTED_CHECKPOINT_ID, Long.toString(checkpointId));
    operation.set(FLINK_JOB_ID, newFlinkJobId);
    operation.set(OPERATOR_ID, operatorId);
    operation.toBranch(branch);

    long startNano = System.nanoTime();
    operation.commit(); // abort is automatically called if this fails.
    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano);
    LOG.info(
        "Committed {} to table: {}, branch: {}, checkpointId {} in {} ms",
        description,
        table.name(),
        branch,
        checkpointId,
        durationMs);
    committerMetrics.commitDuration(durationMs);
  }

  @Override
  public void processElement(StreamRecord<FlinkWriteResult> element) {
    FlinkWriteResult flinkWriteResult = element.getValue();
    List<WriteResult> writeResults =
        writeResultsSinceLastSnapshot.computeIfAbsent(
            flinkWriteResult.checkpointId(), k -> Lists.newArrayList());
    writeResults.add(flinkWriteResult.writeResult());
  }

  @Override
  public void endInput() throws IOException {
    // Flush the buffered data files into 'dataFilesPerCheckpoint' firstly.
    long currentCheckpointId = IcebergStreamWriter.END_INPUT_CHECKPOINT_ID;
    writeToManifestUptoLatestCheckpoint(currentCheckpointId);
    commitUpToCheckpoint(dataFilesPerCheckpoint, flinkJobId, operatorUniqueId, currentCheckpointId);
  }

  private void writeToManifestUptoLatestCheckpoint(long checkpointId) throws IOException {
    if (!writeResultsSinceLastSnapshot.containsKey(checkpointId)) {
      dataFilesPerCheckpoint.put(checkpointId, EMPTY_MANIFEST_DATA);
    }

    for (Map.Entry<Long, List<WriteResult>> writeResultsOfCheckpoint :
        writeResultsSinceLastSnapshot.entrySet()) {
      dataFilesPerCheckpoint.put(
          writeResultsOfCheckpoint.getKey(),
          writeToManifest(writeResultsOfCheckpoint.getKey(), writeResultsOfCheckpoint.getValue()));
    }

    // Clear the local buffer for current checkpoint.
    writeResultsSinceLastSnapshot.clear();
  }

  /**
   * Write all the complete data files to a newly created manifest file and return the manifest's
   * avro serialized bytes.
   */
  private byte[] writeToManifest(long checkpointId, List<WriteResult> writeResults)
      throws IOException {
    WriteResult result = WriteResult.builder().addAll(writeResults).build();
    DeltaManifests deltaManifests =
        FlinkManifestUtil.writeCompletedFiles(
            result, () -> manifestOutputFileFactory.create(checkpointId), spec);

    return SimpleVersionedSerialization.writeVersionAndSerialize(
        DeltaManifestsSerializer.INSTANCE, deltaManifests);
  }

  @Override
  public void open() throws Exception {
    super.open();

    final String operatorID = getRuntimeContext().getOperatorUniqueID();
    this.workerPool =
        ThreadPools.newFixedThreadPool("iceberg-worker-pool-" + operatorID, workerPoolSize);
  }

  @Override
  public void close() throws Exception {
    if (tableLoader != null) {
      tableLoader.close();
    }

    if (workerPool != null) {
      workerPool.shutdown();
    }
  }

  @VisibleForTesting
  static ListStateDescriptor<SortedMap<Long, byte[]>> buildStateDescriptor() {
    Comparator<Long> longComparator = Comparators.forType(Types.LongType.get());
    // Construct a SortedMapTypeInfo.
    SortedMapTypeInfo<Long, byte[]> sortedMapTypeInfo =
        new SortedMapTypeInfo<>(
            BasicTypeInfo.LONG_TYPE_INFO,
            PrimitiveArrayTypeInfo.BYTE_PRIMITIVE_ARRAY_TYPE_INFO,
            longComparator);
    return new ListStateDescriptor<>("iceberg-files-committer-state", sortedMapTypeInfo);
  }
}
