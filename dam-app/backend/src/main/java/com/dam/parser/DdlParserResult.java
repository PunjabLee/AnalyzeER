package com.dam.parser;

import java.util.List;

/**
 * Result of parsing one DDL file: parsed tables plus raw/unique CREATE TABLE counts
 * (parity with ddl_census.ps1: raw vs uniq).
 */
public class DdlParserResult {
    private final List<ParsedTable> tables;
    private final int rawCreateCount;
    private final int uniqueTableCount;

    public DdlParserResult(List<ParsedTable> tables, int rawCreateCount, int uniqueTableCount) {
        this.tables = tables;
        this.rawCreateCount = rawCreateCount;
        this.uniqueTableCount = uniqueTableCount;
    }

    public List<ParsedTable> getTables() { return tables; }
    public int getRawCreateCount() { return rawCreateCount; }
    public int getUniqueTableCount() { return uniqueTableCount; }

    public long totalColumns() {
        return tables.stream().mapToLong(ParsedTable::getColumnCount).sum();
    }
}
