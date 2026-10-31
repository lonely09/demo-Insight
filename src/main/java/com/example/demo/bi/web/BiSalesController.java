package com.example.demo.bi.web;

import com.example.demo.bi.domain.BiActivity;
import com.example.demo.bi.domain.BiChannel;
import com.example.demo.bi.domain.BiProduct;
import com.example.demo.bi.domain.BiRegion;
import com.example.demo.bi.domain.BiSalesRecord;
import com.example.demo.bi.repository.BiActivityRepository;
import com.example.demo.bi.repository.BiChannelRepository;
import com.example.demo.bi.repository.BiProductRepository;
import com.example.demo.bi.repository.BiRegionRepository;
import com.example.demo.bi.repository.BiSalesRecordRepository;
import com.example.demo.bi.service.BiSummaryItem;
import com.example.demo.bi.service.BiDimensionService;
import com.example.demo.bi.support.BiServiceException;
import com.example.demo.bi.web.dto.SalesReq;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 销售流水查询/新增/删除 API：对话式数据统计 Agent 底层取数接口 */
@RestController
@RequestMapping("/api/bi/sales")
public class BiSalesController {

    private final BiSalesRecordRepository salesRepository;
    private final BiChannelRepository channelRepository;
    private final BiProductRepository productRepository;
    private final BiRegionRepository regionRepository;
    private final BiActivityRepository activityRepository;
    private final BiDimensionService dimensionService;

    public BiSalesController(BiSalesRecordRepository salesRepository,
                             BiChannelRepository channelRepository,
                             BiProductRepository productRepository,
                             BiRegionRepository regionRepository,
                             BiActivityRepository activityRepository,
                             BiDimensionService dimensionService) {
        this.salesRepository = salesRepository;
        this.channelRepository = channelRepository;
        this.productRepository = productRepository;
        this.regionRepository = regionRepository;
        this.activityRepository = activityRepository;
        this.dimensionService = dimensionService;
    }

    /** 分页查询销售流水，支持按日期区间与四个维度 ID 过滤 */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long activityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Specification<BiSalesRecord> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (channelId != null) {
                ps.add(cb.equal(root.get("channelId"), channelId));
            }
            if (productId != null) {
                ps.add(cb.equal(root.get("productId"), productId));
            }
            if (regionId != null) {
                ps.add(cb.equal(root.get("regionId"), regionId));
            }
            if (activityId != null) {
                ps.add(cb.equal(root.get("activityId"), activityId));
            }
            if (dateFrom != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("statDate"), dateFrom));
            }
            if (dateTo != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("statDate"), dateTo));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };

        Page<BiSalesRecord> result = salesRepository.findAll(spec,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "statDate")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("pages", result.getTotalPages());
        resp.put("items", result.getContent());
        return resp;
    }

    /** 按维度（channel/product/region）汇总区间内销量与成交额 */
    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(defaultValue = "channel") String group,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {

        LocalDate from = dateFrom != null ? dateFrom : LocalDate.now().minusDays(30);
        LocalDate to = dateTo != null ? dateTo : LocalDate.now();
        if (to.isBefore(from)) {
            throw new BiServiceException("结束日期不能早于开始日期");
        }

        List<BiSummaryItem> items = dimensionService.summarize(group, from, to);
        Object[] totals = salesRepository.sumTotal(from, to).get(0);
        long totalQuantity = ((Number) totals[0]).longValue();
        java.math.BigDecimal totalAmount = (java.math.BigDecimal) totals[1];

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("group", group);
        resp.put("dateFrom", from);
        resp.put("dateTo", to);
        resp.put("records", salesRepository.countByStatDateBetween(from, to));
        resp.put("totalQuantity", totalQuantity);
        resp.put("totalAmount", totalAmount);
        resp.put("items", items);
        return resp;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody SalesReq req) {
        dimensionService.requireChannel(req.channelId());
        dimensionService.requireProduct(req.productId());
        dimensionService.requireRegion(req.regionId());
        if (req.activityId() != null) {
            dimensionService.requireActivity(req.activityId());
        }
        if (req.statDate() == null) {
            throw new BiServiceException("销售日期不能为空");
        }
        if (req.quantity() == null || req.quantity() < 1) {
            throw new BiServiceException("销量至少为 1");
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BiServiceException("成交额必须大于 0");
        }
        BiSalesRecord record = new BiSalesRecord();
        record.setStatDate(req.statDate());
        record.setChannelId(req.channelId());
        record.setProductId(req.productId());
        record.setRegionId(req.regionId());
        record.setActivityId(req.activityId());
        record.setQuantity(req.quantity());
        record.setAmount(req.amount());
        BiSalesRecord saved = salesRepository.save(record);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("statDate", saved.getStatDate());
        resp.put("quantity", saved.getQuantity());
        resp.put("amount", saved.getAmount());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        if (!salesRepository.existsById(id)) {
            throw new BiServiceException("销售流水不存在: id=" + id);
        }
        salesRepository.deleteById(id);
        return Map.of("deleted", id);
    }

    // ================= 名称补全（供汇总结果/前端展示） =================

    private String channelName(Long id) {
        return channelRepository.findById(id).map(BiChannel::getName).orElse("未知渠道");
    }

    private String productName(Long productId) {
        return productRepository.findById(productId).map(BiProduct::getName).orElse("未知商品");
    }

    private String regionName(Long regionId) {
        return regionRepository.findById(regionId).map(BiRegion::getName).orElse("未知区域");
    }

    private String activityName(Long activityId) {
        return activityRepository.findById(activityId).map(BiActivity::getName).orElse("未知活动");
    }
}
