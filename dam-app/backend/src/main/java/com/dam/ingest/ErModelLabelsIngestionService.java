package com.dam.ingest;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaDomain;
import com.dam.parser.ErModelCensusParser;
import com.dam.parser.ErModelSourceLocator;
import com.dam.parser.ParsedDomain;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaDomainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M1 second ingestion channel (labels): reads er-model/00-总览与分组清单.md domain lists and
 * assigns grading (A/B/C) + domain_code (D01..D18/OT/B/C) to every meta_asset row.
 *
 * <p>Hard exit criteria checked here (PLAN §6 M1, er-model §五 数量校验):
 * A=349(332 jf_ + 17 OT) / B=853(450 lcap + 275 N-hex + 128 P-hex) / C=120(117 bak + 3 test),
 * and every per-domain table count must equal the declared count.
 */
@Service
public class ErModelLabelsIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ErModelLabelsIngestionService.class);

    private final MetaAssetRepository assetRepo;
    private final MetaDomainRepository domainRepo;

    public ErModelLabelsIngestionService(MetaAssetRepository assetRepo, MetaDomainRepository domainRepo) {
        this.assetRepo = assetRepo;
        this.domainRepo = domainRepo;
    }

    public record LabelsReport(int total, int a, int b, int c, int unassigned, int domains,
                               Map<String, Integer> perDomain) {
        @Override
        public String toString() {
            return "LabelsReport{total=" + total + ", A=" + a + ", B=" + b + ", C=" + c
                    + ", unassigned=" + unassigned + ", domains=" + domains + "}";
        }
    }

    @Transactional
    public LabelsReport ingest(String erModelDir) {
        Path dir = ErModelSourceLocator.locateDir(erModelDir);
        List<ParsedDomain> parsed = parseCensus(dir);

        // table -> domain code, and cross-check each domain list against its declared count
        Map<String, String> aIndex = new HashMap<>();
        for (ParsedDomain d : parsed) {
            if (d.getTables().size() != d.getDeclaredCount()) {
                throw new IllegalStateException("domain " + d.getCode() + " list size "
                        + d.getTables().size() + " != declared " + d.getDeclaredCount());
            }
            for (String t : d.getTables()) {
                if (aIndex.put(t, d.getCode()) != null) {
                    throw new IllegalStateException("table in two domain lists: " + t);
                }
            }
        }

        int a = 0;
        int b = 0;
        int c = 0;
        int un = 0;
        Map<String, Integer> perDomain = new HashMap<>();
        List<MetaAsset> assets = assetRepo.findAllByOrderByNameAsc();
        for (MetaAsset asset : assets) {
            GradingAssigner.Assignment as = GradingAssigner.assign(asset.getName(), aIndex);
            asset.setGrading(as.grading());
            asset.setDomainCode(as.domainCode());
            if (as.unassigned()) {
                un++;
            } else {
                perDomain.merge(as.domainCode(), 1, Integer::sum);
                switch (as.grading()) {
                    case "A" -> a++;
                    case "B" -> b++;
                    default -> c++;
                }
            }
        }
        assetRepo.saveAll(assets);

        // rebuild the domain registry (18 A-domains + OT + B/C pseudo domains)
        domainRepo.deleteAllInBatch();
        List<MetaDomain> domains = new ArrayList<>();
        for (ParsedDomain d : parsed) {
            domains.add(domain(d.getCode(), d.getName(), "A", d.getDeclaredCount()));
        }
        domains.add(domain(GradingAssigner.DOMAIN_B, "B级平台框架同构表", "B", b));
        domains.add(domain(GradingAssigner.DOMAIN_C, "C级备份与测试表", "C", c));
        domainRepo.saveAll(domains);

        LabelsReport report = new LabelsReport(assets.size(), a, b, c, un, domains.size(), perDomain);
        log.info("Labels ingested from {}: {}", dir.getFileName(), report);
        return report;
    }

    private static MetaDomain domain(String code, String name, String grading, int declared) {
        MetaDomain m = new MetaDomain();
        m.setCode(code);
        m.setName(name);
        m.setGrading(grading);
        m.setDeclaredCount(declared);
        return m;
    }

    static List<ParsedDomain> parseCensus(Path erModelDir) {
        Path census = erModelDir.resolve("00-总览与分组清单.md");
        try {
            return ErModelCensusParser.parse(Files.readAllLines(census, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read census doc: " + census, e);
        }
    }
}
