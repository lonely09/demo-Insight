package com.example.demo.bi.repository;

import com.example.demo.bi.domain.BiChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 渠道仓库 */
public interface BiChannelRepository extends JpaRepository<BiChannel, Long>, JpaSpecificationExecutor<BiChannel> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    java.util.Optional<BiChannel> findByCode(String code);
}

