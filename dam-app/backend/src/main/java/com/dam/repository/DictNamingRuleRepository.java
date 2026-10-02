package com.dam.repository;

import com.dam.domain.DictNamingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DictNamingRuleRepository extends JpaRepository<DictNamingRule, Long> {

    List<DictNamingRule> findByEnabledTrueAndTarget(String target);

    List<DictNamingRule> findAllByOrderByNameAsc();
}
