package com.deskdb.query;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.deskdb.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builder for importing table data from various formats.
 * Supports CSV, JSON, XML, and PARQUET formats.
 */
public class ImportBuilder {
    private final Table table;
    private final Transaction transaction;
    private final String tableName;
    private ImportFormat format = ImportFormat.CSV;
    private boolean skipHeader = true;
    private String[] columns;
    private int batchSize = 1000;
    private static final AtomicLong nextRowId = new AtomicLong(0);
    
    public ImportBuilder(Table table) {
        this.table = table;
        this.transaction = null;
        this.tableName = null;
    }
    
    public ImportBuilder(Transaction transaction, String tableName) {
        this.transaction = transaction;
        this.tableName = tableName;
        this.table = null;
    }
    
    /**
     * Set the import format.
     * @param format the format to use (CSV, JSON, XML, PARQUET)
     * @return this builder for method chaining
     */
    public ImportBuilder format(ImportFormat format) {
        this.format = format;
        return this;
    }
    
    /**
     * Set whether to skip the header row in CSV files.
     * @param skipHeader true to skip header
     * @return this builder for method chaining
     */
    public ImportBuilder skipHeader(boolean skipHeader) {
        this.skipHeader = skipHeader;
        return this;
    }
    
    /**
     * Specify which columns to import.
     * @param columns the column names
     * @return this builder for method chaining
     */
    public ImportBuilder columns(String... columns) {
        this.columns = columns;
        return this;
    }
    
    /**
     * Set the batch size for bulk inserts.
     * @param batchSize the number of rows per batch
     * @return this builder for method chaining
     */
    public ImportBuilder batchSize(int batchSize) {
        this.batchSize = batchSize;
        return this;
    }
    
    /**
     * Import from a file.
     * @param filePath the path to the input file
     * @return the number of rows imported
     * @throws Exception if an error occurs during import
     */
    public int fromFile(String filePath) throws Exception {
        Path path = Paths.get(filePath);
        
        if (!Files.exists(path)) {
            throw new FileNotFoundException("File not found: " + filePath);
        }
        
        try (InputStream is = Files.newInputStream(path)) {
            return importFromStream(is);
        }
    }
    
    /**
     * Import from a string.
     * @param data the data to import
     * @return the number of rows imported
     * @throws Exception if an error occurs during import
     */
    public int fromString(String data) throws Exception {
        try (InputStream is = new ByteArrayInputStream(data.getBytes("UTF-8"))) {
            return importFromStream(is);
        }
    }
    
    /**
     * Import from a byte array.
     * @param data the data to import
     * @return the number of rows imported
     * @throws Exception if an error occurs during import
     */
    public int fromBytes(byte[] data) throws Exception {
        try (InputStream is = new ByteArrayInputStream(data)) {
            return importFromStream(is);
        }
    }
    
    /**
     * Execute the import from a stream.
     * @param is the input stream
     * @return the number of rows imported
     * @throws Exception if an error occurs during import
     */
    public int execute(InputStream is) throws Exception {
        return importFromStream(is);
    }
    
    private int importFromStream(InputStream is) throws Exception {
        List<Map<String, Object>> rows;
        
        switch (format) {
            case CSV:
                rows = importFromCSV(is);
                break;
            case JSON:
                rows = importFromJSON(is);
                break;
            case XML:
                rows = importFromXML(is);
                break;
            case PARQUET:
                rows = importFromParquet(is);
                break;
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
        
        // Filter columns if specified
        if (columns != null && columns.length > 0) {
            List<Map<String, Object>> filteredRows = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> filteredRow = new LinkedHashMap<>();
                for (String col : columns) {
                    if (row.containsKey(col)) {
                        filteredRow.put(col, row.get(col));
                    }
                }
                filteredRows.add(filteredRow);
            }
            rows = filteredRows;
        }
        
        // Insert rows in batches
        return insertRows(rows);
    }
    
    private List<Map<String, Object>> importFromCSV(InputStream is) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        
        String line;
        String[] headers = null;
        int rowNum = 0;
        
        while ((line = reader.readLine()) != null) {
            rowNum++;
            
            // Skip header if configured
            if (skipHeader && rowNum == 1) {
                headers = parseCSVLine(line);
                continue;
            }
            
            // Auto-detect headers if not skipping
            if (headers == null) {
                headers = parseCSVLine(line);
                continue;
            }
            
            String[] values = parseCSVLine(line);
            Map<String, Object> row = new LinkedHashMap<>();
            
            for (int i = 0; i < headers.length && i < values.length; i++) {
                String value = values[i].trim();
                Object parsedValue = parseValue(value);
                row.put(headers[i], parsedValue);
            }
            
            rows.add(row);
        }
        
