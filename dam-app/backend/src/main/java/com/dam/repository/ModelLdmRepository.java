package com.dam.repository;

import com.dam.domain.ModelLdm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModelLdmRepository extends JpaRepository<ModelLdm, Long> {

    Optional<ModelLdm> findByName(String name);
}
