package com.dam.repository;

import com.dam.domain.MetaVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MetaVersionRepository extends JpaRepository<MetaVersion, Long> {

    List<MetaVersion> findAllByOrderByVersionNoDesc();

    Optional<MetaVersion> findTopByOrderByVersionNoDesc();

    Optional<MetaVersion> findByVersionNo(Integer versionNo);
}
