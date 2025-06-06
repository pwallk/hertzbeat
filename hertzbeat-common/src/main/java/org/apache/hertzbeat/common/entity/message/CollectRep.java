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

package org.apache.hertzbeat.common.entity.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.table.ArrowTable;
import org.apache.arrow.vector.table.Row;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.hertzbeat.common.constants.MetricDataConstants;
import org.apache.hertzbeat.common.entity.arrow.RowWrapper;
import org.apache.hertzbeat.common.util.JsonUtil;

@SuppressWarnings("all")
@Slf4j
public final class CollectRep {
    private CollectRep() {}

    /**
     * Collect response status code enum
     */
    public enum Code {
        /**
         * collect success
         */
        SUCCESS(0),

        /**
         * collector not available
         */
        UN_AVAILABLE(1),

        /**
         * peer network un reachable(icmp)
         */
        UN_REACHABLE(2),

        /**
         * peer network server un connectable(tcp,udp...)
         */
        UN_CONNECTABLE(3),

        /**
         * collect metrics data failed(http,ssh,snmp...)
         */
        FAIL(4),

        /**
         * collect metrics data timeout
         */
        TIMEOUT(5),

        /**
         * unrecognized collect response code
         */
        UNRECOGNIZED(-1);

        private final int value;

        Code(int value) {
            this.value = value;
        }

        public int getNumber() {
            if (this == UNRECOGNIZED) {
                throw new IllegalArgumentException("Can't get the number of an unknown enum value.");
            }
            return value;
        }

        public static Code forNumber(int value) {
            switch (value) {
                case 0: return SUCCESS;
                case 1: return UN_AVAILABLE;
                case 2: return UN_REACHABLE;
                case 3: return UN_CONNECTABLE;
                case 4: return FAIL;
                case 5: return TIMEOUT;
                default: return null;
            }
        }
    }

    /**
     * Metrics data collect response entity
     */
    public static class MetricsData implements AutoCloseable {

        /**
         * arrow collect metrics data table
         */
        private ArrowTable table;

        public MetricsData(ArrowTable table) {
            this.table = table;
        }
        
        public MetricsData(VectorSchemaRoot vectorSchemaRoot) {
            this.table = new ArrowTable(vectorSchemaRoot);
        }

        public static Builder newBuilder() {
            return new Builder();
        }
        
        public static Builder newBuilder(MetricsData metricsData) {
            Builder builder = new Builder();
            builder.addMetadataAll(metricsData.getMetadata());
            metricsData.getFields().forEach(builder::addField);
            metricsData.getValues().forEach(builder::addValueRow);
            return builder;
        }
        
        public long rowCount() {
            return table != null ? table.getRowCount() : 0;
        }
        
        public ArrowTable getTable() {
            return table;
        }

        /**
         * notice is to vectorschemaRoot for arrow, the table will empty
         * @return
         */
        public VectorSchemaRoot toVectorSchemaRootAndRelease() {
            return table != null ? table.toVectorSchemaRoot() : null;
        }

        /**
         * to byte array and release the table
         * @return
         */
        public byte[] toByteArrayAndRelease() {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 VectorSchemaRoot root = table.toVectorSchemaRoot();
                 ArrowStreamWriter writer = new ArrowStreamWriter(root,
                         null, Channels.newChannel(out))) {
                writer.start();
                writer.writeBatch();
                writer.end();
                return out.toByteArray();
            } catch (IOException e) {
                log.error(e.getMessage(), e);
                return null;
            }
        }
        
        public long getId() {
            Map<String, String> metadata = getMetadata();
            return Long.parseLong(metadata.getOrDefault(MetricDataConstants.ID, "0"));
        }

        public long getTenantId() {
            Map<String, String> metadata = getMetadata();
            return Long.parseLong(metadata.getOrDefault(MetricDataConstants.TENANT_ID, "0"));
        }

        public String getApp() {
            Map<String, String> metadata = getMetadata();
            return metadata.getOrDefault(MetricDataConstants.APP, "");
        }

        public String getMetrics() {
            Map<String, String> metadata = getMetadata();
            return metadata.getOrDefault(MetricDataConstants.METRICS, "");
        }

        public int getPriority() {
            Map<String, String> metadata = getMetadata();
            return Integer.parseInt(metadata.getOrDefault(MetricDataConstants.PRIORITY, "0"));
        }

        public long getTime() {
            Map<String, String> metadata = getMetadata();
            return Long.parseLong(metadata.getOrDefault(MetricDataConstants.TIME, "0"));
        }

