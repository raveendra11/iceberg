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
package org.apache.iceberg;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import org.apache.iceberg.expressions.ManifestEvaluator;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.types.Comparators;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.ParallelIterable;
import org.apache.iceberg.util.PartitionUtil;
import org.apache.iceberg.util.StructLikeMap;
import org.apache.iceberg.util.StructProjection;

/** A {@link Table} implementation that exposes a table's partitions as rows. */
public class PartitionsTable extends BaseMetadataTable {

  private final Schema schema;

  private final boolean unpartitionedTable;

  PartitionsTable(Table table) {
    this(table, table.name() + ".partitions");
  }

  PartitionsTable(Table table, String name) {
    super(table, name);

    this.schema =
        new Schema(
            Types.NestedField.required(1, "partition", Partitioning.partitionType(table)),
            Types.NestedField.required(4, "spec_id", Types.IntegerType.get()),
            Types.NestedField.required(
                2, "record_count", Types.LongType.get(), "Count of records in data files"),
            Types.NestedField.required(
                3, "file_count", Types.IntegerType.get(), "Count of data files"),
            Types.NestedField.required(
                11,
                "total_data_file_size_in_bytes",
                Types.LongType.get(),
                "Total size in bytes of data files"),
            Types.NestedField.required(
                5,
                "position_delete_record_count",
                Types.LongType.get(),
                "Count of records in position delete files"),
            Types.NestedField.required(
                6,
                "position_delete_file_count",
                Types.IntegerType.get(),
                "Count of position delete files"),
            Types.NestedField.required(
                7,
                "equality_delete_record_count",
                Types.LongType.get(),
                "Count of records in equality delete files"),
            Types.NestedField.required(
                8,
                "equality_delete_file_count",
                Types.IntegerType.get(),
                "Count of equality delete files"),
            Types.NestedField.optional(
                9,
                "last_updated_at",
                Types.TimestampType.withZone(),
                "Commit time of snapshot that last updated this partition"),
            Types.NestedField.optional(
                10,
                "last_updated_snapshot_id",
                Types.LongType.get(),
                "Id of snapshot that last updated this partition"));
    this.unpartitionedTable = Partitioning.partitionType(table).fields().isEmpty();
  }

  @Override
  public TableScan newScan() {
    return new PartitionsScan(table());
  }

  @Override
  public Schema schema() {
    if (unpartitionedTable) {
      return schema.select(
          "record_count",
          "file_count",
          "total_data_file_size_in_bytes",
          "position_delete_record_count",
          "position_delete_file_count",
          "equality_delete_record_count",
          "equality_delete_file_count",
          "last_updated_at",
          "last_updated_snapshot_id");
    }
    return schema;
  }

  @Override
  MetadataTableType metadataTableType() {
    return MetadataTableType.PARTITIONS;
  }

  private DataTask task(StaticTableScan scan) {
    Iterable<Partition> partitions = partitions(table(), scan);
    if (unpartitionedTable) {
      // the table is unpartitioned, partitions contains only the root partition
      return StaticDataTask.of(
          io().newInputFile(table().operations().current().metadataFileLocation()),
          schema(),
          scan.schema(),
          partitions,
          root ->
              StaticDataTask.Row.of(
                  root.dataRecordCount,
                  root.dataFileCount,
                  root.dataFileSizeInBytes,
                  root.posDeleteRecordCount,
                  root.posDeleteFileCount,
                  root.eqDeleteRecordCount,
                  root.eqDeleteFileCount,
                  root.lastUpdatedAt,
                  root.lastUpdatedSnapshotId));
    } else {
      return StaticDataTask.of(
          io().newInputFile(table().operations().current().metadataFileLocation()),
          schema(),
          scan.schema(),
          partitions,
          PartitionsTable::convertPartition);
    }
  }

  private static StaticDataTask.Row convertPartition(Partition partition) {
    return StaticDataTask.Row.of(
        partition.partitionData,
        partition.specId,
        partition.dataRecordCount,
        partition.dataFileCount,
        partition.dataFileSizeInBytes,
        partition.posDeleteRecordCount,
        partition.posDeleteFileCount,
        partition.eqDeleteRecordCount,
        partition.eqDeleteFileCount,
        partition.lastUpdatedAt,
        partition.lastUpdatedSnapshotId);
  }

