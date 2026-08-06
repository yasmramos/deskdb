package com.deskdb.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.deskdb.core.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.stream.*;
import javax.xml.transform.dom.*;
import org.w3c.dom.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builder for exporting table data to various formats.
 * Supports CSV, JSON, XML, and PARQUET formats.
 */
public class ExportBuilder {
    private final Table table;
    private final Transaction transaction;
    private final String tableName;
    private ExportFormat format = ExportFormat.CSV;
    private List<Filter> filters = new ArrayList<>();
    private String[] columns;
    private int limit = -1;
    private int offset = 0;
    
    public ExportBuilder(Table table) {
        this.table = table;
        this.transaction = null;
        this.tableName = null;
    }
    
    public ExportBuilder(Transaction transaction, String tableName) {
        this.transaction = transaction;
        this.tableName = tableName;
        this.table = null;
    }
    
    /**
     * Set the export format.
     * @param format the format to use (CSV, JSON, XML, PARQUET)
     * @return this builder for method chaining
     */
    public ExportBuilder format(ExportFormat format) {
        this.format = format;
        return this;
    }
    
    /**
     * Add a filter condition.
     * @param filter the filter to add
     * @return this builder for method chaining
     */
    public ExportBuilder where(Filter filter) {
        this.filters.add(filter);
        return this;
    }
    
    /**
     * Select specific columns to export.
     * @param columns the column names to export
     * @return this builder for method chaining
     */
    public ExportBuilder columns(String... columns) {
        this.columns = columns;
        return this;
    }
    
    /**
     * Limit the number of rows to export.
     * @param limit the maximum number of rows
     * @return this builder for method chaining
     */
    public ExportBuilder limit(int limit) {
        this.limit = limit;
        return this;
    }
    
    /**
     * Set the offset for pagination.
     * @param offset the number of rows to skip
     * @return this builder for method chaining
     */
    public ExportBuilder offset(int offset) {
        this.offset = offset;
        return this;
    }
    
    /**
     * Export to a file.
     * @param filePath the path to the output file
     * @throws Exception if an error occurs during export
     */
    public void toFile(String filePath) throws Exception {
        Path path = Paths.get(filePath);
        Path parent = path.getParent();
        
        // Check if parent directory exists, throw exception if not
        if (parent != null && Files.notExists(parent)) {
            throw new IOException("Parent directory does not exist: " + parent.toString());
        }
        
        List<Row> rows = fetchData();
        
        try (OutputStream os = Files.newOutputStream(path)) {
            switch (format) {
                case CSV:
                    exportToCSV(os, rows);
                    break;
                case JSON:
                    exportToJSON(os, rows);
                    break;
                case XML:
                    exportToXML(os, rows);
                    break;
                case PARQUET:
                    exportToParquet(os, rows);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported format: " + format);
            }
        }
    }
    
    /**
     * Export to a string.
     * @return the exported data as a string
     * @throws Exception if an error occurs during export
     */
    public String exportToString() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        List<Row> rows = fetchData();
        
        switch (format) {
            case CSV:
                exportToCSV(baos, rows);
                break;
            case JSON:
                exportToJSON(baos, rows);
                break;
            case XML:
                exportToXML(baos, rows);
                break;
            case PARQUET:
                exportToParquet(baos, rows);
                break;
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
        
        return baos.toString("UTF-8");
    }
    
    /**
     * Execute the export and return the data as a byte array.
     * @return the exported data as bytes
     * @throws Exception if an error occurs during export
     */
    public byte[] execute() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        List<Row> rows = fetchData();
        
        switch (format) {
            case CSV:
                exportToCSV(baos, rows);
                break;
            case JSON:
                exportToJSON(baos, rows);
                break;
            case XML:
                exportToXML(baos, rows);
                break;
            case PARQUET:
                exportToParquet(baos, rows);
                break;
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
        
        return baos.toByteArray();
    }
    
    private List<Row> fetchData() throws Exception {
        String actualTableName = tableName != null ? tableName : table.getName();
        DeskDB db = transaction != null ? transaction.getDb() : table.getDb();
        Table actualTable = db.getTable(actualTableName);
        
        if (actualTable == null) {
            throw new RuntimeException("Table '" + actualTableName + "' not found");
        }
        
        List<Row> rows;
        if (transaction != null) {
            rows = transaction.select(actualTableName, filters);
        } else {
            rows = actualTable.select(filters);
        }
        
        // Apply offset and limit
        if (offset > 0 && offset < rows.size()) {
            rows = rows.subList(offset, rows.size());
        }
        if (limit > 0 && limit < rows.size()) {
            rows = rows.subList(0, limit);
        }
        
        // Filter columns if specified
        if (columns != null && columns.length > 0) {
            List<Row> filteredRows = new ArrayList<>();
            for (Row row : rows) {
                Map<String, Object> filteredValues = new LinkedHashMap<>();
                for (String col : columns) {
                    if (row.getValues().containsKey(col)) {
                        filteredValues.put(col, row.getValues().get(col));
                    }
                }
                filteredRows.add(new Row(row.getRowId(), filteredValues));
            }
            rows = filteredRows;
        }
        
        return rows;
    }
    
    private void exportToCSV(OutputStream os, List<Row> rows) throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"));
        
        if (!rows.isEmpty()) {
            // Get column names from first row
            Set<String> columnNames = rows.get(0).getValues().keySet();
            
            // Write header
            writer.println(String.join(",", columnNames));
            
            // Write data
            for (Row row : rows) {
                List<String> values = new ArrayList<>();
                for (String col : columnNames) {
                    Object value = row.getValues().get(col);
                    String strValue = value == null ? "" : escapeCSV(value.toString());
                    values.add(strValue);
                }
                writer.println(String.join(",", values));
            }
        }
        
