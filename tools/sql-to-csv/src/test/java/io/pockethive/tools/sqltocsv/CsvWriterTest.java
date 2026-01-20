package io.pockethive.tools.sqltocsv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("CsvWriter Unit Tests")
class CsvWriterTest {

    @Mock
    private ResultSetMetaData metadata;

    @Mock
    private ResultSet resultSet;

    private CsvWriter csvWriter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        csvWriter = new CsvWriter(",", "NULL");
    }

    @Test
    @DisplayName("Should write header with column names")
    void shouldWriteHeaderWithColumnNames() throws Exception {
        when(metadata.getColumnCount()).thenReturn(3);
        when(metadata.getColumnName(1)).thenReturn("id");
        when(metadata.getColumnName(2)).thenReturn("name");
        when(metadata.getColumnName(3)).thenReturn("email");

        String header = csvWriter.writeHeader(metadata);

        assertThat(header).isEqualTo("id,name,email");
    }

    @Test
    @DisplayName("Should write row with values")
    void shouldWriteRowWithValues() throws Exception {
        when(resultSet.getObject(1)).thenReturn(1);
        when(resultSet.getObject(2)).thenReturn("John Doe");
        when(resultSet.getObject(3)).thenReturn("john@example.com");

        String row = csvWriter.writeRow(resultSet, 3);

        assertThat(row).isEqualTo("1,John Doe,john@example.com");
    }

    @Test
    @DisplayName("Should escape values with delimiter")
    void shouldEscapeValuesWithDelimiter() throws Exception {
        when(resultSet.getObject(1)).thenReturn(1);
        when(resultSet.getObject(2)).thenReturn("Doe, John");
        when(resultSet.getObject(3)).thenReturn("test@example.com");

        String row = csvWriter.writeRow(resultSet, 3);

        assertThat(row).isEqualTo("1,\"Doe, John\",test@example.com");
    }

    @Test
    @DisplayName("Should escape values with quotes")
    void shouldEscapeValuesWithQuotes() throws Exception {
        when(resultSet.getObject(1)).thenReturn(1);
        when(resultSet.getObject(2)).thenReturn("John \"Johnny\" Doe");
        when(resultSet.getObject(3)).thenReturn("test@example.com");

        String row = csvWriter.writeRow(resultSet, 3);

        assertThat(row).isEqualTo("1,\"John \"\"Johnny\"\" Doe\",test@example.com");
    }

    @Test
    @DisplayName("Should escape values with newlines")
    void shouldEscapeValuesWithNewlines() throws Exception {
        when(resultSet.getObject(1)).thenReturn(1);
        when(resultSet.getObject(2)).thenReturn("Line1\nLine2");
        when(resultSet.getObject(3)).thenReturn("test@example.com");

        String row = csvWriter.writeRow(resultSet, 3);

        assertThat(row).isEqualTo("1,\"Line1\nLine2\",test@example.com");
    }

    @Test
    @DisplayName("Should handle NULL values")
    void shouldHandleNullValues() throws Exception {
        when(resultSet.getObject(1)).thenReturn(1);
        when(resultSet.getObject(2)).thenReturn(null);
        when(resultSet.getObject(3)).thenReturn("test@example.com");

        String row = csvWriter.writeRow(resultSet, 3);

        assertThat(row).isEqualTo("1,NULL,test@example.com");
    }

    @Test
    @DisplayName("Should use custom delimiter")
    void shouldUseCustomDelimiter() throws Exception {
        CsvWriter tabWriter = new CsvWriter("\t", "NULL");
        when(resultSet.getObject(1)).thenReturn(1);
        when(resultSet.getObject(2)).thenReturn("John Doe");
        when(resultSet.getObject(3)).thenReturn("test@example.com");

        String row = tabWriter.writeRow(resultSet, 3);

        assertThat(row).isEqualTo("1\tJohn Doe\ttest@example.com");
    }

    @Test
    @DisplayName("Should use custom NULL value")
    void shouldUseCustomNullValue() throws Exception {
        CsvWriter customWriter = new CsvWriter(",", "N/A");
        when(resultSet.getObject(1)).thenReturn(1);
        when(resultSet.getObject(2)).thenReturn(null);
        when(resultSet.getObject(3)).thenReturn("test@example.com");

        String row = customWriter.writeRow(resultSet, 3);

        assertThat(row).isEqualTo("1,N/A,test@example.com");
    }

    @Test
    @DisplayName("Should handle empty string values")
    void shouldHandleEmptyStringValues() throws Exception {
        when(resultSet.getObject(1)).thenReturn(1);
        when(resultSet.getObject(2)).thenReturn("");
        when(resultSet.getObject(3)).thenReturn("test@example.com");

        String row = csvWriter.writeRow(resultSet, 3);

        assertThat(row).isEqualTo("1,,test@example.com");
    }

    @Test
    @DisplayName("Should handle single column")
    void shouldHandleSingleColumn() throws Exception {
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnName(1)).thenReturn("id");

        String header = csvWriter.writeHeader(metadata);

        assertThat(header).isEqualTo("id");
    }
}
