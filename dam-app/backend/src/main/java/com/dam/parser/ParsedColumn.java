package com.dam.parser;

/**
 * Parsed physical column from DDL (no Spring dependency; unit-testable).
 */
public class ParsedColumn {
    private final String name;
    private String type;
    private boolean notNull;
    private String defaultValue;
    private String comment;
    private boolean autoIncrement;
    private boolean primaryKey;

    public ParsedColumn(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public boolean isNotNull() { return notNull; }
    public void setNotNull(boolean notNull) { this.notNull = notNull; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public boolean isAutoIncrement() { return autoIncrement; }
    public void setAutoIncrement(boolean autoIncrement) { this.autoIncrement = autoIncrement; }
    public boolean isPrimaryKey() { return primaryKey; }
    public void setPrimaryKey(boolean primaryKey) { this.primaryKey = primaryKey; }
}
