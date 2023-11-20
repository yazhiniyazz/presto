/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.iceberg;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.common.type.TypeManager;
import com.facebook.presto.hive.HdfsContext;
import com.facebook.presto.hive.HdfsEnvironment;
import com.facebook.presto.hive.HiveColumnConverterProvider;
import com.facebook.presto.hive.metastore.ExtendedHiveMetastore;
import com.facebook.presto.hive.metastore.MetastoreContext;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.TableNotFoundException;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.google.common.collect.ImmutableMap;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowLevelOperationMode;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.io.LocationProvider;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.facebook.presto.hive.metastore.MetastoreUtil.PRESTO_QUERY_ID_NAME;
import static com.facebook.presto.hive.metastore.MetastoreUtil.PRESTO_VERSION_NAME;
import static com.facebook.presto.hive.metastore.MetastoreUtil.PRESTO_VIEW_COMMENT;
import static com.facebook.presto.hive.metastore.MetastoreUtil.PRESTO_VIEW_FLAG;
import static com.facebook.presto.hive.metastore.MetastoreUtil.TABLE_COMMENT;
import static com.facebook.presto.iceberg.IcebergErrorCode.ICEBERG_INVALID_SNAPSHOT_ID;
import static com.facebook.presto.iceberg.IcebergErrorCode.ICEBERG_INVALID_TABLE_TIMESTAMP;
import static com.facebook.presto.iceberg.IcebergSessionProperties.isMergeOnReadModeEnabled;
import static com.facebook.presto.iceberg.util.IcebergPrestoModelConverters.toIcebergTableIdentifier;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Maps.immutableEntry;
import static com.google.common.collect.Streams.mapWithIndex;
import static com.google.common.collect.Streams.stream;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Comparator.comparing;
import static org.apache.iceberg.BaseMetastoreTableOperations.ICEBERG_TABLE_TYPE_VALUE;
import static org.apache.iceberg.BaseMetastoreTableOperations.TABLE_TYPE_PROP;
import static org.apache.iceberg.LocationProviders.locationsFor;
import static org.apache.iceberg.MetadataTableUtils.createMetadataTableInstance;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT_DEFAULT;
import static org.apache.iceberg.TableProperties.DELETE_MODE;
import static org.apache.iceberg.TableProperties.MERGE_MODE;
import static org.apache.iceberg.TableProperties.UPDATE_MODE;
import static org.apache.iceberg.TableProperties.WRITE_LOCATION_PROVIDER_IMPL;

public final class IcebergUtil
{
    private static final Pattern SIMPLE_NAME = Pattern.compile("[a-z][a-z0-9]*");
    private static final Logger log = Logger.get(IcebergUtil.class);

    private IcebergUtil() {}

    public static boolean isIcebergTable(com.facebook.presto.hive.metastore.Table table)
    {
        return ICEBERG_TABLE_TYPE_VALUE.equalsIgnoreCase(table.getParameters().get(TABLE_TYPE_PROP));
    }

    public static Table getIcebergTable(ConnectorMetadata metadata, ConnectorSession session, SchemaTableName table)
    {
        checkArgument(metadata instanceof IcebergAbstractMetadata, "metadata must be instance of IcebergAbstractMetadata!");
        IcebergAbstractMetadata icebergMetadata = (IcebergAbstractMetadata) metadata;
        return icebergMetadata.getIcebergTable(session, table);
    }

    public static Table getHiveIcebergTable(ExtendedHiveMetastore metastore, HdfsEnvironment hdfsEnvironment, ConnectorSession session, SchemaTableName table)
    {
        HdfsContext hdfsContext = new HdfsContext(session, table.getSchemaName(), table.getTableName());
        TableOperations operations = new HiveTableOperations(
                metastore,
                new MetastoreContext(session.getIdentity(), session.getQueryId(), session.getClientInfo(), session.getSource(), Optional.empty(), false, HiveColumnConverterProvider.DEFAULT_COLUMN_CONVERTER_PROVIDER),
                hdfsEnvironment,
                hdfsContext,
                table.getSchemaName(),
                table.getTableName());
        return new BaseTable(operations, quotedTableName(table));
    }

    public static Table getNativeIcebergTable(IcebergResourceFactory resourceFactory, ConnectorSession session, SchemaTableName table)
    {
        return resourceFactory.getCatalog(session).loadTable(toIcebergTableIdentifier(table));
    }

    public static Optional<Long> resolveSnapshotIdByName(Table table, IcebergTableName name)
    {
        if (name.getSnapshotId().isPresent()) {
            if (table.snapshot(name.getSnapshotId().get()) == null) {
                throw new PrestoException(ICEBERG_INVALID_SNAPSHOT_ID, format("Invalid snapshot [%s] for table: %s", name.getSnapshotId().get(), table));
            }
            return name.getSnapshotId();
        }
        return tryGetCurrentSnapshot(table).map(Snapshot::snapshotId);
    }

    public static long getSnapshotIdAsOfTime(Table table, long millisUtc)
    {
        return table.history().stream()
                .filter(logEntry -> logEntry.timestampMillis() <= millisUtc)
                .max(comparing(HistoryEntry::timestampMillis))
                .orElseThrow(() -> new PrestoException(ICEBERG_INVALID_TABLE_TIMESTAMP, format("No history found based on timestamp for table %s", table.name())))
                .snapshotId();
    }

