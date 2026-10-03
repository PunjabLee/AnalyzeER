package com.dam.repository;

import com.dam.domain.ModelPdm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModelPdmRepository extends JpaRepository<ModelPdm, Long> {

    Optional<ModelPdm> findByName(String name);
}
