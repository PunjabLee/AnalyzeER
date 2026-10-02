package com.dam.repository;

import com.dam.domain.MetaRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetaRelationRepository extends JpaRepository<MetaRelation, Long> {

    List<MetaRelation> findByFromAssetId(Long fromAssetId);

    List<MetaRelation> findByToAssetId(Long toAssetId);
}
