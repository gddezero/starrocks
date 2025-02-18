// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.catalog;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.starrocks.analysis.DescriptorTable;
import com.starrocks.common.io.Text;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.connector.iceberg.IcebergApiConverter;
import com.starrocks.connector.iceberg.IcebergCatalogType;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TColumn;
import com.starrocks.thrift.TIcebergTable;
import com.starrocks.thrift.TTableDescriptor;
import com.starrocks.thrift.TTableType;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.PartitionField;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.starrocks.connector.iceberg.IcebergConnector.ICEBERG_CATALOG_TYPE;
import static com.starrocks.server.CatalogMgr.ResourceMappingCatalog.getResourceMappingCatalogName;

public class IcebergTable extends Table {
    private static final Logger LOG = LogManager.getLogger(IcebergTable.class);

    private static final String JSON_KEY_ICEBERG_DB = "database";
    private static final String JSON_KEY_ICEBERG_TABLE = "table";
    private static final String JSON_KEY_RESOURCE_NAME = "resource";
    private static final String JSON_KEY_ICEBERG_PROPERTIES = "icebergProperties";

    private org.apache.iceberg.Table nativeTable; // actual iceberg table
    private String catalogName;
    private String remoteDbName;
    private String remoteTableName;
    private String resourceName;

    private Map<String, String> icebergProperties = Maps.newHashMap();

    public IcebergTable() {
        super(TableType.ICEBERG);
    }

    public IcebergTable(long id, String srTableName, String catalogName, String resourceName, String remoteDbName,
                        String remoteTableName, List<Column> schema, org.apache.iceberg.Table nativeTable,
                        Map<String, String> icebergProperties) {
        super(id, srTableName, TableType.ICEBERG, schema);
        this.catalogName = catalogName;
        this.resourceName = resourceName;
        this.remoteDbName = remoteDbName;
        this.remoteTableName = remoteTableName;
        this.nativeTable = nativeTable;
        this.icebergProperties = icebergProperties;
    }

