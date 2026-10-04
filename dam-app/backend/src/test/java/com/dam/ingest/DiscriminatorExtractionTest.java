package com.dam.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Review N-9: the R3 discriminator extraction had ZERO instances across the whole corpus, so no
 * existing test ever proved the feature works at all (silently dead code). These synthetic cases
 * pin the CONTRACT of the extractor directly — a positive example proves it captures an explicitly
 * stated discriminator, negative examples prove it never invents one (R4: only doc-stated text).
 * No fabricated corpus rows: the real corpus legitimately has none.
 */
class DiscriminatorExtractionTest {

    @Test
    void extractsADiscriminatorOnlyWhenTheDocSpellsItOut() {
        assertEquals("order_type",
                RelationIngestionService.extractDiscriminator("jf_order·多态（按 order_type 区分）"));
        assertEquals("biz_type",
                RelationIngestionService.extractDiscriminator("FK[A/B] 依据:命名推断 (按 biz_type 路由)"));
        assertEquals("source_no",
                RelationIngestionService.extractDiscriminator("多目标（依 source_no 判）"));
    }

    @Test
    void neverInventsADiscriminator() {
        assertNull(RelationIngestionService.extractDiscriminator("jf_reservation_stock·命名推断(多态)"));
        assertNull(RelationIngestionService.extractDiscriminator("按惯例关联客户表"));      // 无括号引导
        assertNull(RelationIngestionService.extractDiscriminator("（按 1_2）"));           // 非列名开头
        assertNull(RelationIngestionService.extractDiscriminator(null));
        assertNull(RelationIngestionService.extractDiscriminator(""));
    }
}
