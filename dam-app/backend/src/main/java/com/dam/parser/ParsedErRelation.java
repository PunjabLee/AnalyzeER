package com.dam.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * One relation line from an {@code 01-ER图/*.md} Mermaid {@code erDiagram} block, e.g.
 * <pre>
 *   jf_customer ||--o{ jf_sales_order : "1:N [命名推断] order.customer_id -&gt; customer.id (NOT NULL)"
 *   jf_product_color_coordina_mapping }o--o{ jf_product : "M:N [命名推断·编码] pccm.sku -&gt; product.sku"
 *   jf_collection_notice ||--o{ jf_invoice_application : "N:N [命名推断·编码] app.collection_bill_codes(多单号串)"
 * </pre>
 * Channel-1 ER evidence uniquely carries <b>cardinality</b> and an explicit <b>evidence tag</b> — the
 * two things logical-FK channel-2 lacks. The relationship symbol's <em>left/right crow-foot
 * markers</em> decide which side owns the FK (the "many" side) and whether the direction is even
 * unambiguous, so we keep BOTH endpoint names and a {@link Kind} instead of assuming right=child.
 *
 * <p><b>Zero-fabrication guard (S1/S1-3 review remediation):</b> a line only yields a trustworthy
 * {@code 1:N}/{@code N:1}/{@code 1:1}/{@code M:N} {@link Kind} when BOTH the symbol markers AND the
 * label's own cardinality token (when present, e.g. {@code "N:N …"}) agree. Any clash demotes the
 * line to {@link Kind#AMBIGUOUS} so the ingestion service never builds OR resolves an edge from it.
 * A malformed symbol missing one side's marker (e.g. {@code A ..o{ B}) yields
 * {@link Kind#UNSUPPORTED_SYMBOL} — counted, never silently dropped. Catalog validation (endpoints
 * exist / the child really owns the FK column) is still the caller's job; nothing here asserts the
 * real schema.
 */
public class ParsedErRelation {

    /** Relationship trust level derived from the symbol's left/right markers, cross-checked with the label. */
    public enum Kind {
        /** left is one/optional, right is many → FK lives on the RIGHT (child); may build & resolve */
        ONE_TO_MANY,
        /** left is many, right is one/optional → FK lives on the LEFT (child); may build & resolve */
        MANY_TO_ONE,
        /** both sides one/optional (1:1 / 1:0..1) → FK side symbol-ambiguous → enrich only, never resolve */
        ONE_TO_ONE,
        /** both sides many (M:N) → not a single directed FK → skip */
        MANY_TO_MANY,
        /** symbol markers and the label's own cardinality token disagree → untrusted → skip entirely */
        AMBIGUOUS,
        /** relationship symbol missing one side's marker (author typo, e.g. {@code ..o{}) → skip, but count */
        UNSUPPORTED_SYMBOL
    }

    private final String leftTable;
    private final String rightTable;
    private final Kind kind;
    /** 1:1 / 1:N / N:M derived from the kind; null for AMBIGUOUS / UNSUPPORTED_SYMBOL */
    private final String cardinality;
    /** five-level evidence (注释明示/索引佐证/字段命名/业务语义推断/待确认) */
    private final String evidenceLevel;
    private final double confidence;
    /** raw label inside the double quotes (kept as evidence text; also scanned for denial keywords) */
    private final String labelRaw;
    /** candidate FK column names seen as {@code alias.col} (or bare {@code *_id/_code}) before the first separator */
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

    /** only a clearly-oriented one&lt;-&gt;many edge may create a brand-new directed edge */
    public boolean allowsNewEdge() {
        return kind == Kind.ONE_TO_MANY || kind == Kind.MANY_TO_ONE;
    }

    /**
     * Whether this line may resolve a channel-2 prose edge to a concrete parent. Same trust bar as
     * {@link #allowsNewEdge()}: an ambiguous 1:1 line cannot tell which side owns the FK, so it may
     * enrich an existing edge's cardinality but must never pin a new target (S1-1).
     */
    public boolean canResolveProse() {
        return allowsNewEdge();
    }

    /** true for kinds the ingestion service skips entirely (still tallied, never silently dropped). */
    public boolean isUntrusted() {
        return kind == Kind.AMBIGUOUS || kind == Kind.UNSUPPORTED_SYMBOL;
    }
}
