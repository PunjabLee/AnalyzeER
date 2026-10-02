package com.dam.repository;

import com.dam.domain.DqIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DqIssueRepository extends JpaRepository<DqIssue, Long> {

    List<DqIssue> findByStatus(String status);

    Page<DqIssue> findByRuleCodeAndStatusContaining(String ruleCode, String status, Pageable pageable);

    Page<DqIssue> findByRuleCode(String ruleCode, Pageable pageable);

    Page<DqIssue> findByStatus(String status, Pageable pageable);
}
