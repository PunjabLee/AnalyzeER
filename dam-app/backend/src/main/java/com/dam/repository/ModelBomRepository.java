package com.dam.repository;

import com.dam.domain.ModelBom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelBomRepository extends JpaRepository<ModelBom, Long> {

    List<ModelBom> findAllByOrderByCategoryAscNameAsc();

    List<ModelBom> findByCategoryOrderByDomainCodeAscNameAsc(String category);
}
