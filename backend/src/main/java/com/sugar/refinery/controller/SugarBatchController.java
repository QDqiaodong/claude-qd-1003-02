package com.sugar.refinery.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sugar.refinery.common.ApiResponse;
import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.entity.SugarBatch;
import com.sugar.refinery.entity.SugarReinspection;
import com.sugar.refinery.service.SugarBatchService;

/** 成品糖批次接口：质量复检—发运锁定闭环 */
@RestController
@RequestMapping("/api/sugar-batches")
public class SugarBatchController {

    private final SugarBatchService batchService;

    public SugarBatchController(SugarBatchService batchService) {
        this.batchService = batchService;
    }

    /**
     * 复检录入请求：
     * version 必须为「当前版本 + 1」；requestId 是幂等键，重复点击 / 超时重试必须带同一个。
     */
    public record ReinspectRequest(Integer version, LocalDateTime inspectTime,
                                   JuiceQuality quality, String result, String reason,
                                   String requestId) {
    }

    @GetMapping
    public ApiResponse<List<SugarBatch>> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String grade,
                                              @RequestParam(required = false) String warehouse,
                                              @RequestParam(required = false) String reviewStatus) {
        return ApiResponse.success(batchService.list(status, grade, warehouse, reviewStatus));
    }

    @GetMapping("/{id}")
    public ApiResponse<SugarBatch> get(@PathVariable Long id) {
        return ApiResponse.success(batchService.get(id));
    }

    @PostMapping
    public ApiResponse<SugarBatch> create(@RequestBody SugarBatch input) {
        return ApiResponse.success("批次已建立，等待质量复检", batchService.create(input));
    }

    @PostMapping("/{id}/reinspect")
    public ApiResponse<SugarReinspection> reinspect(@PathVariable Long id,
                                                    @RequestBody ReinspectRequest req) {
        try {
            SugarReinspection r = batchService.reinspect(id, req.version(), req.inspectTime(),
                    req.quality(), req.result(), req.reason(), req.requestId());
            // 重复点击 / 超时重试带同一 requestId：服务层已合并，不落新记录
            if (r.isIdempotentReplay()) {
                return ApiResponse.success("复检已提交（重复请求已合并）", r);
            }
            boolean passed = "通过".equals(r.getResult());
            String message = passed
                    ? "第 " + r.getVersion() + " 版复检通过，已解锁入库 / 发运资格"
                    : "第 " + r.getVersion() + " 版复检不通过，入库 / 发运已锁定";
            return ApiResponse.success(message, r);
        } catch (DataIntegrityViolationException e) {
            // 并发下同一 requestId 或同版本已被另一事务落库：本事务已整体回滚（不留半成品），
            // 直接返回已存在的那版，保证「一次复检只保留一份有效记录」
            Optional<SugarReinspection> existing = req.requestId() == null ? Optional.empty()
                    : batchService.findByRequestId(req.requestId());
            if (existing.isPresent()) {
                return ApiResponse.success("复检已提交（重复请求已合并）", existing.get());
            }
            throw e;
        }
    }

    @GetMapping("/{id}/reinspections")
    public ApiResponse<List<SugarReinspection>> history(@PathVariable Long id) {
        return ApiResponse.success(batchService.history(id));
    }

    @PostMapping("/{id}/store")
    public ApiResponse<SugarBatch> store(@PathVariable Long id) {
        return ApiResponse.success("已入库", batchService.store(id));
    }

    @PostMapping("/{id}/ship")
    public ApiResponse<SugarBatch> ship(@PathVariable Long id) {
        return ApiResponse.success("已发运", batchService.ship(id));
    }

    @GetMapping("/stock-by-warehouse")
    public ApiResponse<List<Map<String, Object>>> stockByWarehouse() {
        return ApiResponse.success(batchService.stockByWarehouse());
    }
}
