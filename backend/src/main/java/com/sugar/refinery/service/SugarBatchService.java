package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.entity.SugarBatch;
import com.sugar.refinery.entity.SugarReinspection;
import com.sugar.refinery.repository.SugarBatchRepository;
import com.sugar.refinery.repository.SugarReinspectionRepository;

/**
 * 成品糖批次业务（质量复检—发运锁定）。
 *
 * <p>核心规则：① 品类按纯度自动判定（纯度 ≥ 99.5 才能判白砂糖）；
 * ② 单批数量不得超过 {@link Dict#MAX_BATCH_KG}；③ 新建批次先进入「待复检」，
 * 值班员按批号逐版录入复检时间 / 糖度 / 纯度 / 纤维分 / 结论，复检只追加不改写，
 * 版本号批号内递增，{@code request_id} 幂等键 + {@code (batch_id, version)} 唯一索引
 * 保证重复点击 / 请求重试只落一份有效记录；④ 已入库 / 已发运的批次不可回写旧结果，
 * 重新复检必须以「当前版本 + 1」落新版本并保留历史；⑤ 最新一版复检通过才允许入库，
 * 入库后最新一版仍为通过才允许发运——复检不通过即锁定发运资格。</p>
 *
 * <p>所有写路径都是「批次行锁 + 单事务」：校验失败整体回滚，不会只落下复检记录
 * 或只改批次状态，任何失败都可安全重试。</p>
 */
@Service
public class SugarBatchService {

    /** 所有写路径都是单事务，失败即整体回滚，因此任何失败都可安全重试 */
    private static final String SAFE_RETRY = "；本次未落下任何数据，可安全重试";

    private final SugarBatchRepository batchRepository;
    private final SugarReinspectionRepository reviewRepository;

    public SugarBatchService(SugarBatchRepository batchRepository,
                             SugarReinspectionRepository reviewRepository) {
        this.batchRepository = batchRepository;
        this.reviewRepository = reviewRepository;
    }

