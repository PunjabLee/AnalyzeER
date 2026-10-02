package com.dam.repository;

import com.dam.domain.MetaColumn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetaColumnRepository extends JpaRepository<MetaColumn, Long> {

    List<MetaColumn> findByAssetIdOrderByOrdinalAsc(Long assetId);

    long countByAssetId(Long assetId);
}
