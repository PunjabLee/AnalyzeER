package com.dam.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed table from DDL: name, physical columns, PK presence, comment, charset.
 */
public class ParsedTable {
    private final String name;
    private final List<ParsedColumn> columns = new ArrayList<>();
    private boolean hasPk;
    private String tableComment;
    private String charset;
    private String collate;
    private int startLine;

    public ParsedTable(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public List<ParsedColumn> getColumns() { return columns; }
    public int getColumnCount() { return columns.size(); }
    public boolean isHasPk() { return hasPk; }
    public void setHasPk(boolean hasPk) { this.hasPk = hasPk; }
    public String getTableComment() { return tableComment; }
    public void setTableComment(String tableComment) { this.tableComment = tableComment; }
    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
    public String getCollate() { return collate; }
    public void setCollate(String collate) { this.collate = collate; }
    public int getStartLine() { return startLine; }
    public void setStartLine(int startLine) { this.startLine = startLine; }
}
