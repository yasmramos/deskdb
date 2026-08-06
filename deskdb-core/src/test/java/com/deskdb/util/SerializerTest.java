package com.deskdb.util;

import com.deskdb.core.Row;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Serializer class, focusing on BigDecimal (DECIMAL) support.
 */
public class SerializerTest {

    @Test
    public void testSerializeDeserializeRowWithBigDecimal() throws IOException {
        Row originalRow = new Row(1L);
        originalRow.set("id", 1);
        originalRow.set("name", "Test Product");
        originalRow.set("price", new BigDecimal("99.99"));
        originalRow.set("quantity", 100);

        byte[] serialized = Serializer.serializeRow(originalRow);
        Row deserializedRow = Serializer.deserializeRow(serialized);

        assertEquals(originalRow.getRowId(), deserializedRow.getRowId());
        assertEquals(originalRow.get("id"), deserializedRow.get("id"));
        assertEquals(originalRow.get("name"), deserializedRow.get("name"));
        assertEquals(originalRow.get("price"), deserializedRow.get("price"));
        assertEquals(originalRow.get("quantity"), deserializedRow.get("quantity"));
    }

    @Test
    public void testBigDecimalPrecision() throws IOException {
        Row originalRow = new Row(2L);
        BigDecimal preciseValue = new BigDecimal("123456789.123456789012345678");
        originalRow.set("precise_decimal", preciseValue);

        byte[] serialized = Serializer.serializeRow(originalRow);
        Row deserializedRow = Serializer.deserializeRow(serialized);

        BigDecimal deserializedValue = (BigDecimal) deserializedRow.get("precise_decimal");
        assertEquals(preciseValue, deserializedValue);
        assertEquals(preciseValue.scale(), deserializedValue.scale());
        assertEquals(preciseValue.precision(), deserializedValue.precision());
    }

    @Test
    public void testBigDecimalNegativeValue() throws IOException {
        Row originalRow = new Row(3L);
        BigDecimal negativeValue = new BigDecimal("-999.99");
        originalRow.set("negative_decimal", negativeValue);

        byte[] serialized = Serializer.serializeRow(originalRow);
        Row deserializedRow = Serializer.deserializeRow(serialized);

        BigDecimal deserializedValue = (BigDecimal) deserializedRow.get("negative_decimal");
        assertEquals(negativeValue, deserializedValue);
    }

    @Test
    public void testBigDecimalZero() throws IOException {
        Row originalRow = new Row(4L);
        BigDecimal zeroValue = new BigDecimal("0.00");
        originalRow.set("zero_decimal", zeroValue);

        byte[] serialized = Serializer.serializeRow(originalRow);
        Row deserializedRow = Serializer.deserializeRow(serialized);

        BigDecimal deserializedValue = (BigDecimal) deserializedRow.get("zero_decimal");
        assertEquals(zeroValue, deserializedValue);
    }

    @Test
    public void testMixedDataTypesWithBigDecimal() throws IOException {
        Row originalRow = new Row(5L);
        originalRow.set("string_val", "Hello");
        originalRow.set("int_val", 42);
        originalRow.set("long_val", 1000000L);
        originalRow.set("double_val", 3.14159);
        originalRow.set("boolean_val", true);
        originalRow.set("decimal_val", new BigDecimal("123.456"));

        byte[] serialized = Serializer.serializeRow(originalRow);
        Row deserializedRow = Serializer.deserializeRow(serialized);

        assertEquals(originalRow.getRowId(), deserializedRow.getRowId());
        assertEquals(originalRow.get("string_val"), deserializedRow.get("string_val"));
        assertEquals(originalRow.get("int_val"), deserializedRow.get("int_val"));
        assertEquals(originalRow.get("long_val"), deserializedRow.get("long_val"));
        assertEquals(originalRow.get("double_val"), deserializedRow.get("double_val"));
        assertEquals(originalRow.get("boolean_val"), deserializedRow.get("boolean_val"));
        assertEquals(originalRow.get("decimal_val"), deserializedRow.get("decimal_val"));
    }
}