    public String getCatalogName() {
        return catalogName == null ? getResourceMappingCatalogName(resourceName, "iceberg") : catalogName;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getRemoteDbName() {
        return remoteDbName;
    }

    public String getRemoteTableName() {
        return remoteTableName;
    }

    @Override
    public String getUUID() {
        return String.join(".", catalogName, remoteDbName, remoteTableName, Long.toString(createTime));
    }

    public List<Column> getPartitionColumns() {
        List<PartitionField> identityPartitionFields = this.getNativeTable().spec().fields().stream().
                filter(partitionField -> partitionField.transform().isIdentity()).collect(Collectors.toList());
        return identityPartitionFields.stream().map(partitionField -> getColumn(partitionField.name())).collect(
                Collectors.toList());
    }

    public boolean isUnPartitioned() {
        return getPartitionColumns().size() == 0;
    }

    public List<String> getPartitionColumnNames() {
        return getPartitionColumns().stream().filter(Objects::nonNull).map(Column::getName)
                .collect(Collectors.toList());
    }

    @Override
    public String getTableIdentifier() {
        return Joiner.on(":").join(remoteTableName, ((BaseTable) getNativeTable()).operations().current().uuid());
    }

    public IcebergCatalogType getCatalogType() {
        return IcebergCatalogType.valueOf(icebergProperties.get(ICEBERG_CATALOG_TYPE));
    }

    public String getTableLocation() {
        return getNativeTable().location();
    }

    public org.apache.iceberg.Table getNativeTable() {
        // For compatibility with the resource iceberg table. native table is lazy. Prevent failure during fe restarting.
        if (nativeTable == null) {
            IcebergTable resourceMappingTable = (IcebergTable) GlobalStateMgr.getCurrentState().getMetadataMgr()
                    .getTable(getCatalogName(), remoteDbName, remoteTableName);
            if (resourceMappingTable == null) {
                throw new StarRocksConnectorException("Can't find table %s.%s.%s",
                        getCatalogName(), remoteDbName, remoteTableName);
            }
            nativeTable = resourceMappingTable.getNativeTable();
        }
        return nativeTable;
    }

    @Override
    public TTableDescriptor toThrift(List<DescriptorTable.ReferencedPartitionInfo> partitions) {
        Preconditions.checkNotNull(partitions);

        TIcebergTable tIcebergTable = new TIcebergTable();

        List<TColumn> tColumns = Lists.newArrayList();
        for (Column column : getBaseSchema()) {
            tColumns.add(column.toThrift());
        }
        tIcebergTable.setColumns(tColumns);

        tIcebergTable.setIceberg_schema(IcebergApiConverter.getTIcebergSchema(nativeTable.schema()));

        TTableDescriptor tTableDescriptor = new TTableDescriptor(id, TTableType.ICEBERG_TABLE,
                fullSchema.size(), 0, remoteTableName, remoteDbName);
        tTableDescriptor.setIcebergTable(tIcebergTable);
        return tTableDescriptor;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        super.write(out);

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(JSON_KEY_ICEBERG_DB, remoteDbName);
        jsonObject.addProperty(JSON_KEY_ICEBERG_TABLE, remoteTableName);
        if (!Strings.isNullOrEmpty(resourceName)) {
            jsonObject.addProperty(JSON_KEY_RESOURCE_NAME, resourceName);
        }
        if (!icebergProperties.isEmpty()) {
            JsonObject jIcebergProperties = new JsonObject();
            for (Map.Entry<String, String> entry : icebergProperties.entrySet()) {
                jIcebergProperties.addProperty(entry.getKey(), entry.getValue());
            }
            jsonObject.add(JSON_KEY_ICEBERG_PROPERTIES, jIcebergProperties);
        }
        Text.writeString(out, jsonObject.toString());
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        super.readFields(in);

        String json = Text.readString(in);
        JsonObject jsonObject = JsonParser.parseString(json).getAsJsonObject();
        remoteDbName = jsonObject.getAsJsonPrimitive(JSON_KEY_ICEBERG_DB).getAsString();
        remoteTableName = jsonObject.getAsJsonPrimitive(JSON_KEY_ICEBERG_TABLE).getAsString();
        resourceName = jsonObject.getAsJsonPrimitive(JSON_KEY_RESOURCE_NAME).getAsString();
        if (jsonObject.has(JSON_KEY_ICEBERG_PROPERTIES)) {
            JsonObject jIcebergProperties = jsonObject.getAsJsonObject(JSON_KEY_ICEBERG_PROPERTIES);
            for (Map.Entry<String, JsonElement> entry : jIcebergProperties.entrySet()) {
                icebergProperties.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long id;
        private String srTableName;
        private String catalogName;
        private String resourceName;
        private String remoteDbName;
        private String remoteTableName;
        private List<Column> fullSchema;
        private Map<String, String> icebergProperties;
        private org.apache.iceberg.Table nativeTable;

        public Builder() {
        }

        public Builder setId(long id) {
            this.id = id;
            return this;
        }

        public Builder setSrTableName(String srTableName) {
            this.srTableName = srTableName;
            return this;
        }

        public Builder setCatalogName(String catalogName) {
            this.catalogName = catalogName;
            return this;
        }

        public Builder setResourceName(String resourceName) {
            this.resourceName = resourceName;
            return this;
        }

        public Builder setRemoteDbName(String remoteDbName) {
            this.remoteDbName = remoteDbName;
            return this;
        }

        public Builder setRemoteTableName(String remoteTableName) {
            this.remoteTableName = remoteTableName;
            return this;
        }

        public Builder setFullSchema(List<Column> fullSchema) {
            this.fullSchema = fullSchema;
            return this;
        }

        public Builder setIcebergProperties(Map<String, String> icebergProperties) {
            this.icebergProperties = icebergProperties;
            return this;
        }

        public Builder setNativeTable(org.apache.iceberg.Table nativeTable) {
            this.nativeTable = nativeTable;
            return this;
        }

        public IcebergTable build() {
            return new IcebergTable(id, srTableName, catalogName, resourceName, remoteDbName, remoteTableName,
                    fullSchema, nativeTable, icebergProperties);
        }
    }
}
