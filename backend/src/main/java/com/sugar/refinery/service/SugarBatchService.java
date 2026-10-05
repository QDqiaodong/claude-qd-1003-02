package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.entity.SugarBatch;
import com.sugar.refinery.repository.SugarBatchRepository;

/**
 * 成品糖批次业务。
 *
 * <p>核心规则：① 品类按纯度自动判定（纯度 ≥ 99.5 才能判白砂糖）；
 * ② 单批数量不得超过 {@link Dict#MAX_BATCH_KG}；③ 只有「待检」的批能入库；
 * ④ 状态逐级推进：待检 → 已入库 → 已发运。</p>
 */
@Service
public class SugarBatchService {

    private final SugarBatchRepository batchRepository;

    public SugarBatchService(SugarBatchRepository batchRepository) {
        this.batchRepository = batchRepository;
    }

    public List<SugarBatch> list(String status, String grade, String warehouse) {
        if (status != null && !status.isBlank()) {
            return batchRepository.findByStatus(status);
        }
        if (grade != null && !grade.isBlank()) {
            return batchRepository.findByGrade(grade);
        }
        if (warehouse != null && !warehouse.isBlank()) {
            return batchRepository.findByWarehouse(warehouse);
        }
        return batchRepository.findAll();
    }

    public SugarBatch get(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new BizException("成品糖批次不存在：" + id));
    }

    @Transactional
    public SugarBatch create(SugarBatch input) {
        if (input.getBatchNo() == null || input.getBatchNo().isBlank()) {
            throw new BizException("批号不能为空");
        }
        batchRepository.findByBatchNo(input.getBatchNo().trim()).ifPresent(b -> {
            throw new BizException("批号已存在：" + input.getBatchNo());
        });
        if (input.getQuantityKg() == null || input.getQuantityKg().signum() <= 0) {
            throw new BizException("数量必须大于 0");
        }
        // 规则②：单批上限
        if (input.getQuantityKg().compareTo(Dict.MAX_BATCH_KG) > 0) {
            throw new BizException("单批 " + input.getQuantityKg() + " 千克超过上限 "
                    + Dict.MAX_BATCH_KG + " 千克，需拆分");
        }
        if (!Dict.isValidWarehouse(input.getWarehouse())) {
            throw new BizException("糖仓不合法：" + input.getWarehouse());
        }
        if (input.getQuality() == null) {
            throw new BizException("必须录入品质三指标（糖度 / 纯度 / 纤维分）");
        }

        SugarBatch b = new SugarBatch();
        b.setBatchNo(input.getBatchNo().trim());
        b.setQuantityKg(input.getQuantityKg());
        b.setQuality(input.getQuality());
        b.setWarehouse(input.getWarehouse());
        // 规则①：按纯度自动判品类
        JuiceQuality q = input.getQuality();
        String auto = Dict.decideGrade(q.getPurity());
        if (input.getGrade() != null && !input.getGrade().isBlank()
                && !Dict.isValidSugarGrade(input.getGrade())) {
            throw new BizException("品类不合法：" + input.getGrade());
        }
        // 纯度不够却硬判白砂糖，直接拦下
        if ("白砂糖".equals(input.getGrade()) && !"白砂糖".equals(auto)) {
            throw new BizException("纯度 " + q.getPurity() + "% 未达 " + Dict.MIN_WHITE_PURITY
                    + "%，不能判为白砂糖，应为 " + auto);
        }
        b.setGrade(input.getGrade() == null || input.getGrade().isBlank() ? auto : input.getGrade());
        b.setStatus("待检");
        b.setProducedAt(LocalDateTime.now());
        return batchRepository.save(b);
    }

    /** 入库：待检 → 已入库 */
    @Transactional
    public SugarBatch store(Long id) {
        SugarBatch b = get(id);
        if (!"待检".equals(b.getStatus())) {
            throw new BizException("只有「待检」的批次能入库，当前状态：" + b.getStatus());
        }
        b.setStatus("已入库");
        return batchRepository.save(b);
    }

    /** 发运：已入库 → 已发运 */
    @Transactional
    public SugarBatch ship(Long id) {
        SugarBatch b = get(id);
        if (!"已入库".equals(b.getStatus())) {
            throw new BizException("只有「已入库」的批次能发运，当前状态：" + b.getStatus());
        }
        b.setStatus("已发运");
        return batchRepository.save(b);
    }

    /** 按糖仓汇总库存（吨） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> stockByWarehouse() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String w : Dict.WAREHOUSES) {
            BigDecimal kg = BigDecimal.ZERO;
            int n = 0;
            for (SugarBatch b : batchRepository.findByWarehouse(w)) {
                if ("已发运".equals(b.getStatus())) {
                    continue;
                }
                kg = kg.add(b.getQuantityKg());
                n++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("warehouse", w);
            row.put("batches", n);
            row.put("quantityTon", kg.divide(BigDecimal.valueOf(1000), 2, java.math.RoundingMode.HALF_UP));
            out.add(row);
        }
        return out;
    }
}
