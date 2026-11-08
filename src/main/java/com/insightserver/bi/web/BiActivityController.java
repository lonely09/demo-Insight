package com.insightserver.bi.web;

import com.insightserver.bi.domain.BiActivity;
import com.insightserver.bi.repository.BiActivityRepository;
import com.insightserver.bi.repository.BiSalesRecordRepository;
import com.insightserver.bi.support.BiServiceException;
import com.insightserver.bi.web.dto.ActivityReq;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 活动增删改查 API */
@RestController
@RequestMapping("/api/bi/activities")
public class BiActivityController {

    private final BiActivityRepository activityRepository;
    private final BiSalesRecordRepository salesRepository;

    public BiActivityController(BiActivityRepository activityRepository,
                                BiSalesRecordRepository salesRepository) {
        this.activityRepository = activityRepository;
        this.salesRepository = salesRepository;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String status) {
        Specification<BiActivity> spec = (root, query, cb) ->
                (status == null || status.isBlank()) ? null : cb.equal(root.get("status"), status);
        Page<BiActivity> result = activityRepository.findAll(spec,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.ASC, "id")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("pages", result.getTotalPages());
        resp.put("items", result.getContent());
        return resp;
    }

    @GetMapping("/{id}")
    public BiActivity get(@PathVariable Long id) {
        return activityRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("活动不存在: id=" + id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody ActivityReq req) {
        validateDates(req.startDate(), req.endDate());
        BiActivity activity = new BiActivity();
        apply(activity, req);
        BiActivity saved = activityRepository.save(activity);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", saved.getName());
        resp.put("type", saved.getType());
        resp.put("startDate", saved.getStartDate());
        resp.put("endDate", saved.getEndDate());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody ActivityReq req) {
        BiActivity activity = activityRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("活动不存在: id=" + id));
        LocalDate newStart = req.startDate() != null ? req.startDate() : activity.getStartDate();
        LocalDate newEnd = req.endDate() != null ? req.endDate() : activity.getEndDate();
        validateDates(newStart, newEnd);
        apply(activity, req);
        BiActivity saved = activityRepository.save(activity);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", saved.getName());
        resp.put("type", saved.getType());
        resp.put("startDate", saved.getStartDate());
        resp.put("endDate", saved.getEndDate());
        resp.put("status", saved.getStatus());
        return resp;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        activityRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("活动不存在: id=" + id));
        if (salesRepository.existsByActivityId(id)) {
            throw new BiServiceException("该活动存在销售流水，无法删除，可改为 ENDED 归档");
        }
        activityRepository.deleteById(id);
        return Map.of("deleted", id);
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BiServiceException("活动结束日期不能早于开始日期");
        }
    }

    private void apply(BiActivity activity, ActivityReq req) {
        if (req.name() != null && !req.name().isBlank()) {
            activity.setName(req.name());
        }
        if (req.type() != null && !req.type().isBlank()) {
            activity.setType(req.type());
        }
        if (req.startDate() != null) {
            activity.setStartDate(req.startDate());
        }
        if (req.endDate() != null) {
            activity.setEndDate(req.endDate());
        }
    }

    private Map<String, Object> pageResult(Page<BiActivity> result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("pages", result.getTotalPages());
        resp.put("items", result.getContent());
        return resp;
    }
}
