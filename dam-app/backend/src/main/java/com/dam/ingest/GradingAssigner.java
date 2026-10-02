package com.dam.ingest;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure grading/domain decision (no Spring, unit-testable), mirroring the bucket rules of
 * er-model/00-总览与分组清单.md §三/§四:
 * <ul>
 *   <li>C: {@code *_bak_<digits>} backups + {@code test_} copies (117 + 3 = 120)</li>
 *   <li>A: explicit membership in the 18-domain lists (332 jf_) + OT list (17) = 349;
 *       note {@code jf_goods_bak} (no timestamp) stays A by the documented rule</li>
 *   <li>B: isomorphic platform families lcap_ (450) / N{hex} Quartz (275) / P{hex} Activiti (128) = 853</li>
 * </ul>
 */
public final class GradingAssigner {

    private static final Pattern BAK_DATE = Pattern.compile("_bak_\\d{6,}$");
    private static final Pattern TEST_COPY = Pattern.compile("^test_");
    private static final Pattern LCAP = Pattern.compile("^lcap_");
    private static final Pattern HEX_FAMILY = Pattern.compile("^[NP][0-9A-Fa-f]{7}_");

    /** B/C pseudo-domain codes (see meta_domain). */
    public static final String DOMAIN_B = "B";
    public static final String DOMAIN_C = "C";

    public record Assignment(String grading, String domainCode) {
        public boolean unassigned() { return grading == null; }
    }

    private GradingAssigner() { }

    /**
     * @param aIndex table name -> domain code (D01..D18/OT) built from the census document
     */
    public static Assignment assign(String tableName, Map<String, String> aIndex) {
        if (BAK_DATE.matcher(tableName).find() || TEST_COPY.matcher(tableName).find()) {
            return new Assignment("C", DOMAIN_C);
        }
        String domain = aIndex.get(tableName);
        if (domain != null) {
            return new Assignment("A", domain);
        }
        if (LCAP.matcher(tableName).find() || HEX_FAMILY.matcher(tableName).find()) {
            return new Assignment("B", DOMAIN_B);
        }
        return new Assignment(null, null);
    }
}
