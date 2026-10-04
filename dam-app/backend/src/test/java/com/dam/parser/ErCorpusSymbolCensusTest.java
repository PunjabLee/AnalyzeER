package com.dam.parser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corpus-level symbol census (S3-4): parses EVERY relation line across all {@code 01-ER图/*.md}
 * documents and asserts (a) no line is silently dropped — every relation line yields a definitive
 * {@link ParsedErRelation.Kind}, and (b) the Kind distribution stays within known bounds.
 *
 * <p>This is the regression guard the unit tests cannot give: they pin individual symbols, but a
 * symbol class silently mis-parsed at scale would sail through. If the Mermaid notation drifts or
 * the parser degrades, a bound breaks and CI reddens. Bounds are tuned to the CURRENT observed
 * corpus ({@code total=456 · ONE_TO_MANY=293 · AMBIGUOUS=126 · ONE_TO_ONE=13 ·
 * UNSUPPORTED_SYMBOL=13 · MANY_TO_MANY=3 · MANY_TO_ONE=8}) — generous enough to survive content
 * edits, tight enough to catch a bucket collapsing.
 */
class ErCorpusSymbolCensusTest {

    @Test
    void everyRelationLineGetsADefinitiveKindAndSymbolDistributionIsStable() throws Exception {
        Path erDir = ErModelSourceLocator.locateDir(null).resolve("01-ER图");
        assertThat(Files.isDirectory(erDir)).as("01-ER图 dir resolvable").isTrue();

        Map<ParsedErRelation.Kind, Integer> census = new EnumMap<>(ParsedErRelation.Kind.class);
        int total = 0;
        List<Path> files;
        try (var paths = Files.list(erDir)) {
            files = paths.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        assertThat(files).as("ER diagram docs present").isNotEmpty();

        for (Path f : files) {
            List<String> lines = Files.readAllLines(f);
            for (ParsedErRelation r : ErDiagramRelationParser.parse(lines)) {
                total++;
                // (a) no silent drop: every parsed line carries a non-null Kind
                assertThat(r.getKind()).as("definitive Kind for line @ " + f.getFileName()).isNotNull();
                census.merge(r.getKind(), 1, Integer::sum);
            }
        }

        System.out.println("ER-CORPUS-CENSUS files=" + files.size() + " total=" + total + " " + census);

        // (b) distribution bounds — the dominant direction, the two "never build an edge" buckets and
        //     the two trust-gate buckets must each stay above their observed floor.
        assertThat(total).as("total ER relation lines").isGreaterThan(430);
        assertThat(census.getOrDefault(ParsedErRelation.Kind.ONE_TO_MANY, 0))
                .as("dominant 一↔多 direction rows (child owns FK)").isGreaterThan(270);
        assertThat(census.getOrDefault(ParsedErRelation.Kind.ONE_TO_ONE, 0))
                .as("1:1 rows (enrich only, never resolve prose)").isGreaterThanOrEqualTo(12);
        assertThat(census.getOrDefault(ParsedErRelation.Kind.MANY_TO_MANY, 0))
                .as("N:N self-associations (no new edge)").isGreaterThanOrEqualTo(3);
        assertThat(census.getOrDefault(ParsedErRelation.Kind.AMBIGUOUS, 0))
                .as("symbol-vs-label cardinality clashes flagged, not silently enriched").isGreaterThanOrEqualTo(120);
        assertThat(census.getOrDefault(ParsedErRelation.Kind.UNSUPPORTED_SYMBOL, 0))
                .as("malformed single-marker rows counted, not dropped").isGreaterThanOrEqualTo(13);
        // MANY_TO_ONE (right-many rows, FK-owner written on the LEFT) exists in the corpus — measured
        // 8 — and IS asserted: S1 direction fixes turned these into a real, load-bearing bucket.
        assertThat(census.getOrDefault(ParsedErRelation.Kind.MANY_TO_ONE, 0))
                .as("reverse-direction rows").isGreaterThanOrEqualTo(8);

        // sanity: the six Kind buckets must account for exactly the total (nothing lost to null)
        int accounted = census.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(accounted).as("every line accounted in a Kind bucket").isEqualTo(total);
    }
}