    public static List<IcebergColumnHandle> getColumns(Schema schema, TypeManager typeManager)
    {
        return schema.columns().stream()
                .map(column -> IcebergColumnHandle.create(column, typeManager))
                .collect(toImmutableList());
    }

    public static Map<PartitionField, Integer> getIdentityPartitions(PartitionSpec partitionSpec)
    {
        // TODO: expose transform information in Iceberg library
        ImmutableMap.Builder<PartitionField, Integer> columns = ImmutableMap.builder();
        for (int i = 0; i < partitionSpec.fields().size(); i++) {
            PartitionField field = partitionSpec.fields().get(i);
            if (field.transform().toString().equals("identity")) {
                columns.put(field, i);
            }
        }
        return columns.build();
    }

    public static FileFormat getFileFormat(Table table)
    {
        return FileFormat.valueOf(table.properties()
                .getOrDefault(DEFAULT_FILE_FORMAT, DEFAULT_FILE_FORMAT_DEFAULT)
                .toUpperCase(Locale.ENGLISH));
    }

    public static Optional<String> getTableComment(Table table)
    {
        return Optional.ofNullable(table.properties().get(TABLE_COMMENT));
    }

    private static String quotedTableName(SchemaTableName name)
    {
        return quotedName(name.getSchemaName()) + "." + quotedName(name.getTableName());
    }

    private static String quotedName(String name)
    {
        if (SIMPLE_NAME.matcher(name).matches()) {
            return name;
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    public static TableScan getTableScan(TupleDomain<IcebergColumnHandle> predicates, Optional<Long> snapshotId, Table icebergTable)
    {
        Expression expression = ExpressionConverter.toIcebergExpression(predicates);
        TableScan tableScan = icebergTable.newScan().filter(expression);
        return snapshotId
                .map(id -> isSnapshot(icebergTable, id) ? tableScan.useSnapshot(id) : tableScan.asOfTime(id))
                .orElse(tableScan);
    }

    private static boolean isSnapshot(Table icebergTable, Long id)
    {
        return stream(icebergTable.snapshots())
                .anyMatch(snapshot -> snapshot.snapshotId() == id);
    }

    public static LocationProvider getLocationProvider(SchemaTableName schemaTableName, String tableLocation, Map<String, String> storageProperties)
    {
        if (storageProperties.containsKey(WRITE_LOCATION_PROVIDER_IMPL)) {
            throw new PrestoException(NOT_SUPPORTED, "Table " + schemaTableName + " specifies " + storageProperties.get(WRITE_LOCATION_PROVIDER_IMPL) +
                    " as a location provider. Writing to Iceberg tables with custom location provider is not supported.");
        }
        return locationsFor(tableLocation, storageProperties);
    }

    public static TableScan buildTableScan(Table icebergTable, MetadataTableType metadataTableType)
    {
        return createMetadataTableInstance(icebergTable, metadataTableType).newScan();
    }

    public static Map<String, Integer> columnNameToPositionInSchema(Schema schema)
    {
        return mapWithIndex(schema.columns().stream(),
                (column, position) -> immutableEntry(column.name(), toIntExact(position)))
                .collect(toImmutableMap(Entry::getKey, Entry::getValue));
    }

    public static void validateTableMode(ConnectorSession session, org.apache.iceberg.Table table)
    {
        if (isMergeOnReadModeEnabled(session)) {
            return;
        }

        String deleteMode = table.properties().get(DELETE_MODE);
        String mergeMode = table.properties().get(MERGE_MODE);
        String updateMode = table.properties().get(UPDATE_MODE);
        if (Stream.of(deleteMode, mergeMode, updateMode).anyMatch(s -> Objects.equals(s, RowLevelOperationMode.MERGE_ON_READ.modeName()))) {
            throw new PrestoException(NOT_SUPPORTED, "merge-on-read table mode not supported yet");
        }
    }

    public static Map<String, String> createIcebergViewProperties(ConnectorSession session, String prestoVersion)
    {
        return ImmutableMap.<String, String>builder()
                .put(TABLE_COMMENT, PRESTO_VIEW_COMMENT)
                .put(PRESTO_VIEW_FLAG, "true")
                .put(PRESTO_VERSION_NAME, prestoVersion)
                .put(PRESTO_QUERY_ID_NAME, session.getQueryId())
                .put(TABLE_TYPE_PROP, ICEBERG_TABLE_TYPE_VALUE)
                .build();
    }

    public static Optional<Map<String, String>> tryGetProperties(Table table)
    {
        try {
            return Optional.ofNullable(table.properties());
        }
        catch (TableNotFoundException e) {
            log.warn(String.format("Unable to fetch properties for table %s: %s", table.name(), e.getMessage()));
            return Optional.empty();
        }
    }

    public static Optional<Snapshot> tryGetCurrentSnapshot(Table table)
    {
        try {
            return Optional.ofNullable(table.currentSnapshot());
        }
        catch (TableNotFoundException e) {
            log.warn(String.format("Unable to fetch snapshot for table %s: %s", table.name(), e.getMessage()));
            return Optional.empty();
        }
    }
}
