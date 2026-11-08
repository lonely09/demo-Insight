package com.insightserver.bi.web;

import com.insightserver.bi.domain.BiRegion;
import com.insightserver.bi.repository.BiRegionRepository;
import com.insightserver.bi.repository.BiSalesRecordRepository;
import com.insightserver.bi.support.BiServiceException;
import com.insightserver.bi.web.dto.RegionReq;
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

/** 区域增删改查 API */
@RestController
@RequestMapping("/api/bi/regions")
public class BiRegionController {

    private final BiRegionRepository regionRepository;
    private final BiSalesRecordRepository salesRepository;

    public BiRegionController(BiRegionRepository regionRepository,
                              BiSalesRecordRepository salesRepository) {
        this.regionRepository = regionRepository;
        this.salesRepository = salesRepository;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) Integer level,
                                    @RequestParam(required = false) Long parentId,
                                    @RequestParam(required = false) String status) {
        Specification<BiRegion> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (level != null) {
                predicates.add(cb.equal(root.get("level"), level));
            }
            if (parentId != null) {
                predicates.add(cb.equal(root.get("parentId"), parentId));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Page<BiRegion> result = regionRepository.findAll(spec,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.ASC, "id")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("pages", result.getTotalPages());
        resp.put("items", result.getContent());
        return resp;
    }

    @GetMapping("/{id}")
    public BiRegion get(@PathVariable Long id) {
        return regionRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("区域不存在: id=" + id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody RegionReq req) {
        if (regionRepository.existsByCode(req.code())) {
            throw new BiServiceException("区域编码已存在: " + req.code());
        }
        BiRegion region = new BiRegion();
        apply(region, req);
        BiRegion saved = regionRepository.save(region);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", saved.getName());
        resp.put("code", saved.getCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody RegionReq req) {
        BiRegion region = regionRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("区域不存在: id=" + id));
        if (req.code() != null && !req.code().isBlank()) {
            regionRepository.findByCode(req.code())
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> { throw new BiServiceException("区域编码已存在: " + req.code()); });
        }
        if (req.parentId() != null && req.parentId().equals(id)) {
            throw new BiServiceException("区域不能以自己作为上级");
        }
        if (req.parentId() != null) {
            BiRegion parent = regionRepository.findById(req.parentId())
                    .orElseThrow(() -> new BiServiceException("上级区域不存在: id=" + req.parentId()));
            if (parent.getLevel() >= 3) {
                throw new BiServiceException("上级区域已是最末级，无法再挂子区域");
            }
        }
        apply(region, req);
        BiRegion saved = regionRepository.save(region);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", saved.getName());
        resp.put("code", saved.getCode());
        resp.put("level", saved.getLevel());
        resp.put("parentId", saved.getParentId());
        resp.put("status", saved.getStatus());
        return resp;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        regionRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("区域不存在: id=" + id));
        if (regionRepository.existsByParentId(id)) {
            throw new BiServiceException("该区域下存在子区域，请先删除子区域");
        }
        if (salesRepository.existsByRegionId(id)) {
            throw new BiServiceException("该区域存在销售流水，无法删除，可改为 DISABLED 停用");
        }
        regionRepository.deleteById(id);
        return Map.of("deleted", id);
    }

    private void apply(BiRegion region, RegionReq req) {
        if (req.name() != null && !req.name().isBlank()) {
            region.setName(req.name());
        }
        if (req.code() != null && !req.code().isBlank()) {
            region.setCode(req.code());
        }
        Long parentId = req.parentId();
        Integer level = req.level();
        if (req.parentId() != null) {
            BiRegion parent = regionRepository.findById(req.parentId())
                    .orElseThrow(() -> new BiServiceException("上级区域不存在: id=" + req.parentId()));
            level = parent.getLevel() + 1;
            parentId = parent.getId();
        } else if (level == null) {
            level = 1;
        }
        region.setLevel(level);
        region.setParentId(parentId);
        if (req.status() != null && !req.status().isBlank()) {
            region.setStatus(req.status());
        }
    }

    private Map<String, Object> pageResult(Page<BiRegion> result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("pages", result.getTotalPages());
        resp.put("items", result.getContent());
        return resp;
    }
}
