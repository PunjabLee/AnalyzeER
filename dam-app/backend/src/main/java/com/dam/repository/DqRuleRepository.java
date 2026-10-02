package com.dam.repository;

import com.dam.domain.DqRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DqRuleRepository extends JpaRepository<DqRule, Long> {

    Optional<DqRule> findByCode(String code);

    List<DqRule> findByEnabledTrue();
}
