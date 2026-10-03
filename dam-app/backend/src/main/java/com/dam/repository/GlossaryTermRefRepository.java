package com.dam.repository;

import com.dam.domain.GlossaryTermRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GlossaryTermRefRepository extends JpaRepository<GlossaryTermRef, Long> {

    List<GlossaryTermRef> findByTermId(Long termId);

    List<GlossaryTermRef> findByAssetId(Long assetId);
}
