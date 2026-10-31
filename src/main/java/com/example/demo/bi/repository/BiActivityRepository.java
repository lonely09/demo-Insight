package com.example.demo.bi.repository;

import com.example.demo.bi.domain.BiActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 活动仓库 */
public interface BiActivityRepository extends JpaRepository<BiActivity, Long>, JpaSpecificationExecutor<BiActivity> {
}