        public Code getCode() {
            Map<String, String> metadata = getMetadata();
            return Code.forNumber(Integer.parseInt(metadata.getOrDefault(MetricDataConstants.CODE, "0")));
        }

        public String getMsg() {
            Map<String, String> metadata = getMetadata();
            return metadata.getOrDefault(MetricDataConstants.MSG, "");
        }

        public String getInstanceName() {
            Map<String, String> metadata = getMetadata();
            return metadata.getOrDefault(MetricDataConstants.INSTANCE_NAME, null);
        }

        public String getInstanceHost() {
            Map<String, String> metadata = getMetadata();
            return metadata.getOrDefault(MetricDataConstants.INSTANCE_HOST, null);
        }

        public Map<String, String> getLabels() {
            Map<String, String> metadata = getMetadata();
            String labelStr = metadata.getOrDefault(MetricDataConstants.LABELS, "");
            if (labelStr == null || "".equals(labelStr)) {
                return null;
            }
            return JsonUtil.fromJson(labelStr, Map.class);
        }

        public Map<String, String> getAnnotations() {
            Map<String, String> metadata = getMetadata();
            String annotationStr = metadata.getOrDefault(MetricDataConstants.ANNOTATIONS, "");
            if (annotationStr == null || "".equals(annotationStr)) {
                return null;
            }
            return JsonUtil.fromJson(annotationStr, Map.class);
        }

        private Map<String, String> getMetadata() {
            return table != null ? table.getSchema().getCustomMetadata() : new HashMap<>();
        }

        // Get fields and values from table
        public List<Field> getFields() {
            if (table == null) {
                return new ArrayList<>();
            }
            return table.getSchema().getFields().stream()
                .map(field -> {
                    Map<String, String> metadata = field.getMetadata();
                    return Field.newBuilder()
                        .setName(field.getName())
                        .setType(Integer.parseInt(metadata.getOrDefault(MetricDataConstants.TYPE, "0")))
                        .setUnit(metadata.getOrDefault(MetricDataConstants.UNIT, ""))
                        .setLabel(Boolean.parseBoolean(metadata.getOrDefault(MetricDataConstants.LABEL, "false")))
                        .build();
                })
                .collect(Collectors.toList());
        }

        public List<ValueRow> getValues() {
            if (table == null) {
                return new ArrayList<>();
            }

            List<String> fieldNames = table.getSchema().getFields().stream()
                .map(org.apache.arrow.vector.types.pojo.Field::getName)
                .collect(Collectors.toList());

            List<ValueRow> values = new ArrayList<>();
            Iterator<Row> iterator = table.iterator();
            while (iterator.hasNext()) {
                Row row = iterator.next();
                ValueRow valueRow = ValueRow.newBuilder()
                    .setColumns(fieldNames.stream()
                        .map(fieldName -> new String(((VarCharVector)
                            table.getVector(fieldName)).get(row.getRowNumber())))
                        .collect(Collectors.toList()))
                    .build();
                values.add(valueRow);
            }
            return values;
        }
        
        public RowWrapper readRow() {
            final Iterator<Row> rowIterator = table.iterator();
            List<org.apache.arrow.vector.types.pojo.Field> fields = table.getSchema().getFields();
            return new RowWrapper(null, rowIterator, fields, -1);
        }

        public int getValuesCount() {
            return table != null ? (int)table.getRowCount() : 0;
        }

        public int getFieldsCount() {
            return table != null ? table.getSchema().getFields().size() : 0;
        }

        @Override
        public void close() {
            if (table != null) {
                table.close();
            }
        }

        // Builder remains mostly the same, but build() method changes
        public static class Builder {
            
            private Map<String, String> metadata = new HashMap<>();
            private List<Field> fields = new ArrayList<>();
            private List<ValueRow> values = new ArrayList<>();
            
            public Builder setId(long id) {
                metadata.put(MetricDataConstants.ID, String.valueOf(id));
                return this;
            }

            public Builder setTenantId(long tenantId) {
                metadata.put(MetricDataConstants.TENANT_ID, String.valueOf(tenantId));
                return this;
            }

            public Builder setApp(String app) {
                metadata.put(MetricDataConstants.APP, app != null ? app : "");
                return this;
            }

            public Builder setMetrics(String metrics) {
                metadata.put(MetricDataConstants.METRICS, metrics != null ? metrics : "");
                return this;
            }
            
