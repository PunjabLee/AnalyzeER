package com.dam.ingest;

/**
 * Summary returned from one ingestion run.
 */
public class IngestReport {
    private String source;
    private int tableCount;
    private int rawCreateCount;
    private long totalColumns;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public int getTableCount() { return tableCount; }
    public void setTableCount(int tableCount) { this.tableCount = tableCount; }
    public int getRawCreateCount() { return rawCreateCount; }
    public void setRawCreateCount(int rawCreateCount) { this.rawCreateCount = rawCreateCount; }
    public long getTotalColumns() { return totalColumns; }
    public void setTotalColumns(long totalColumns) { this.totalColumns = totalColumns; }

    @Override
    public String toString() {
        return "IngestReport{source=" + source + ", tables=" + tableCount
                + ", rawCreates=" + rawCreateCount + ", columns=" + totalColumns + "}";
    }
}
