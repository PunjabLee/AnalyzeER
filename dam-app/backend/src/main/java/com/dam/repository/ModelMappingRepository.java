package com.dam.repository;

import com.dam.domain.ModelMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelMappingRepository extends JpaRepository<ModelMapping, Long> {

    List<ModelMapping> findByBomId(Long bomId);

    Optional<ModelMapping> findByBomIdAndLdmId(Long bomId, Long ldmId);
}