            public Builder setLabels(Map<String, String> labels) {
                if (labels == null || labels.isEmpty()) {
                    return this;
                }
                String labelStr = JsonUtil.toJson(labels);
                metadata.put(MetricDataConstants.LABELS, labelStr);
                return this;
            }
            
            public Builder setAnnotations(Map<String, String> annotations) {
                if (annotations == null || annotations.isEmpty()) {
                    return this;
                }
                String annotationStr = JsonUtil.toJson(annotations);
                metadata.put(MetricDataConstants.ANNOTATIONS, annotationStr);
                return this;
            }

            public Builder setPriority(int priority) {
                metadata.put(MetricDataConstants.PRIORITY, String.valueOf(priority));
                return this;
            }

            public Builder setTime(long time) {
                metadata.put(MetricDataConstants.TIME, String.valueOf(time));
                return this;
            }

            public Builder setCode(Code code) {
                metadata.put(MetricDataConstants.CODE, String.valueOf(code != null ? code.value : 0));
                return this;
            }

            public Builder setMsg(String msg) {
                metadata.put(MetricDataConstants.MSG, msg != null ? msg : "");
                return this;
            }
            
            public Builder addMetadata(String key, String value) {
                if (key == null || "".equals(key)) {
                    return this;
                }
                metadata.put(key, value);
                return this;
            }
            
            public Builder addMetadataAll(Map<String, String> meta) {
                if (meta == null || meta.isEmpty()) {
                    return this;
                }
                this.metadata.putAll(meta);
                return this;
            }

            public Builder addField(Field field) {
                this.fields.add(field);
                return this;
            }

            public Builder addValueRow(ValueRow valueRow) {
                this.values.add(valueRow);
                return this;
            }

            public MetricsData build() {
                BufferAllocator allocator = new RootAllocator();
                try {
                    // Create Arrow fields with metadata
                    List<org.apache.arrow.vector.types.pojo.Field> arrowFields = fields.stream()
                            .map(field -> {
                                Map<String, String> fieldMetadata = new HashMap<>();
                                fieldMetadata.put(MetricDataConstants.TYPE, String.valueOf(field.getType()));
                                fieldMetadata.put(MetricDataConstants.UNIT, field.getUnit());
                                fieldMetadata.put(MetricDataConstants.LABEL, String.valueOf(field.getLabel()));

                                return new org.apache.arrow.vector.types.pojo.Field(
                                        field.getName(),
                                        new FieldType(true, new ArrowType.Utf8(), null, fieldMetadata),
                                        null);
                            })
                            .collect(Collectors.toList());

                    // Create Schema with metadata
                    Schema schema = new Schema(arrowFields, metadata);
                    VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
                    
                    try {
                        root.allocateNew();
                        int rowCount = values.size();
                        root.setRowCount(rowCount);
                        // Write values
                        for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
                            VarCharVector vector = (VarCharVector) root.getVector(fieldIndex);
                            vector.allocateNew();

                            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                                ValueRow row = values.get(rowIndex);
                                if (row != null && row.getColumnsList() != null &&
                                        fieldIndex < row.getColumnsList().size()) {
                                    String value = row.getColumns(fieldIndex);
                                    if (value != null) {
                                        // Check byte array size, Arrow buffer size is 32768 bytes
                                        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                                        vector.setSafe(rowIndex, bytes);
                                    }
                                }
                            }
                            vector.setValueCount(rowCount);
                        }
                        return new MetricsData(new ArrowTable(root));
                    } catch (Exception e1) {
                        log.error(e1.getMessage(), e1);
                        throw e1;
                    } finally {
                        root.close();
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    allocator.close();
                    throw e;
                }
            }
            
            public long getId() {
                return Long.parseLong(metadata.getOrDefault(MetricDataConstants.ID, "0"));
            }

            public long getTenantId() {
                return Long.parseLong(metadata.getOrDefault(MetricDataConstants.TENANT_ID, "0"));
            }

            public String getApp() {
                return metadata.getOrDefault(MetricDataConstants.APP, "");
            }

            public String getMetrics() {
                return metadata.getOrDefault(MetricDataConstants.METRICS, "");
            }

            public int getPriority() {
                return Integer.parseInt(metadata.getOrDefault(MetricDataConstants.PRIORITY, "0"));
            }

            public long getTime() {
                return Integer.parseInt(metadata.getOrDefault(MetricDataConstants.TIME, "0"));
            }

            public Code getCode() {
                return Code.forNumber(Integer.parseInt(metadata.getOrDefault(MetricDataConstants.CODE, "0")));
            }

