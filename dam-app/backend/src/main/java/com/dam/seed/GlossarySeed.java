package com.dam.seed;

import com.dam.domain.GlossaryTerm;
import com.dam.repository.GlossaryTermRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Startup seed for the business glossary (capability M3). Installs a couple of clearly
 * labelled EXAMPLE terms in DRAFT status so the glossary UI and the term-to-object binding
 * have something to act on out of the box.
 *
 * <p>Deliberately does NOT pre-bind terms to assets (that would couple to ingestion runner
 * order and assert business semantics); binding is an operator action via the API / drag UI.
 * Per R4 the definition/caliber are placeholders pending a real business Owner.
 */
@Configuration
public class GlossarySeed {

    @Bean
    ApplicationRunner seedGlossaryBasics(GlossaryTermRepository terms) {
        return args -> {
            seedTerm(terms, "贸易商", "D08",
                    "示例术语：与外部贸易往来主体的业务口径待 Owner 补录（R4）");
            seedTerm(terms, "销售订单", "D01",
                    "示例术语：销售下单主对象的业务口径待 Owner 补录（R4）");
        };
    }

    private static void seedTerm(GlossaryTermRepository terms, String name, String domain, String note) {
        if (terms.findByName(name).isPresent()) {
            return;
        }
        GlossaryTerm t = new GlossaryTerm();
        t.setName(name);
        t.setDomainCode(domain);
        t.setStatus("DRAFT");
        t.setNote(note);
        terms.save(t);
    }
}