        writer.flush();
    }
    
    private String escapeCSV(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
    
    private void exportToJSON(OutputStream os, List<Row> rows) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arrayNode = mapper.createArrayNode();
        
        for (Row row : rows) {
            ObjectNode objectNode = mapper.createObjectNode();
            for (Map.Entry<String, Object> entry : row.getValues().entrySet()) {
                addValueToObjectNode(objectNode, entry.getKey(), entry.getValue());
            }
            arrayNode.add(objectNode);
        }
        
        mapper.writerWithDefaultPrettyPrinter().writeValue(os, arrayNode);
    }
    
    private void addValueToObjectNode(ObjectNode node, String key, Object value) {
        if (value == null) {
            node.set(key, com.fasterxml.jackson.databind.node.NullNode.getInstance());
        } else if (value instanceof String) {
            node.put(key, (String) value);
        } else if (value instanceof Integer) {
            node.put(key, (Integer) value);
        } else if (value instanceof Long) {
            node.put(key, (Long) value);
        } else if (value instanceof Double) {
            node.put(key, (Double) value);
        } else if (value instanceof Boolean) {
            node.put(key, (Boolean) value);
        } else if (value instanceof LocalDateTime) {
            node.put(key, ((LocalDateTime) value).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } else {
            node.put(key, value.toString());
        }
    }
    
    private void exportToXML(OutputStream os, List<Row> rows) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();
        
        String actualTableName = tableName != null ? tableName : table.getName();
        Element root = doc.createElement("data");
        root.setAttribute("table", actualTableName);
        root.setAttribute("exportDate", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        doc.appendChild(root);
        
        for (Row row : rows) {
            Element rowElement = doc.createElement("row");
            rowElement.setAttribute("id", String.valueOf(row.getRowId()));
            
            for (Map.Entry<String, Object> entry : row.getValues().entrySet()) {
                Element field = doc.createElement("field");
                field.setAttribute("name", entry.getKey());
                
                Object value = entry.getValue();
                if (value != null) {
                    field.setTextContent(value.toString());
                }
                
                rowElement.appendChild(field);
            }
            
            root.appendChild(rowElement);
        }
        
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(os);
        transformer.transform(source, result);
    }
    
    private void exportToParquet(OutputStream os, List<Row> rows) throws IOException {
        // Simplified Parquet-like binary format
        // In production, use Apache Parquet library
        DataOutputStream dos = new DataOutputStream(os);
        
        // Write magic number
        dos.writeBytes("PAR1");
        
        // Write number of rows
        dos.writeInt(rows.size());
        
        if (!rows.isEmpty()) {
            // Write column names
            Set<String> columnNames = rows.get(0).getValues().keySet();
            dos.writeInt(columnNames.size());
            for (String col : columnNames) {
                dos.writeUTF(col);
            }
            
            // Write data
            for (Row row : rows) {
                for (String col : columnNames) {
                    Object value = row.getValues().get(col);
                    writeValue(dos, value);
                }
            }
        }
        
        // Write end magic number
        dos.writeBytes("PAR1");
        dos.flush();
    }
    
    private void writeValue(DataOutputStream dos, Object value) throws IOException {
        if (value == null) {
            dos.writeByte(0); // NULL marker
        } else if (value instanceof String) {
            dos.writeByte(1); // STRING marker
            dos.writeUTF((String) value);
        } else if (value instanceof Integer) {
            dos.writeByte(2); // INT marker
            dos.writeInt((Integer) value);
        } else if (value instanceof Long) {
            dos.writeByte(3); // LONG marker
            dos.writeLong((Long) value);
        } else if (value instanceof Double) {
            dos.writeByte(4); // DOUBLE marker
            dos.writeDouble((Double) value);
        } else if (value instanceof Boolean) {
            dos.writeByte(5); // BOOLEAN marker
            dos.writeBoolean((Boolean) value);
        } else if (value instanceof LocalDateTime) {
            dos.writeByte(6); // DATETIME marker
            dos.writeUTF(((LocalDateTime) value).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } else {
            dos.writeByte(7); // UNKNOWN marker
            dos.writeUTF(value.toString());
        }
    }
    
    /**
     * Add a WHERE condition using fluent API.
     * @param column the column name
     * @return a WhereCondition builder
     */
    public WhereCondition where(String column) {
        return new WhereCondition(column, this);
    }
    
    public static class WhereCondition {
        private final String column;
        private final ExportBuilder parent;
        
        public WhereCondition(String column, ExportBuilder parent) {
            this.column = column;
            this.parent = parent;
        }
        
        public ExportBuilder eq(Object value) {
            parent.filters.add(new Filter(column, Filter.Operator.EQ, value));
            return parent;
        }
        
        public ExportBuilder gt(Object value) {
            parent.filters.add(new Filter(column, Filter.Operator.GT, value));
            return parent;
        }
        
        public ExportBuilder gte(Object value) {
            parent.filters.add(new Filter(column, Filter.Operator.GTE, value));
            return parent;
        }
        
        public ExportBuilder lt(Object value) {
            parent.filters.add(new Filter(column, Filter.Operator.LT, value));
            return parent;
        }
        
        public ExportBuilder lte(Object value) {
            parent.filters.add(new Filter(column, Filter.Operator.LTE, value));
            return parent;
        }
        
        public ExportBuilder neq(Object value) {
            parent.filters.add(new Filter(column, Filter.Operator.NEQ, value));
            return parent;
        }
    }
}
