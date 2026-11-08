package com.insightserver.bi.web;

import com.insightserver.bi.domain.BiChannel;
import com.insightserver.bi.repository.BiChannelRepository;
import com.insightserver.bi.repository.BiSalesRecordRepository;
import com.insightserver.bi.support.BiServiceException;
import com.insightserver.bi.web.dto.ChannelReq;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

/** 渠道增删改查 API */
@RestController
@RequestMapping("/api/bi/channels")
public class BiChannelController {

    private final BiChannelRepository channelRepository;
    private final BiSalesRecordRepository salesRepository;

    public BiChannelController(BiChannelRepository channelRepository,
                               BiSalesRecordRepository salesRepository) {
        this.channelRepository = channelRepository;
        this.salesRepository = salesRepository;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String status) {
        Specification<BiChannel> spec = (root, query, cb) ->
                (status == null || status.isBlank()) ? null : cb.equal(root.get("status"), status);
        Page<BiChannel> result = channelRepository.findAll(spec,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.ASC, "id")));
        return pageResult(result);
    }

    @GetMapping("/{id}")
    public BiChannel get(@PathVariable Long id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("渠道不存在: id=" + id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody ChannelReq req) {
        if (channelRepository.existsByCode(req.code())) {
            throw new BiServiceException("渠道编码已存在: " + req.code());
        }
        BiChannel channel = new BiChannel();
        apply(channel, req);
        BiChannel saved = channelRepository.save(channel);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", saved.getName());
        resp.put("code", saved.getCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody ChannelReq req) {
        BiChannel channel = channelRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("渠道不存在: id=" + id));
        if (req.code() != null && !req.code().isBlank()) {
            channelRepository.findByCode(req.code())
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> { throw new BiServiceException("渠道编码已存在: " + req.code()); });
        }
        apply(channel, req);
        BiChannel saved = channelRepository.save(channel);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", saved.getName());
        resp.put("code", saved.getCode());
        resp.put("type", saved.getType());
        resp.put("status", saved.getStatus());
        return resp;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        channelRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("渠道不存在: id=" + id));
        if (salesRepository.existsByChannelId(id)) {
            throw new BiServiceException("该渠道已有销售流水，无法删除，可改为 DISABLED 停用");
        }
        channelRepository.deleteById(id);
        return Map.of("deleted", id);
    }

    private void apply(BiChannel channel, ChannelReq req) {
        if (req.name() != null && !req.name().isBlank()) {
            channel.setName(req.name());
        }
        if (req.code() != null && !req.code().isBlank()) {
            channel.setCode(req.code());
        }
        if (req.type() != null && !req.type().isBlank()) {
            channel.setType(req.type());
        }
    }

    private Map<String, Object> pageResult(Page<BiChannel> result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("pages", result.getTotalPages());
        resp.put("items", result.getContent());
        return resp;
    }
}
