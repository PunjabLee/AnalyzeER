package com.dam.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * One relation line from an {@code 01-ER图/*.md} Mermaid {@code erDiagram} block, e.g.
 * <pre>
 *   jf_customer ||--o{ jf_sales_order : "1:N [命名推断] order.customer_id -&gt; customer.id (NOT NULL)"
 * </pre>
 * The channel-1 ER evidence uniquely carries <b>cardinality</b> (from the {@code ||--o{} } symbol)
 * and an explicit <b>evidence tag</b> ({@code [命名推断]} / {@code [注释明示]} / ...), which the
 * logical-FK channel-2 lacks. Structure is parsed here; catalog validation (does each side really
 * exist, does the child really own the FK column) happens in the ingestion service, never assumed.
 */
public class ParsedErRelation {

    /** left entity in the diagram = the "one"/parent side */
    private final String parentTable;
    /** right entity = the "many"/child side that owns the FK column */
    private final String childTable;
    /** 1:1 or 1:N, derived from the relationship symbol */
    private final String cardinality;
    /** five-level evidence (注释明示/索引佐证/字段命名/业务语义推断/待确认) */
    private final String evidenceLevel;
    private final double confidence;
    /** raw label inside the double quotes (kept as evidence text) */
    private final String labelRaw;
    /** candidate FK column names seen as {@code alias.col} before any {@code ->} */
    private final List<String> fromColumnCandidates = new ArrayList<>();
    /** target column seen after {@code ->} (e.g. customer.id -> "id"), may be null */
    private String toColumnCandidate;
    /** cross-domain code from a [跨域Dxx] tag, may be null */
    private String crossDomain;

    public ParsedErRelation(String parentTable, String childTable, String cardinality,
                            String evidenceLevel, double confidence, String labelRaw) {
        this.parentTable = parentTable;
        this.childTable = childTable;
        this.cardinality = cardinality;
        this.evidenceLevel = evidenceLevel;
        this.confidence = confidence;
        this.labelRaw = labelRaw;
    }

    public String getParentTable() { return parentTable; }
    public String getChildTable() { return childTable; }
    public String getCardinality() { return cardinality; }
    public String getEvidenceLevel() { return evidenceLevel; }
    public double getConfidence() { return confidence; }
    public String getLabelRaw() { return labelRaw; }
    public List<String> getFromColumnCandidates() { return fromColumnCandidates; }
    public String getToColumnCandidate() { return toColumnCandidate; }
    public void setToColumnCandidate(String toColumnCandidate) { this.toColumnCandidate = toColumnCandidate; }
    public String getCrossDomain() { return crossDomain; }
    public void setCrossDomain(String crossDomain) { this.crossDomain = crossDomain; }
}