    public List<SugarBatch> list(String status, String grade, String warehouse, String reviewStatus) {
        if (reviewStatus != null && !reviewStatus.isBlank() && !Dict.isValidReviewStatus(reviewStatus)) {
            throw new BizException("复检状态不合法：" + reviewStatus);
        }
        List<SugarBatch> rows = new ArrayList<>();
        for (SugarBatch b : batchRepository.findAll()) {
            if (status != null && !status.isBlank() && !status.equals(b.getStatus())) {
                continue;
            }
            if (grade != null && !grade.isBlank() && !grade.equals(b.getGrade())) {
                continue;
            }
            if (warehouse != null && !warehouse.isBlank() && !warehouse.equals(b.getWarehouse())) {
                continue;
            }
            rows.add(b);
        }
        attachReviews(rows);
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            rows.removeIf(b -> !matchReviewBucket(reviewStatus, b));
        }
        return rows;
    }

    /**
     * 复检状态筛选口径：「待复检」是工作态桶——从未复检或最新一版不通过（等待重新复检）
     * 都算；「通过」「不通过」按最新一版结论精确匹配。
     */
    private static boolean matchReviewBucket(String filter, SugarBatch b) {
        if ("待复检".equals(filter)) {
            return b.getReviewResult() == null || "不通过".equals(b.getReviewResult());
        }
        return filter.equals(b.getReviewStatus());
    }

    public SugarBatch get(Long id) {
        SugarBatch b = batchRepository.findById(id)
                .orElseThrow(() -> new BizException("成品糖批次不存在：" + id));
        return attachReview(b);
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
        // 规则③：新建批次先进入「待复检」，复检通过后才能入库 / 发运
        b.setStatus("待复检");
        b.setProducedAt(LocalDateTime.now());
        return attachReview(batchRepository.save(b));
    }

    // ------------------------------------------------------------------
    // 质量复检
    // ------------------------------------------------------------------

    /** 某批号的复检历史（版本倒序，最新一版带 latest 标记） */
    @Transactional(readOnly = true)
    public List<SugarReinspection> history(Long batchId) {
        SugarBatch b = batchRepository.findById(batchId)
                .orElseThrow(() -> new BizException("成品糖批次不存在：" + batchId));
        List<SugarReinspection> rows = reviewRepository.findByBatchIdOrderByVersionDesc(b.getId());
        if (!rows.isEmpty()) {
            rows.get(0).setLatest(true);
        }
        return rows;
    }

    /** 控制器幂等兜底用：按 requestId 找回已落下的复检记录 */
    @Transactional(readOnly = true)
    public Optional<SugarReinspection> findByRequestId(String requestId) {
        return reviewRepository.findByRequestId(requestId);
    }

    /**
     * 录入复检：锁批次行 → 校验版本与录入内容 → 只追加新版本，同一事务。
     *
     * <p>{@code requestId} 是幂等键：重复点击 / 超时重试带同一键，直接返回已落下的那版；
     * 版本必须严格等于「当前版本 + 1」，已入库 / 已发运的批次不能回写旧版本，
     * 重新复检只能落新版本，历史逐版保留。</p>
     */
    @Transactional
    public SugarReinspection reinspect(Long batchId, Integer version, LocalDateTime inspectTime,
                                       JuiceQuality quality, String result, String reason,
                                       String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException("缺少请求标识，请刷新页面后重试");
        }
        // 幂等：同一 requestId 只落一版，重复点击 / 超时重试直接返回已落下的记录
        Optional<SugarReinspection> dup = reviewRepository.findByRequestId(requestId.trim());
        if (dup.isPresent()) {
            SugarReinspection existed = dup.get();
            if (!sameIntent(existed, batchId, version, inspectTime, quality, result, reason)) {
                throw new BizException("该复检请求已受理过（批号 " + existed.getBatchNo()
                        + " 第 " + existed.getVersion() + " 版），但本次内容不一致，请刷新页面核对后再操作");
            }
            existed.setLatest(existed.getVersion() == reviewRepository.maxVersionByBatchId(existed.getBatchId()));
            existed.setIdempotentReplay(true);
            return existed;
        }

        // 锁批次行：同一批的并发复检在此串行，后到者按最新版本号校验
        SugarBatch b = batchRepository.lockById(batchId)
                .orElseThrow(() -> new BizException("成品糖批次不存在：" + batchId + SAFE_RETRY));

        if (version == null) {
            throw new BizException("缺少复检版本号，请刷新页面后重试" + SAFE_RETRY);
        }
        if (inspectTime == null) {
            throw new BizException("请录入复检时间" + SAFE_RETRY);
        }
        if (quality == null || quality.getBrix() == null || quality.getPurity() == null
                || quality.getFiber() == null) {
            throw new BizException("请完整录入复检三指标（糖度 / 纯度 / 纤维分）" + SAFE_RETRY);
        }
        if (!inRange(quality.getBrix()) || !inRange(quality.getPurity()) || !inRange(quality.getFiber())) {
            throw new BizException("糖度 / 纯度 / 纤维分必须在 0~100 之间" + SAFE_RETRY);
        }
        if (!Dict.isValidReinspectResult(result)) {
            throw new BizException("复检结论不合法：" + result + SAFE_RETRY);
        }
        String normalizedReason = reason == null ? null : reason.trim();
        if ("不通过".equals(result) && (normalizedReason == null || normalizedReason.isEmpty())) {
            throw new BizException("复检不通过必须填写不通过原因" + SAFE_RETRY);
        }
        if ("通过".equals(result)) {
            // 通过结论不带原因，避免页面把历史备注误显示成锁定原因
            normalizedReason = null;
        }

        int current = reviewRepository.maxVersionByBatchId(b.getId());
        if (version != current + 1) {
            // 规则④：旧版本不可回写；跳版本也不允许，杜绝并发下的版本空洞
            throw new BizException("批号 " + b.getBatchNo() + " 当前复检版本为第 " + current
                    + " 版，本次提交的是第 " + version + " 版；已入库 / 已发运的批次不可回写旧结果，"
                    + "重新复检请提交第 " + (current + 1) + " 版" + SAFE_RETRY);
        }

        SugarReinspection r = new SugarReinspection();
        r.setBatchId(b.getId());
        r.setBatchNo(b.getBatchNo());
        r.setVersion(current + 1);
        r.setRequestId(requestId.trim());
        r.setInspectTime(inspectTime);
        r.setQuality(quality);
        r.setResult(result);
        r.setReason(normalizedReason);
        r.setCreatedAt(LocalDateTime.now());
        // saveAndFlush：让唯一约束冲突（并发同版本 / 同 requestId）在事务内抛出并整体回滚，
        // 由控制器兜底返回已落下的那版，保证同一复检意图只保留一份有效记录
        SugarReinspection saved = reviewRepository.saveAndFlush(r);
        saved.setLatest(true);
        return saved;
    }

    /** 幂等重放内容一致性比对（时间按秒比对，避开数据库秒级精度截断） */
    private boolean sameIntent(SugarReinspection existed, Long batchId, Integer version,
                               LocalDateTime inspectTime, JuiceQuality quality, String result,
                               String reason) {
        if (!existed.getBatchId().equals(batchId) || !existed.getVersion().equals(version)
                || !existed.getResult().equals(result)) {
            return false;
        }
        String replayReason = "通过".equals(result) ? null : (reason == null ? null : reason.trim());
        boolean reasonSame = existed.getReason() == null ? replayReason == null
                : existed.getReason().equals(replayReason);
        boolean timeSame = existed.getInspectTime().truncatedTo(ChronoUnit.SECONDS)
                .equals(inspectTime.truncatedTo(ChronoUnit.SECONDS));
        JuiceQuality eq = existed.getQuality();
        return reasonSame && timeSame && eq != null && quality != null
                && eq.getBrix().compareTo(quality.getBrix()) == 0
                && eq.getPurity().compareTo(quality.getPurity()) == 0
                && eq.getFiber().compareTo(quality.getFiber()) == 0;
    }

    private static boolean inRange(BigDecimal v) {
        return v.signum() >= 0 && v.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    // ------------------------------------------------------------------
    // 入库 / 发运（发运锁定）
    // ------------------------------------------------------------------

    /** 入库：待复检 → 已入库，前提是最新一版复检通过 */
    @Transactional
    public SugarBatch store(Long id) {
        SugarBatch b = lockBatch(id);
        if (!"待复检".equals(b.getStatus())) {
            throw new BizException("只有「待复检」的批次能入库，当前状态：" + b.getStatus() + SAFE_RETRY);
        }
        requireReviewPassed(b, "入库");
        b.setStatus("已入库");
        return attachReview(batchRepository.save(b));
    }

    /** 发运：已入库 → 已发运，前提是最新一版复检仍为通过（复检不通过即锁发运） */
    @Transactional
    public SugarBatch ship(Long id) {
        SugarBatch b = lockBatch(id);
        if (!"已入库".equals(b.getStatus())) {
            throw new BizException("只有「已入库」的批次能发运，当前状态：" + b.getStatus() + SAFE_RETRY);
        }
        requireReviewPassed(b, "发运");
        b.setStatus("已发运");
        return attachReview(batchRepository.save(b));
    }

    private SugarBatch lockBatch(Long id) {
        return batchRepository.lockById(id)
                .orElseThrow(() -> new BizException("成品糖批次不存在：" + id + SAFE_RETRY));
    }

    /** 入库 / 发运的共同前提：最新一版复检必须是「通过」 */
    private void requireReviewPassed(SugarBatch b, String action) {
        SugarReinspection latest = reviewRepository.findFirstByBatchIdOrderByVersionDesc(b.getId())
                .orElse(null);
        if (latest == null) {
            throw new BizException("批号 " + b.getBatchNo() + " 尚未复检，复检通过后才能"
                    + action + SAFE_RETRY);
        }
        if (!"通过".equals(latest.getResult())) {
            throw new BizException("批号 " + b.getBatchNo() + " 最新复检（第 " + latest.getVersion()
                    + " 版）结论为不通过" + (latest.getReason() == null ? "" : "：" + latest.getReason())
                    + "，不能" + action + "；请重新复检并通过后再操作" + SAFE_RETRY);
        }
    }

    // ------------------------------------------------------------------
    // 复检视图回填 + 糖仓汇总
    // ------------------------------------------------------------------

    private SugarBatch attachReview(SugarBatch b) {
        attachReviews(List.of(b));
        return b;
    }

    /** 批量回填：当前复检版本 / 结论 / 复检状态 / 最近复检时间 / 发运资格，一次查询完成 */
    private void attachReviews(Collection<SugarBatch> batches) {
        if (batches.isEmpty()) {
            return;
        }
        List<Long> ids = batches.stream().map(SugarBatch::getId).toList();
        Map<Long, SugarReinspection> latest = new HashMap<>();
        for (SugarReinspection r : reviewRepository.findByBatchIdInOrderByVersionDesc(ids)) {
            latest.putIfAbsent(r.getBatchId(), r);   // 版本倒序，第一个即最新一版
        }
        for (SugarBatch b : batches) {
            SugarReinspection r = latest.get(b.getId());
            if (r == null) {
                b.setReviewVersion(null);
                b.setReviewResult(null);
                b.setReviewStatus("待复检");
                b.setLastInspectTime(null);
                b.setLastRejectReason(null);
                b.setShippable(false);
                b.setShipLockReason("尚未复检，复检通过后才能入库 / 发运");
                continue;
            }
            b.setReviewVersion(r.getVersion());
            b.setReviewResult(r.getResult());
            b.setReviewStatus(r.getResult());
            b.setLastInspectTime(r.getInspectTime());
            boolean passed = "通过".equals(r.getResult());
            b.setLastRejectReason(passed ? null : r.getReason());
            if ("已发运".equals(b.getStatus())) {
                b.setShippable(false);
                b.setShipLockReason("该批已发运");
            } else if (!passed) {
                b.setShippable(false);
                b.setShipLockReason("最新复检（第 " + r.getVersion() + " 版）不通过"
                        + (r.getReason() == null ? "" : "：" + r.getReason())
                        + "，已锁定入库 / 发运，需重新复检通过");
            } else if ("待复检".equals(b.getStatus())) {
                b.setShippable(false);
                b.setShipLockReason("复检已通过，请先办理入库");
            } else {
                b.setShippable(true);
                b.setShipLockReason(null);
            }
        }
    }

    /**
     * 按糖仓汇总，三口径区分：
     * 待复检（未复检或最新不通过、尚未发运）/ 可发运（最新通过、在仓未发运）/ 已发运。
     * 另给出在仓合计（待复检 + 可发运），保持库存口径只数未发运批次。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> stockByWarehouse() {
        List<SugarBatch> all = batchRepository.findAll();
        attachReviews(all);

        List<Map<String, Object>> out = new ArrayList<>();
        for (String w : Dict.WAREHOUSES) {
            int waitN = 0, shipN = 0, shippedN = 0;
            BigDecimal waitKg = BigDecimal.ZERO;
            BigDecimal shipKg = BigDecimal.ZERO;
            BigDecimal shippedKg = BigDecimal.ZERO;
            for (SugarBatch b : all) {
                if (!w.equals(b.getWarehouse())) {
                    continue;
                }
                if ("已发运".equals(b.getStatus())) {
                    shippedN++;
                    shippedKg = shippedKg.add(b.getQuantityKg());
                } else if ("通过".equals(b.getReviewStatus())) {
                    shipN++;
                    shipKg = shipKg.add(b.getQuantityKg());
                } else {
                    // 未复检或最新不通过，一律计入待复检口径
                    waitN++;
                    waitKg = waitKg.add(b.getQuantityKg());
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("warehouse", w);
            row.put("waitingBatches", waitN);
            row.put("waitingTon", toTon(waitKg));
            row.put("shippableBatches", shipN);
            row.put("shippableTon", toTon(shipKg));
            row.put("shippedBatches", shippedN);
            row.put("shippedTon", toTon(shippedKg));
            // 在仓合计 = 待复检 + 可发运（已发运不计库存，与原口径一致）
            row.put("batches", waitN + shipN);
            row.put("quantityTon", toTon(waitKg.add(shipKg)));
            out.add(row);
        }
        return out;
    }

    private static BigDecimal toTon(BigDecimal kg) {
        return kg.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
    }
}
