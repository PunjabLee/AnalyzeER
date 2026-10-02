package com.dam.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * One domain section parsed from er-model/00-总览与分组清单.md:
 * code (D01..D18 / OT), display name, declared table count and the explicit table list.
 */
public class ParsedDomain {

    private final String code;
    private final String name;
    private final int declaredCount;
    private final List<String> tables = new ArrayList<>();

    public ParsedDomain(String code, String name, int declaredCount) {
        this.code = code;
        this.name = name;
        this.declaredCount = declaredCount;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public int getDeclaredCount() { return declaredCount; }
    public List<String> getTables() { return tables; }
}
