package com.insightserver.bi.repository;

import com.insightserver.bi.domain.BiRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 区域仓库 */
public interface BiRegionRepository extends JpaRepository<BiRegion, Long>, JpaSpecificationExecutor<BiRegion> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    boolean existsByParentId(Long parentId);

    java.util.Optional<BiRegion> findByCode(String code);
}
