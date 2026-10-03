package com.dam.repository;

import com.dam.domain.MetaVersionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetaVersionItemRepository extends JpaRepository<MetaVersionItem, Long> {

    List<MetaVersionItem> findByVersionId(Long versionId);

    List<MetaVersionItem> findByVersionIdAndChangeType(Long versionId, String changeType);
}
