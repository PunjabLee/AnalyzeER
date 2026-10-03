package com.dam.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * One relation line from an {@code 01-ER图/*.md} Mermaid {@code erDiagram} block, e.g.
 * <pre>
 *   jf_customer ||--o{ jf_sales_order : "1:N [命名推断] order.customer_id -&gt; customer.id (NOT NULL)"
 *   jf_product_color_coordina_mapping }o--o{ jf_product : "M:N [命名推断·编码] pccm.sku -&gt; product.sku"
 * </pre>
 * Channel-1 ER evidence uniquely carries <b>cardinality</b> and an explicit <b>evidence tag</b> —
 * the two things logical-FK channel-2 lacks. The relationship symbol's <em>left/right crow-foot
 * markers</em> decide which side owns the FK (the "many" side) and whether the direction is even
 * unambiguous, so we keep BOTH endpoint names and a {@link Kind} instead of assuming right=child:
 * {@code ||}/{@code |o} = exactly/zero-or-one, {@code }|}/{@code }o} = one-or-more/zero-or-more.
 * Catalog validation (endpoints exist / the child really owns the FK column) is the caller's job —
 * nothing here is asserted about the real schema.
 */
public class ParsedErRelation {

    /** Relationship cardinality kind derived from the symbol's left/right markers. */
    public enum Kind {
        /** left is one/optional, right is many → FK lives on the RIGHT (child), new edge allowed */
        ONE_TO_MANY,
        /** left is many, right is one/optional → FK lives on the LEFT (child), new edge allowed */
        MANY_TO_ONE,
        /** both sides one/optional (1:1 / 1:0..1) → FK side is symbol-ambiguous → enrich only */
        ONE_TO_ONE,
        /** both sides many (M:N) → not a single directed FK → skip */
        MANY_TO_MANY
    }

    private final String leftTable;
    private final String rightTable;
    private final Kind kind;
    /** 1:1 / 1:N / N:M derived from the kind */
    private final String cardinality;
    /** five-level evidence (注释明示/索引佐证/字段命名/业务语义推断/待确认) */
    private final String evidenceLevel;
    private final double confidence;
    /** raw label inside the double quotes (kept as evidence text) */
    private final String labelRaw;
    /** candidate FK column names seen as {@code alias.col} before the first target separator */
    private final List<String> fromColumnCandidates = new ArrayList<>();
    /** target column seen after the separator (e.g. customer.id -> "id"), may be null */
    private String toColumnCandidate;
    /** cross-domain code from a [跨域Dxx] tag, may be null */
    private String crossDomain;

    public ParsedErRelation(String leftTable, String rightTable, Kind kind, String cardinality,
                            String evidenceLevel, double confidence, String labelRaw) {
        this.leftTable = leftTable;
        this.rightTable = rightTable;
        this.kind = kind;
        this.cardinality = cardinality;
        this.evidenceLevel = evidenceLevel;
        this.confidence = confidence;
        this.labelRaw = labelRaw;
    }

    public String getLeftTable() { return leftTable; }
    public String getRightTable() { return rightTable; }
    public Kind getKind() { return kind; }
    public String getCardinality() { return cardinality; }
    public String getEvidenceLevel() { return evidenceLevel; }
    public double getConfidence() { return confidence; }
    public String getLabelRaw() { return labelRaw; }
    public List<String> getFromColumnCandidates() { return fromColumnCandidates; }
    public String getToColumnCandidate() { return toColumnCandidate; }
    public void setToColumnCandidate(String toColumnCandidate) { this.toColumnCandidate = toColumnCandidate; }
    public String getCrossDomain() { return crossDomain; }
    public void setCrossDomain(String crossDomain) { this.crossDomain = crossDomain; }

    /** only a clearly-oriented one<->many edge may create a brand-new directed edge */
    public boolean allowsNewEdge() {
        return kind == Kind.ONE_TO_MANY || kind == Kind.MANY_TO_ONE;
    }
}
