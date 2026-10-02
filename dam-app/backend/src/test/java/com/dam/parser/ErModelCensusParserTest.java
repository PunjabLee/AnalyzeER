package com.dam.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 exit-criterion support: the census document must parse into the documented
 * 18 A-grade domains + OT, with per-domain list sizes equal to declared counts.
 */
class ErModelCensusParserTest {

    private static List<ParsedDomain> parseCensus() throws IOException {
        Path dir = ErModelSourceLocator.locateDir(null);
        Path census = dir.resolve("00-总览与分组清单.md");
        return ErModelCensusParser.parse(Files.readAllLines(census, StandardCharsets.UTF_8));
    }

    @Test
    void parses_18_domains_plus_OT() throws IOException {
        List<ParsedDomain> domains = parseCensus();
        assertEquals(19, domains.size(), "18 D-domains + OT");
        assertEquals(332, domains.stream().filter(d -> !d.getCode().equals("OT"))
                .mapToInt(d -> d.getTables().size()).sum(), "jf_ tables across D01..D18");
    }

    @Test
    void every_domain_matches_declared_count() throws IOException {
        for (ParsedDomain d : parseCensus()) {
            assertEquals(d.getDeclaredCount(), d.getTables().size(),
                    "declared vs parsed for " + d.getCode());
        }
    }

    @Test
    void d01_and_ot_contents() throws IOException {
        List<ParsedDomain> domains = parseCensus();
        ParsedDomain d01 = domains.stream().filter(d -> d.getCode().equals("D01")).findFirst().orElseThrow();
        assertEquals(18, d01.getTables().size());
        assertTrue(d01.getTables().contains("jf_sales_order"));
        assertTrue(d01.getTables().contains("jf_sales_order_copy1"), "copy1 stays A per doc §六");

        ParsedDomain ot = domains.stream().filter(d -> d.getCode().equals("OT")).findFirst().orElseThrow();
        assertEquals(17, ot.getTables().size());
        assertTrue(ot.getTables().containsAll(List.of("country", "personnel", "sys_dict_type", "temp")));
    }
}
