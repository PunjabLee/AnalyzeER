package com.dam.repository;

import com.dam.domain.GlossaryTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlossaryTermRepository extends JpaRepository<GlossaryTerm, Long> {

    Optional<GlossaryTerm> findByName(String name);

    List<GlossaryTerm> findAllByOrderByNameAsc();
}
