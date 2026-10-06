package com.sugar.refinery;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.entity.SugarBatch;
import com.sugar.refinery.entity.SugarReinspection;
import com.sugar.refinery.repository.SugarBatchRepository;
import com.sugar.refinery.repository.SugarReinspectionRepository;
import com.sugar.refinery.service.SugarBatchService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成品糖「质量复检—发运锁定」服务层闭环测试。
 *
 * <p>覆盖：新建批次先待复检、复检通过才解锁入库 / 发运、不通过留原因并锁发运、
 * requestId 幂等（重复提交 / 内容不一致重放）、按批号 + 版本只追加不改写、
 * 旧版本回写拦截（含已入库 / 已发运）、重新复检生成新版本保留历史、
 * 入库后复检不通过立即剥夺发运资格、非法状态推进、失败事务不留半成品、
 * 糖仓汇总三口径（待复检 / 可发运 / 已发运）、复检状态筛选、数据库唯一约束兜底。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SugarReinspectionTest {

    @Autowired private SugarBatchService batchService;
    @Autowired private SugarBatchRepository batchRepository;
    @Autowired private SugarReinspectionRepository reviewRepository;

    private static JuiceQuality q(String brix, String purity, String fiber) {
        return new JuiceQuality(new BigDecimal(brix), new BigDecimal(purity), new BigDecimal(fiber));
    }

    private SugarBatch newBatch(String no, String warehouse, String kg) {
        SugarBatch input = new SugarBatch();
        input.setBatchNo(no);
        input.setGrade("白砂糖");
        input.setQuantityKg(new BigDecimal(kg));
        input.setQuality(q("99.80", "99.60", "0.05"));
        input.setWarehouse(warehouse);
        return batchService.create(input);
    }

    private SugarReinspection inspect(SugarBatch b, int version, boolean pass, String requestId) {
        return batchService.reinspect(b.getId(), version, LocalDateTime.now().minusMinutes(30),
                q("99.85", "99.65", "0.04"),
                pass ? "通过" : "不通过",
                pass ? null : "色值偏高，需返料",
                requestId);
    }

    @BeforeEach
    void setUp() {
        reviewRepository.deleteAll();
        batchRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // 新建批次 / 复检解锁
    // ------------------------------------------------------------------

    @Test
    void new_batch_is_waiting_review_and_locked_until_passed() {
        SugarBatch b = newBatch("SB-T001", "1号糖仓", "30000");
        assertEquals("待复检", b.getStatus());
        assertEquals("待复检", b.getReviewStatus());
        assertNull(b.getReviewVersion());
        assertFalse(b.isShippable());

        // 未复检不能入库
        assertThrows(BizException.class, () -> batchService.store(b.getId()));

        // 首检不通过：留原因，入库 / 发运继续锁定
        SugarReinspection r1 = inspect(b, 1, false, "req-t001-1");
        assertEquals(1, r1.getVersion());
        SugarBatch afterFail = batchService.get(b.getId());
        assertEquals("不通过", afterFail.getReviewStatus());
        assertEquals("色值偏高，需返料", afterFail.getLastRejectReason());
        assertFalse(afterFail.isShippable());
        assertNotNull(afterFail.getShipLockReason());
        assertThrows(BizException.class, () -> batchService.store(b.getId()));
        assertEquals("待复检", batchRepository.findById(b.getId()).orElseThrow().getStatus());
    }

    @Test
    void passed_review_unlocks_store_then_ship() {
        SugarBatch b = newBatch("SB-T002", "1号糖仓", "40000");
        inspect(b, 1, true, "req-t002-1");

        SugarBatch reviewed = batchService.get(b.getId());
        assertEquals("通过", reviewed.getReviewStatus());
        assertEquals(1, reviewed.getReviewVersion());
        assertNotNull(reviewed.getLastInspectTime());
        // 在仓前状态仍是「待复检」：资格未开但提示先入库
        assertFalse(reviewed.isShippable());
        assertTrue(reviewed.getShipLockReason().contains("入库"));

        SugarBatch stored = batchService.store(b.getId());
        assertEquals("已入库", stored.getStatus());
        assertTrue(stored.isShippable(), "已入库且最新复检通过必须具备发运资格");
        assertNull(stored.getShipLockReason());

        SugarBatch shipped = batchService.ship(b.getId());
        assertEquals("已发运", shipped.getStatus());
        assertFalse(shipped.isShippable());
        assertEquals("该批已发运", shipped.getShipLockReason());

        // 非法推进：已发运不能再发运 / 入库
        assertThrows(BizException.class, () -> batchService.ship(b.getId()));
        assertThrows(BizException.class, () -> batchService.store(b.getId()));
    }

    @Test
    void fail_reason_required_and_metrics_validated() {
        SugarBatch b = newBatch("SB-T003", "2号糖仓", "20000");
        // 不通过却没有原因
        assertThrows(BizException.class, () -> batchService.reinspect(b.getId(), 1,
                LocalDateTime.now(), q("99.8", "99.6", "0.1"), "不通过", "  ", "req-t003-x"));
        // 结论非法
        assertThrows(BizException.class, () -> batchService.reinspect(b.getId(), 1,
                LocalDateTime.now(), q("99.8", "99.6", "0.1"), "待定", null, "req-t003-y"));
        // 指标越界
        assertThrows(BizException.class, () -> batchService.reinspect(b.getId(), 1,
                LocalDateTime.now(), q("101.2", "99.6", "0.1"), "通过", null, "req-t003-z"));
        // 缺复检时间
        assertThrows(BizException.class, () -> batchService.reinspect(b.getId(), 1,
                null, q("99.8", "99.6", "0.1"), "通过", null, "req-t003-w"));
        // 全部回滚：没有任何复检记录
        assertEquals(0, reviewRepository.findByBatchIdOrderByVersionDesc(b.getId()).size());
        assertEquals("待复检", batchRepository.findById(b.getId()).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // 幂等：重复点击 / 请求重试只落一份
    // ------------------------------------------------------------------

    @Test
    void duplicate_request_id_keeps_single_record() {
        SugarBatch b = newBatch("SB-T004", "1号糖仓", "30000");
        LocalDateTime t = LocalDateTime.now().minusMinutes(10);
        SugarReinspection first = batchService.reinspect(b.getId(), 1, t,
                q("99.85", "99.65", "0.04"), "通过", null, "req-dup-1");

        // 同 requestId 原样重放：返回同一条，不新增版本
        SugarReinspection replay = batchService.reinspect(b.getId(), 1, t,
                q("99.85", "99.65", "0.04"), "通过", null, "req-dup-1");
        assertEquals(first.getId(), replay.getId());
        assertEquals(1, reviewRepository.findByBatchIdOrderByVersionDesc(b.getId()).size());

        // 同 requestId 但内容变了：明确拒绝，不静默覆盖
        assertThrows(BizException.class, () -> batchService.reinspect(b.getId(), 1, t,
                q("99.85", "99.65", "0.04"), "不通过", "新原因", "req-dup-1"));
        assertEquals(1, reviewRepository.findByBatchIdOrderByVersionDesc(b.getId()).size());

        // 缺 requestId 直接拒绝
        assertThrows(BizException.class, () -> batchService.reinspect(b.getId(), 2, t,
                q("99.85", "99.65", "0.04"), "通过", null, " "));
    }

    @Test
    void database_unique_index_blocks_same_version() {
        SugarBatch b = newBatch("SB-T005", "1号糖仓", "30000");
        batchService.reinspect(b.getId(), 1, LocalDateTime.now(),
                q("99.85", "99.65", "0.04"), "通过", null, "req-uv-1");
        // 绕过服务层直接插一条同版本：(batch_id, version) 唯一索引必须拒绝
        SugarReinspection dup = new SugarReinspection();
        dup.setBatchId(b.getId());
        dup.setBatchNo(b.getBatchNo());
        dup.setVersion(1);
        dup.setRequestId("req-uv-2");
        dup.setInspectTime(LocalDateTime.now());
        dup.setQuality(q("99.0", "98.0", "0.2"));
        dup.setResult("不通过");
        dup.setReason("绕过服务层的旧结果");
        dup.setCreatedAt(LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class, () -> reviewRepository.saveAndFlush(dup));
        // request_id 唯一也兜底
        dup.setVersion(2);
        dup.setRequestId("req-uv-1");
        assertThrows(DataIntegrityViolationException.class, () -> reviewRepository.saveAndFlush(dup));
    }

    @Test
    void concurrent_same_version_only_one_wins_others_fall_back() throws Exception {
        SugarBatch b = newBatch("SB-T008", "2号糖仓", "30000");
        int n = 8;
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(n);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String req = "req-conc-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    // 每个线程都想写第 1 版（重复点击 / 并发提交场景）
                    batchService.reinspect(b.getId(), 1, LocalDateTime.now(),
                            q("99.85", "99.65", "0.04"), "通过", null, req);
                    return "ok-" + req;
                } catch (DataIntegrityViolationException e) {
                    // 行锁内版本校验失败或唯一索引兜底：本事务整体回滚，调用方安全重试 v2
                    return "rejected-" + req;
                } catch (BizException e) {
                    return "rejected-" + req;
                }
            }));
        }
        ready.await();
        start.countDown();
        int ok = 0;
        int rejected = 0;
        for (java.util.concurrent.Future<String> f : futures) {
            if (f.get().startsWith("ok-")) ok++;
            else rejected++;
        }
        pool.shutdown();

        // 只有一份有效的第 1 版落库，其余全部安全失败
        assertEquals(1, ok, "并发同版本提交只能有一份生效");
        assertEquals(n - 1, rejected);
        assertEquals(1, reviewRepository.findByBatchIdOrderByVersionDesc(b.getId()).size());
        // 批次状态未被并发写带偏
        assertEquals("待复检", batchRepository.findById(b.getId()).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // 版本：只追加不改写；旧版本不可回写；重新复检保留历史
    // ------------------------------------------------------------------

    @Test
    void stale_version_write_is_rejected_and_new_version_keeps_history() {
        SugarBatch b = newBatch("SB-T006", "2号糖仓", "30000");
        inspect(b, 1, true, "req-t006-1");
        batchService.store(b.getId());

        // 已入库不能回写第 1 版，也不能跳版本
        assertThrows(BizException.class,
                () -> inspect(b, 1, false, "req-t006-old"), "旧版本回写必须拦截");
        assertThrows(BizException.class,
                () -> inspect(b, 3, false, "req-t006-gap"), "跳版本必须拦截");

        // 重新复检必须走新版本：v2 不通过 → 发运资格立即被剥夺（状态保持已入库）
        SugarReinspection v2 = inspect(b, 2, false, "req-t006-2");
        assertEquals(2, v2.getVersion());
        SugarBatch locked = batchService.get(b.getId());
        assertEquals("已入库", locked.getStatus());
        assertEquals("不通过", locked.getReviewStatus());
        assertFalse(locked.isShippable());
        assertThrows(BizException.class, () -> batchService.ship(b.getId()));

        // v3 通过 → 发运资格恢复；三版历史全保留且版本倒序
        inspect(b, 3, true, "req-t006-3");
        SugarBatch unlocked = batchService.get(b.getId());
        assertTrue(unlocked.isShippable());
        List<SugarReinspection> hist = batchService.history(b.getId());
        assertEquals(List.of(3, 2, 1), hist.stream().map(SugarReinspection::getVersion).toList());
        assertTrue(hist.get(0).isLatest());
        assertFalse(hist.get(1).isLatest());
        assertEquals("色值偏高，需返料", hist.get(1).getReason(), "旧版不通过原因必须保留");
        assertNull(hist.get(2).getReason(), "通过版本不应带原因");

        batchService.ship(b.getId());

        // 已发运后仍只能追加 v4，旧版本一律不可回写
        assertThrows(BizException.class, () -> inspect(b, 2, true, "req-t006-old2"));
        SugarReinspection v4 = inspect(b, 4, true, "req-t006-4");
        assertEquals(4, v4.getVersion());
        assertEquals(4, batchService.history(b.getId()).size());
        // 已发运状态不被复检动作改变
        assertEquals("已发运", batchRepository.findById(b.getId()).orElseThrow().getStatus());
    }

    @Test
    void failed_store_or_ship_leaves_no_orphan_and_status_unchanged() {
        SugarBatch b = newBatch("SB-T007", "3号糖仓", "30000");
        inspect(b, 1, false, "req-t007-1");
        // 入库前提不满足：复检记录保留（这是合法留痕），但批次状态不变、没有半成品状态
        assertThrows(BizException.class, () -> batchService.store(b.getId()));
        assertEquals("待复检", batchRepository.findById(b.getId()).orElseThrow().getStatus());

        // v2 通过入库后，制造 v3 不通过，发运失败必须整体回滚
        inspect(b, 2, true, "req-t007-2");
        batchService.store(b.getId());
        inspect(b, 3, false, "req-t007-3");
        assertThrows(BizException.class, () -> batchService.ship(b.getId()));
        SugarBatch still = batchRepository.findById(b.getId()).orElseThrow();
        assertEquals("已入库", still.getStatus(), "发运失败不能把批次留在中间态");
    }

    // ------------------------------------------------------------------
    // 复检状态筛选
    // ------------------------------------------------------------------

    @Test
    void list_filters_by_review_status() {
        SugarBatch a = newBatch("SB-T10", "1号糖仓", "1000");
        SugarBatch b = newBatch("SB-T11", "1号糖仓", "1000");
        SugarBatch c = newBatch("SB-T12", "1号糖仓", "1000");
        inspect(a, 1, false, "req-t10-1");
        inspect(b, 1, true, "req-t11-1");
        // c 未复检

        List<SugarBatch> waiting = batchService.list(null, null, null, "待复检");
        assertEquals(2, waiting.size(), "未复检与最新不通过都算待复检");
        assertTrue(waiting.stream().anyMatch(x -> x.getId().equals(a.getId())));
        assertTrue(waiting.stream().anyMatch(x -> x.getId().equals(c.getId())));

        List<SugarBatch> passed = batchService.list(null, null, null, "通过");
        assertEquals(1, passed.size());
        assertEquals(b.getId(), passed.get(0).getId());

        List<SugarBatch> failed = batchService.list(null, null, null, "不通过");
        assertEquals(1, failed.size());
        assertEquals(a.getId(), failed.get(0).getId());

        assertThrows(BizException.class, () -> batchService.list(null, null, null, "啥啊"));
    }

    // ------------------------------------------------------------------
    // 糖仓汇总三口径
    // ------------------------------------------------------------------

    @Test
    void stock_summary_splits_waiting_shippable_shipped() {
        // 1号糖仓：一批复检不通过（待复检口径），一批通过已入库（可发运口径），一批已发运
        SugarBatch w1 = newBatch("SB-T20", "1号糖仓", "10000");
        SugarBatch w2 = newBatch("SB-T21", "1号糖仓", "20000");
        SugarBatch w3 = newBatch("SB-T22", "1号糖仓", "30000");
        inspect(w1, 1, false, "req-t20-1");
        inspect(w2, 1, true, "req-t21-1");
        batchService.store(w2.getId());
        inspect(w3, 1, true, "req-t22-1");
        batchService.store(w3.getId());
        batchService.ship(w3.getId());

        // 2号糖仓：一批从未复检（待复检口径）
        SugarBatch w4 = newBatch("SB-T23", "2号糖仓", "5000");

        List<Map<String, Object>> rows = batchService.stockByWarehouse();
        Map<String, Object> wh1 = rows.stream().filter(r -> "1号糖仓".equals(r.get("warehouse")))
                .findFirst().orElseThrow();
        assertEquals(1, wh1.get("waitingBatches"));
        assertEquals(new BigDecimal("10.00"), wh1.get("waitingTon"));
        assertEquals(1, wh1.get("shippableBatches"));
        assertEquals(new BigDecimal("20.00"), wh1.get("shippableTon"));
        assertEquals(1, wh1.get("shippedBatches"));
        assertEquals(new BigDecimal("30.00"), wh1.get("shippedTon"));
        // 在仓合计只数未发运 = 待复检 + 可发运
        assertEquals(2, wh1.get("batches"));
        assertEquals(new BigDecimal("30.00"), wh1.get("quantityTon"));

        Map<String, Object> wh2 = rows.stream().filter(r -> "2号糖仓".equals(r.get("warehouse")))
                .findFirst().orElseThrow();
        assertEquals(1, wh2.get("waitingBatches"));
        assertEquals(0, wh2.get("shippableBatches"));
        assertEquals(0, wh2.get("shippedBatches"));
        assertEquals(new BigDecimal("5.00"), wh2.get("quantityTon"));
    }
}
