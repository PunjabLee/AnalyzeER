package com.dam.repository;

import com.dam.domain.MetaAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MetaAssetRepository extends JpaRepository<MetaAsset, Long> {

    Optional<MetaAsset> findByAssetUrn(String assetUrn);

    List<MetaAsset> findAllByOrderByNameAsc();

    List<MetaAsset> findByNameContainingIgnoreCaseOrderByNameAsc(String keyword);
}
