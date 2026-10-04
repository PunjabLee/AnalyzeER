package com.dam.ingest;

/**
 * Summary returned from one ingestion run.
 *
 * <p>The asset/column counters describe the in-place upsert diff of {@code /ddl}
 * (review N-1/N-2): with a stable document, a re-run must show everything
 * <i>unchanged</i> — created/updated/evicted counts are the erosion early-warning.
 */
public class IngestReport {
    private String source;
    private int tableCount;
    private int rawCreateCount;
    private long totalColumns;
    private int assetsCreated;
    private int assetsUpdated;
    private int assetsUnchanged;
    private int assetsEvicted;
    private int columnsCreated;
    private int columnsUpdated;
    private int columnsUnchanged;
    private int columnsEvicted;
    private boolean dryRun;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public int getTableCount() { return tableCount; }
    public void setTableCount(int tableCount) { this.tableCount = tableCount; }
    public int getRawCreateCount() { return rawCreateCount; }
    public void setRawCreateCount(int rawCreateCount) { this.rawCreateCount = rawCreateCount; }
    public long getTotalColumns() { return totalColumns; }
    public void setTotalColumns(long totalColumns) { this.totalColumns = totalColumns; }
    public int getAssetsCreated() { return assetsCreated; }
    public void setAssetsCreated(int assetsCreated) { this.assetsCreated = assetsCreated; }
    public int getAssetsUpdated() { return assetsUpdated; }
    public void setAssetsUpdated(int assetsUpdated) { this.assetsUpdated = assetsUpdated; }
    public int getAssetsUnchanged() { return assetsUnchanged; }
    public void setAssetsUnchanged(int assetsUnchanged) { this.assetsUnchanged = assetsUnchanged; }
    public int getAssetsEvicted() { return assetsEvicted; }
    public void setAssetsEvicted(int assetsEvicted) { this.assetsEvicted = assetsEvicted; }
    public int getColumnsCreated() { return columnsCreated; }
    public void setColumnsCreated(int columnsCreated) { this.columnsCreated = columnsCreated; }
    public int getColumnsUpdated() { return columnsUpdated; }
    public void setColumnsUpdated(int columnsUpdated) { this.columnsUpdated = columnsUpdated; }
    public int getColumnsUnchanged() { return columnsUnchanged; }
    public void setColumnsUnchanged(int columnsUnchanged) { this.columnsUnchanged = columnsUnchanged; }
    public int getColumnsEvicted() { return columnsEvicted; }
    public void setColumnsEvicted(int columnsEvicted) { this.columnsEvicted = columnsEvicted; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    @Override
    public String toString() {
        return "IngestReport{source=" + source + ", tables=" + tableCount
                + ", rawCreates=" + rawCreateCount + ", columns=" + totalColumns
                + ", assets=" + assetsCreated + "+" + assetsUpdated + "~"
                + assetsUnchanged + "=" + assetsEvicted + "/"
                + "cols=" + columnsCreated + "+" + columnsUpdated + "~"
                + columnsUnchanged + "=" + columnsEvicted
                + (dryRun ? ", DRY-RUN" : "") + "}";
    }
}
