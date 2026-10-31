package com.example.demo.bi.repository;

import com.example.demo.bi.domain.BiProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 商品仓库 */
public interface BiProductRepository extends JpaRepository<BiProduct, Long>, JpaSpecificationExecutor<BiProduct> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    java.util.Optional<BiProduct> findBySku(String sku);
}