        return rows;
    }
    
    private String[] parseCSVLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                if (inQuotes && i < line.length() - 1 && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++; // Skip next quote
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        result.add(current.toString());
        return result.toArray(new String[0]);
    }
    
    private List<Map<String, Object>> importFromJSON(InputStream is) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        
        JsonNode rootNode = mapper.readTree(is);
        
        if (rootNode.isArray()) {
            for (JsonNode node : rootNode) {
                Map<String, Object> row = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    row.put(field.getKey(), parseJsonNode(field.getValue()));
                }
                rows.add(row);
            }
        } else if (rootNode.isObject()) {
            // Single object
            Map<String, Object> row = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                row.put(field.getKey(), parseJsonNode(field.getValue()));
            }
            rows.add(row);
        }
        
        return rows;
    }
    
    private Object parseJsonNode(JsonNode node) {
        if (node.isNull()) {
            return null;
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isInt()) {
            return node.asInt();
        } else if (node.isLong()) {
            return node.asLong();
        } else if (node.isDouble()) {
            return node.asDouble();
        } else if (node.isTextual()) {
            String text = node.asText();
            // Try to parse as date/time
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception e) {
                return text;
            }
        } else {
            return node.asText();
        }
    }
    
    private List<Map<String, Object>> importFromXML(InputStream is) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        
        SAXParserFactory factory = SAXParserFactory.newInstance();
        SAXParser parser = factory.newSAXParser();
        
        XMLReader reader = parser.getXMLReader();
        XMLHandler handler = new XMLHandler();
        reader.setContentHandler(handler);
        
        reader.parse(new InputSource(is));
        rows.addAll(handler.getRows());
        
        return rows;
    }
    
    private static class XMLHandler extends DefaultHandler {
        private List<Map<String, Object>> rows = new ArrayList<>();
        private Map<String, Object> currentRow;
        private String currentField;
        private StringBuilder currentValue;
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("row".equals(qName)) {
                currentRow = new LinkedHashMap<>();
                currentValue = new StringBuilder();
            } else if ("field".equals(qName)) {
                currentField = attributes.getValue("name");
                currentValue = new StringBuilder();
            }
        }
        
        @Override
        public void characters(char[] ch, int start, int length) {
            if (currentValue != null) {
                currentValue.append(ch, start, length);
            }
        }
        
        @Override
        public void endElement(String uri, String localName, String qName) {
            if ("row".equals(qName) && currentRow != null) {
                rows.add(currentRow);
                currentRow = null;
            } else if ("field".equals(qName) && currentField != null && currentRow != null) {
                String value = currentValue.toString().trim();
                currentRow.put(currentField, ImportBuilder.parseValue(value));
                currentField = null;
            }
        }
        
        public List<Map<String, Object>> getRows() {
            return rows;
        }
    }
    
    private List<Map<String, Object>> importFromParquet(InputStream is) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        DataInputStream dis = new DataInputStream(is);
        
        // Read magic number
        byte[] magic = new byte[4];
        dis.readFully(magic);
        if (!new String(magic).equals("PAR1")) {
            throw new IOException("Invalid Parquet file: missing magic number");
        }
        
        // Read number of rows
        int rowCount = dis.readInt();
        
        if (rowCount > 0) {
            // Read column names
            int columnCount = dis.readInt();
            String[] columnNames = new String[columnCount];
            for (int i = 0; i < columnCount; i++) {
                columnNames[i] = dis.readUTF();
            }
            
            // Read data
            for (int r = 0; r < rowCount; r++) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String col : columnNames) {
                    Object value = readValue(dis);
                    row.put(col, value);
                }
                rows.add(row);
            }
        }
        
        // Read end magic number
        dis.readFully(magic);
        
        return rows;
    }
    
    private Object readValue(DataInputStream dis) throws IOException {
        byte type = dis.readByte();
        
        switch (type) {
            case 0: // NULL
                return null;
            case 1: // STRING
                return dis.readUTF();
            case 2: // INT
                return dis.readInt();
            case 3: // LONG
                return dis.readLong();
            case 4: // DOUBLE
                return dis.readDouble();
            case 5: // BOOLEAN
                return dis.readBoolean();
            case 6: // DATETIME
                String dateTimeStr = dis.readUTF();
                try {
                    return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (Exception e) {
                    return dateTimeStr;
                }
            default: // UNKNOWN
                return dis.readUTF();
        }
    }
    
    private static Object parseValue(String value) {
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("null")) {
            return null;
        }
        
        // Try to parse as integer
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // Not an integer
        }
        
        // Try to parse as long
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // Not a long
        }
        
        // Try to parse as double
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            // Not a double
        }
        
        // Try to parse as boolean
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        
        // Try to parse as date/time
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            // Not a date/time
        }
        
        // Return as string
        return value;
    }
    
    private int insertRows(List<Map<String, Object>> rows) throws Exception {
        if (rows.isEmpty()) {
            return 0;
        }
        
        String actualTableName = tableName != null ? tableName : table.getName();
        DeskDB db = transaction != null ? transaction.getDb() : table.getDb();
        Table actualTable = db.getTable(actualTableName);
        
        if (actualTable == null) {
            throw new RuntimeException("Table '" + actualTableName + "' not found");
        }
        
        int totalInserted = 0;
        
        if (transaction != null) {
            // Use provided transaction
            for (Map<String, Object> row : rows) {
                Row newRow = new Row(nextRowId.getAndIncrement(), row);
                actualTable.insert(newRow);
                totalInserted++;
            }
        } else {
            // Auto-commit with batching
            try (Transaction autoTx = db.beginTransaction()) {
                int count = 0;
                for (Map<String, Object> row : rows) {
                    Row newRow = new Row(nextRowId.getAndIncrement(), row);
                    actualTable.insert(newRow);
                    totalInserted++;
                    count++;
                    
                    // Commit batch
                    if (count >= batchSize) {
                        autoTx.commit();
                        count = 0;
                    }
                }
                
                // Commit remaining
                if (count > 0) {
                    autoTx.commit();
                }
            }
        }
        
        return totalInserted;
    }
}
