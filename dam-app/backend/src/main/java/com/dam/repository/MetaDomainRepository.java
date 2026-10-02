package com.dam.repository;

import com.dam.domain.MetaDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MetaDomainRepository extends JpaRepository<MetaDomain, Long> {

    Optional<MetaDomain> findByCode(String code);
}
