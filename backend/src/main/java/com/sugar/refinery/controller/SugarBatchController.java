package com.sugar.refinery.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sugar.refinery.common.ApiResponse;
import com.sugar.refinery.entity.SugarBatch;
import com.sugar.refinery.service.SugarBatchService;

/** 成品糖批次接口 */
@RestController
@RequestMapping("/api/sugar-batches")
public class SugarBatchController {

    private final SugarBatchService batchService;

    public SugarBatchController(SugarBatchService batchService) {
        this.batchService = batchService;
    }

    @GetMapping
    public ApiResponse<List<SugarBatch>> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String grade,
                                              @RequestParam(required = false) String warehouse) {
        return ApiResponse.success(batchService.list(status, grade, warehouse));
    }

    @GetMapping("/{id}")
    public ApiResponse<SugarBatch> get(@PathVariable Long id) {
        return ApiResponse.success(batchService.get(id));
    }

    @PostMapping
    public ApiResponse<SugarBatch> create(@RequestBody SugarBatch input) {
        return ApiResponse.success("批次已建立", batchService.create(input));
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