  private static Iterable<Partition> partitions(Table table, StaticTableScan scan) {
    Types.StructType partitionType = Partitioning.partitionType(table);

    StructLikeMap<Partition> partitions =
        StructLikeMap.create(partitionType, new PartitionComparator(partitionType));

    try (CloseableIterable<ManifestEntry<? extends ContentFile<?>>> entries = planEntries(scan)) {
      for (ManifestEntry<? extends ContentFile<?>> entry : entries) {
        Snapshot snapshot = table.snapshot(entry.snapshotId());
        ContentFile<?> file = entry.file();
        StructLike key =
            PartitionUtil.coercePartition(
                partitionType, table.specs().get(file.specId()), file.partition());
        partitions
            .computeIfAbsent(key, () -> new Partition(key, partitionType))
            .update(file, snapshot);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    return partitions.values();
  }

  @VisibleForTesting
  static CloseableIterable<ManifestEntry<?>> planEntries(StaticTableScan scan) {
    Table table = scan.table();

    CloseableIterable<ManifestFile> filteredManifests =
        filteredManifests(scan, table, scan.snapshot().allManifests(table.io()));

    Iterable<CloseableIterable<ManifestEntry<?>>> tasks =
        CloseableIterable.transform(filteredManifests, manifest -> readEntries(manifest, scan));

    return new ParallelIterable<>(tasks, scan.planExecutor());
  }

  private static CloseableIterable<ManifestEntry<?>> readEntries(
      ManifestFile manifest, StaticTableScan scan) {
    Table table = scan.table();
    return CloseableIterable.transform(
        ManifestFiles.open(manifest, table.io(), table.specs())
            .caseSensitive(scan.isCaseSensitive())
            .select(BaseScan.scanColumns(manifest.content())) // don't select stats columns
            .liveEntries(),
        t ->
            (ManifestEntry<? extends ContentFile<?>>)
                // defensive copy of manifest entry without stats columns
                t.copyWithoutStats());
  }

  private static CloseableIterable<ManifestFile> filteredManifests(
      StaticTableScan scan, Table table, List<ManifestFile> manifestFilesList) {
    CloseableIterable<ManifestFile> manifestFiles =
        CloseableIterable.withNoopClose(manifestFilesList);

    LoadingCache<Integer, ManifestEvaluator> evalCache =
        Caffeine.newBuilder()
            .build(
                specId -> {
                  PartitionSpec spec = table.specs().get(specId);
                  PartitionSpec transformedSpec = transformSpec(scan.tableSchema(), spec);
                  return ManifestEvaluator.forRowFilter(
                      scan.filter(), transformedSpec, scan.isCaseSensitive());
                });

    return CloseableIterable.filter(
        manifestFiles, manifest -> evalCache.get(manifest.partitionSpecId()).eval(manifest));
  }

  private class PartitionsScan extends StaticTableScan {
    PartitionsScan(Table table) {
      super(
          table,
          PartitionsTable.this.schema(),
          MetadataTableType.PARTITIONS,
          PartitionsTable.this::task);
    }
  }

  private static class PartitionComparator implements Comparator<StructLike> {
    private Comparator<StructLike> comparator;

    private PartitionComparator(Types.StructType struct) {
      this.comparator = Comparators.forType(struct);
    }

    @Override
    public int compare(StructLike o1, StructLike o2) {
      if (o1 instanceof StructProjection && o2 instanceof StructProjection) {
        int cmp =
            Integer.compare(
                ((StructProjection) o1).projectedFields(),
                ((StructProjection) o2).projectedFields());
        if (cmp != 0) {
          return cmp;
        }
      }

      return comparator.compare(o1, o2);
    }
  }

  static class Partition {
    private final PartitionData partitionData;
    private int specId;
    private long dataRecordCount;
    private int dataFileCount;
    private long dataFileSizeInBytes;
    private long posDeleteRecordCount;
    private int posDeleteFileCount;
    private long eqDeleteRecordCount;
    private int eqDeleteFileCount;
    private Long lastUpdatedAt;
    private Long lastUpdatedSnapshotId;

    Partition(StructLike key, Types.StructType keyType) {
      this.partitionData = toPartitionData(key, keyType);
      this.specId = 0;
      this.dataRecordCount = 0L;
      this.dataFileCount = 0;
      this.dataFileSizeInBytes = 0L;
      this.posDeleteRecordCount = 0L;
      this.posDeleteFileCount = 0;
      this.eqDeleteRecordCount = 0L;
      this.eqDeleteFileCount = 0;
    }

    void update(ContentFile<?> file, Snapshot snapshot) {
      if (snapshot != null) {
        long snapshotCommitTime = snapshot.timestampMillis() * 1000;
        if (this.lastUpdatedAt == null || snapshotCommitTime > this.lastUpdatedAt) {
          this.specId = file.specId();

          this.lastUpdatedAt = snapshotCommitTime;
          this.lastUpdatedSnapshotId = snapshot.snapshotId();
        }
      }

      switch (file.content()) {
        case DATA:
          this.dataRecordCount += file.recordCount();
          this.dataFileCount += 1;
          this.dataFileSizeInBytes += file.fileSizeInBytes();
          break;
        case POSITION_DELETES:
          this.posDeleteRecordCount += file.recordCount();
          this.posDeleteFileCount += 1;
          break;
        case EQUALITY_DELETES:
          this.eqDeleteRecordCount += file.recordCount();
          this.eqDeleteFileCount += 1;
          break;
        default:
          throw new UnsupportedOperationException(
              "Unsupported file content type: " + file.content());
      }
    }

    /** Needed because StructProjection is not serializable */
    private static PartitionData toPartitionData(StructLike key, Types.StructType keyType) {
      PartitionData keyTemplate = new PartitionData(keyType);
      return keyTemplate.copyFor(key);
    }
  }
}