            public String getMsg() {
                return metadata.getOrDefault(MetricDataConstants.MSG, "");
            }
            
            public String getInstanceName() {
                return metadata.getOrDefault(MetricDataConstants.INSTANCE_NAME, null);
            }
            
            public String getInstanceHost() {
                return metadata.getOrDefault(MetricDataConstants.INSTANCE_HOST, null);
            }
            
            public Map<String, String> getLabels() {
                String labelStr = metadata.getOrDefault(MetricDataConstants.LABELS, "");
                if (labelStr == null || "".equals(labelStr)) {
                    return null;
                }
                return JsonUtil.fromJson(labelStr, Map.class);
            }
            
            public Map<String, String> getAnnotations() {
                String annotationStr = metadata.getOrDefault(MetricDataConstants.ANNOTATIONS, "");
                if (annotationStr == null || "".equals(annotationStr)) {
                    return null;
                }
                return JsonUtil.fromJson(annotationStr, Map.class);
            }
            
            public Map<String, String> getMetadata() {
                return metadata;
            }

            public List<Field> getFieldsList() {
                return fields;
            }

            public Field getFields(int index) {
                return fields.get(index);
            }

            public int getFieldsCount() {
                return fields.size();
            }

            public List<ValueRow> getValuesList() {
                return values;
            }

            public ValueRow getValues(int index) {
                return values.get(index);
            }

            public int getValuesCount() {
                return values.size();
            }

            public void addAllFields(List<Field> fieldList) {
                fields.addAll(fieldList);
            }

            public void clearValues() {
                values = new LinkedList<>();
            }

            public void clearFields() {
                fields = new LinkedList<>();
            }
        }
    }

    /**
     * Metrics field entity
     */
    public static class Field {
        /**
         * monitoring collect metric field name
         */
        private String name;

        /**
         * monitoring collect metrics field type, 0-number 1-string
         */
        private int type;

        /**
         * monitoring collect metrics field unit, % MB GB TB S...
         */
        private String unit;

        /**
         * is label field
         */
        private boolean label;

        public Field() {
            name = "";
            unit = "";
        }

        // Getters and setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getType() {
            return type;
        }

        public void setType(int type) {
            this.type = type;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public boolean getLabel() {
            return label;
        }

        public void setLabel(boolean label) {
            this.label = label;
        }

        public static Builder newBuilder() {
            return new Builder();
        }

        public boolean equals(Object o) {
            if(!(o instanceof Field))
                return false;
            Field other = (Field)o;
            return other.name.equals(this.name);
        }

        public static class Builder {
            private final Field instance;

            private Builder() {
                instance = new Field();
            }

            public Builder setName(String name) {
                instance.setName(name);
                return this;
            }

            public Builder setType(int type) {
                instance.setType(type);
                return this;
            }

            public Builder setUnit(String unit) {
                instance.setUnit(unit);
                return this;
            }

            public Builder setLabel(boolean label) {
                instance.setLabel(label);
                return this;
            }

            public Field build() {
                return instance;
            }
        }
    }

    /**
     * Metrics value row entity
     */
    public static class ValueRow {
        /**
         * monitoring collect metrics value, mapping with the fields
         */
        private List<String> columns;

        public ValueRow() {
            columns = new LinkedList<>();
        }
        
        public ValueRow(List<String> columns) {
            this.columns = new LinkedList<>();
            this.columns.addAll(columns);
        }

        public List<String> getColumnsList() {
            return columns;
        }

        public void setColumns(List<String> columns) {
            this.columns = columns;
        }

        public void addColumns(String value) {
            if (value == null) {
                throw new NullPointerException();
            }
            columns.add(value);
        }

        public void clearColumns() {
            columns = new LinkedList<>();
        }

        public String getColumns(int index) {
            return columns.get(index);
        }

        public int getColumnsCount() {
            return columns.size();
        }

        public static Builder newBuilder() {
            return new Builder();
        }

        public static class Builder {
            private List<String> columns;

            private Builder() {
                columns = new LinkedList<>();
            }

            public Builder setColumns(List<String> columns) {
                this.columns = columns;
                return this;
            }

            public Builder addColumn(String column) {
                columns.add(column);
                return this;
            }

            public Builder addAllColumns(Iterable<String> columns) {
                for (String column : columns) {
                    this.columns.add(column);
                }
                return this;
            }

            public ValueRow build() {
                return new ValueRow(columns);
            }

            public void clear() {
                columns.clear();
            }
        }
    }
}
