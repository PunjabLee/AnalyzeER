package com.dam.repository;

import com.dam.domain.DictCodeValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DictCodeValueRepository extends JpaRepository<DictCodeValue, Long> {

    List<DictCodeValue> findByCategoryOrderByOrdinalAscIdAsc(String category);

    List<DictCodeValue> findAllByOrderByCategoryAscOrdinalAsc();
}
