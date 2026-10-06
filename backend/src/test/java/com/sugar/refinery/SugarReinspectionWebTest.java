package com.sugar.refinery;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sugar.refinery.repository.SugarBatchRepository;
import com.sugar.refinery.repository.SugarReinspectionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 成品糖「质量复检—发运锁定」HTTP 闭环测试。
 *
 * <p>覆盖：批次视图带当前结论 / 最近复检时间 / 发运资格、复检通过后入库发运、
 * 不通过缺原因被 400 拦下、重复 requestId 重试只落一份（接口幂等合并）、
 * 旧版本回写 400、复检状态筛选、糖仓汇总三口径、失败后刷新看到的状态一致。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SugarReinspectionWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private SugarBatchRepository batchRepository;
    @Autowired private SugarReinspectionRepository reviewRepository;

    private final ObjectMapper json = new ObjectMapper();

    private static final MediaType JSON_UTF8 = MediaType.APPLICATION_JSON;

    private Long createBatch(String no, String warehouse, String kg) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("batchNo", no);
        body.put("grade", "白砂糖");
        body.put("quantityKg", new BigDecimal(kg));
        body.put("warehouse", warehouse);
        body.put("quality", Map.of("brix", 99.80, "purity", 99.60, "fiber", 0.05));
        mvc.perform(post("/api/sugar-batches").contentType(JSON_UTF8).content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("待复检"))
                .andExpect(jsonPath("$.data.reviewStatus").value("待复检"))
                .andExpect(jsonPath("$.data.shippable").value(false));
        return batchRepository.findByBatchNo(no).orElseThrow().getId();
    }

    private String reviewBody(int version, boolean pass, String reason, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("inspectTime", "2026-10-06T10:00:00");
        body.put("quality", Map.of("brix", 99.85, "purity", 99.66, "fiber", 0.05));
        body.put("result", pass ? "通过" : "不通过");
        body.put("reason", reason);
        body.put("requestId", requestId);
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        reviewRepository.deleteAll();
        batchRepository.deleteAll();
    }

    @Test
    void review_pass_unlocks_store_and_ship_and_view_matches_api() throws Exception {
        Long id = createBatch("SB-W001", "1号糖仓", "30000");

        // 未复检：入库在前端按钮层也会禁用，接口直接 400
        mvc.perform(post("/api/sugar-batches/{id}/store", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("尚未复检")));

        // 首检通过
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", id)
                        .contentType(JSON_UTF8).content(reviewBody(1, true, null, "web-req-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.result").value("通过"))
                .andExpect(jsonPath("$.data.batchNo").value("SB-W001"));

        // 刷新页面：视图字段与接口一致
        mvc.perform(get("/api/sugar-batches/{id}", id))
                .andExpect(jsonPath("$.data.reviewStatus").value("通过"))
                .andExpect(jsonPath("$.data.reviewVersion").value(1))
                .andExpect(jsonPath("$.data.reviewResult").value("通过"))
                .andExpect(jsonPath("$.data.lastInspectTime").value("2026-10-06T10:00:00"))
                .andExpect(jsonPath("$.data.shippable").value(false))   // 还没入库
                .andExpect(jsonPath("$.data.shipLockReason").value("复检已通过，请先办理入库"));

        mvc.perform(post("/api/sugar-batches/{id}/store", id)).andExpect(status().isOk());
        mvc.perform(get("/api/sugar-batches/{id}", id))
                .andExpect(jsonPath("$.data.status").value("已入库"))
                .andExpect(jsonPath("$.data.shippable").value(true))
                .andExpect(jsonPath("$.data.shipLockReason").doesNotExist());
        mvc.perform(post("/api/sugar-batches/{id}/ship", id)).andExpect(status().isOk());
        mvc.perform(get("/api/sugar-batches/{id}", id))
                .andExpect(jsonPath("$.data.status").value("已发运"))
                .andExpect(jsonPath("$.data.shippable").value(false));
    }

    @Test
    void failed_review_locks_ship_and_requires_reason() throws Exception {
        Long id = createBatch("SB-W002", "2号糖仓", "20000");
        // 不通过但没带原因：400，且无任何复检记录落下
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", id)
                        .contentType(JSON_UTF8).content(reviewBody(1, false, "  ", "web-req-bad")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("原因")));

        // 带原因的不通过：锁定入库 / 发运，原因出现在批次视图
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", id)
                        .contentType(JSON_UTF8).content(reviewBody(1, false, "色值偏高需返料", "web-req-fail")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/sugar-batches/{id}", id))
                .andExpect(jsonPath("$.data.reviewStatus").value("不通过"))
                .andExpect(jsonPath("$.data.lastRejectReason").value("色值偏高需返料"))
                .andExpect(jsonPath("$.data.shippable").value(false))
                .andExpect(jsonPath("$.data.shipLockReason", org.hamcrest.Matchers.containsString("色值偏高需返料")));

        mvc.perform(post("/api/sugar-batches/{id}/store", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不通过")));

        // 历史接口能看到不通过原因
        mvc.perform(get("/api/sugar-batches/{id}/reinspections", id))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].reason").value("色值偏高需返料"))
                .andExpect(jsonPath("$.data[0].latest").value(true));
    }

    @Test
    void duplicate_request_is_idempotent_and_stale_version_rejected() throws Exception {
        Long id = createBatch("SB-W003", "1号糖仓", "15000");
        String payload = reviewBody(1, true, null, "web-req-dup");

        // 连点两次（相同请求体 + 相同 requestId）
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", id).contentType(JSON_UTF8).content(payload))
                .andExpect(status().isOk());
        MvcResult second = mvc.perform(post("/api/sugar-batches/{id}/reinspect", id)
                        .contentType(JSON_UTF8).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("复检已提交（重复请求已合并）"))
                .andReturn();
        JsonNode node = json.readTree(second.getResponse().getContentAsByteArray());
        long reviewId = node.path("data").path("id").asLong();

        // 库里只有一份有效记录
        mvc.perform(get("/api/sugar-batches/{id}/reinspections", id))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(reviewId));

        // 入库后旧版本（v1）回写被拦，必须提交 v2
        mvc.perform(post("/api/sugar-batches/{id}/store", id)).andExpect(status().isOk());
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", id)
                        .contentType(JSON_UTF8).content(reviewBody(1, false, "想回写旧版", "web-req-old")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不可回写旧结果")));

        // v2 落新结论、历史保留两版；v2 不通过立刻锁发运
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", id)
                        .contentType(JSON_UTF8).content(reviewBody(2, false, "复检发现回潮", "web-req-v2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(get("/api/sugar-batches/{id}/reinspections", id))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[1].version").value(1));
        mvc.perform(post("/api/sugar-batches/{id}/ship", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("回潮")));
        // 批次仍停在已入库，没有中间态
        mvc.perform(get("/api/sugar-batches/{id}", id))
                .andExpect(jsonPath("$.data.status").value("已入库"))
                .andExpect(jsonPath("$.data.shippable").value(false));
    }

    @Test
    void review_status_filter_and_stock_summary() throws Exception {
        Long fail = createBatch("SB-W10", "1号糖仓", "10000");
        Long pass = createBatch("SB-W11", "1号糖仓", "20000");
        createBatch("SB-W12", "2号糖仓", "5000");   // 未复检
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", fail)
                .contentType(JSON_UTF8).content(reviewBody(1, false, "水分超标", "web-req-w10")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", pass)
                .contentType(JSON_UTF8).content(reviewBody(1, true, null, "web-req-w11")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sugar-batches/{id}/store", pass)).andExpect(status().isOk());

        // 复检状态筛选：待复检 = 未复检 + 最新不通过
        mvc.perform(get("/api/sugar-batches").param("reviewStatus", "待复检"))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/sugar-batches").param("reviewStatus", "通过"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].batchNo").value("SB-W11"));
        mvc.perform(get("/api/sugar-batches").param("reviewStatus", "不通过"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].batchNo").value("SB-W10"));

        // 糖仓汇总三口径
        MvcResult res = mvc.perform(get("/api/sugar-batches/stock-by-warehouse"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = json.readTree(res.getResponse().getContentAsByteArray()).path("data");
        JsonNode wh1 = null;
        JsonNode wh2 = null;
        for (JsonNode row : rows) {
            if ("1号糖仓".equals(row.path("warehouse").asText())) wh1 = row;
            if ("2号糖仓".equals(row.path("warehouse").asText())) wh2 = row;
        }
        assertEquals(1, wh1.path("waitingBatches").asInt());
        assertEquals(10.00, wh1.path("waitingTon").asDouble(), 0.001);
        assertEquals(1, wh1.path("shippableBatches").asInt());
        assertEquals(20.00, wh1.path("shippableTon").asDouble(), 0.001);
        assertEquals(0, wh1.path("shippedBatches").asInt());
        assertEquals(2, wh1.path("batches").asInt());
        assertEquals(30.00, wh1.path("quantityTon").asDouble(), 0.001);
        assertEquals(1, wh2.path("waitingBatches").asInt());
        assertEquals(5.00, wh2.path("waitingTon").asDouble(), 0.001);
    }

    @Test
    void shipped_batch_can_only_add_new_version() throws Exception {
        Long id = createBatch("SB-W20", "3号糖仓", "18000");
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", id)
                .contentType(JSON_UTF8).content(reviewBody(1, true, null, "web-req-w20-1")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sugar-batches/{id}/store", id)).andExpect(status().isOk());
        mvc.perform(post("/api/sugar-batches/{id}/ship", id)).andExpect(status().isOk());

        // 已发运：旧版本不可回写
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", id)
                        .contentType(JSON_UTF8).content(reviewBody(1, false, "篡改旧结果", "web-req-w20-old")))
                .andExpect(status().isBadRequest());
        // 追加 v2 允许（留痕 / 事后复检），批次状态保持已发运
        mvc.perform(post("/api/sugar-batches/{id}/reinspect", id)
                        .contentType(JSON_UTF8).content(reviewBody(2, true, null, "web-req-w20-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(get("/api/sugar-batches/{id}", id))
                .andExpect(jsonPath("$.data.status").value("已发运"))
                .andExpect(jsonPath("$.data.reviewVersion").value(2));
    }
}
