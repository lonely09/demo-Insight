package com.example.demo.bi.web;

import com.example.demo.bi.domain.BiProduct;
import com.example.demo.bi.repository.BiProductRepository;
import com.example.demo.bi.repository.BiSalesRecordRepository;
import com.example.demo.bi.support.BiServiceException;
import com.example.demo.bi.web.dto.ProductReq;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 商品增删改查 API */
@RestController
@RequestMapping("/api/bi/products")
public class BiProductController {

    private final BiProductRepository productRepository;
    private final BiSalesRecordRepository salesRepository;

    public BiProductController(BiProductRepository productRepository,
                               BiSalesRecordRepository salesRepository) {
        this.productRepository = productRepository;
        this.salesRepository = salesRepository;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String status) {
        Specification<BiProduct> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (category != null && !category.isBlank()) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Page<BiProduct> result = productRepository.findAll(spec,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.ASC, "id")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("pages", result.getTotalPages());
        resp.put("items", result.getContent());
        return resp;
    }

    @GetMapping("/{id}")
    public BiProduct get(@PathVariable Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("商品不存在: id=" + id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody ProductReq req) {
        if (productRepository.existsBySku(req.sku())) {
            throw new BiServiceException("商品 SKU 已存在: " + req.sku());
        }
        BiProduct product = new BiProduct();
        apply(product, req);
        BiProduct saved = productRepository.save(product);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", saved.getName());
        resp.put("sku", saved.getSku());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody ProductReq req) {
        BiProduct product = productRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("商品不存在: id=" + id));
        if (req.sku() != null && !req.sku().isBlank()) {
            productRepository.findBySku(req.sku())
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> { throw new BiServiceException("商品 SKU 已存在: " + req.sku()); });
        }
        apply(product, req);
        BiProduct saved = productRepository.save(product);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", saved.getName());
        resp.put("sku", saved.getSku());
        resp.put("category", saved.getCategory());
        resp.put("price", saved.getPrice());
        resp.put("status", saved.getStatus());
        return resp;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        productRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("商品不存在: id=" + id));
        if (salesRepository.existsByProductId(id)) {
            throw new BiServiceException("该商品存在销售流水，无法删除，可改为 OFF_SHELF 下架");
        }
        productRepository.deleteById(id);
        return Map.of("deleted", id);
    }

    private void apply(BiProduct product, ProductReq req) {
        if (req.name() != null && !req.name().isBlank()) {
            product.setName(req.name());
        }
        if (req.sku() != null && !req.sku().isBlank()) {
            product.setSku(req.sku());
        }
        if (req.category() != null && !req.category().isBlank()) {
            product.setCategory(req.category());
        }
        if (req.price() != null) {
            product.setPrice(req.price());
        }
        if (req.status() != null && !req.status().isBlank()) {
            product.setStatus(req.status());
        }
    }

    private Map<String, Object> pageResult(Page<BiProduct> result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("pages", result.getTotalPages());
        resp.put("items", result.getContent());
        return resp;
    }
}
