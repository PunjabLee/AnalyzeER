package com.dam.repository;

import com.dam.domain.DictStandardField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DictStandardFieldRepository extends JpaRepository<DictStandardField, Long> {

    List<DictStandardField> findByEnabledTrueOrderByFieldNameAsc();

    List<DictStandardField> findAllByOrderByFieldNameAsc();
}
