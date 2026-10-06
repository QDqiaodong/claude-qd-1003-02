package com.sugar.refinery.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CompositeType;

import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.composite.JuiceQualityCompositeType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 成品糖批次。
 *
 * <p>分蜜干燥后按批打包，纯度达到 {@code MIN_WHITE_PURITY} 才能判白砂糖，
 * 否则只能判赤砂糖。新建批次先进入「待复检」，质量复检通过后才允许入库与发运，
 * 状态：待复检 → 已入库 → 已发运。</p>
 *
 * <p>品质同样用 record {@link JuiceQuality} + {@code @CompositeType} 承载。
 * 复检视图字段（当前版本 / 结论 / 复检时间 / 发运资格）由服务层按最新一版
 * {@code sugar_reinspection} 回填，不落本批次表。</p>
 */
@Entity
@Table(name = "sugar_batch")
@Getter
@Setter
public class SugarBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 批号，唯一 */
    @Column(name = "batch_no", nullable = false, unique = true, length = 32)
    private String batchNo;

    /** 白砂糖 / 赤砂糖 / 绵白糖 / 冰糖 */
    @Column(name = "grade", nullable = false, length = 16)
    private String grade;

    /** 数量（千克） */
    @Column(name = "quantity_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantityKg;

    @CompositeType(JuiceQualityCompositeType.class)
    private JuiceQuality quality;

    /** 糖仓 */
    @Column(name = "warehouse", nullable = false, length = 24)
    private String warehouse;

    /** 待复检 / 已入库 / 已发运 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "produced_at")
    private LocalDateTime producedAt;

    // ---------------- 质量复检视图字段（服务层按最新一版回填，不入库） ----------------

    /** 当前复检版本号；从未复检为 null */
    @Transient
    private Integer reviewVersion;

    /** 当前复检结论：通过 / 不通过；从未复检为 null */
    @Transient
    private String reviewResult;

    /** 复检状态：待复检（未复检或最新不通过）/ 通过 / 不通过 */
    @Transient
    private String reviewStatus;

    /** 最近复检时间（最新一版录入的复检时间） */
    @Transient
    private LocalDateTime lastInspectTime;

    /** 最近一次不通过原因（最新一版不通过时有值） */
    @Transient
    private String lastRejectReason;

    /** 发运资格：最新一版复检通过且尚未发运 */
    @Transient
    private boolean shippable;

    /** 未取得发运资格 / 不能继续流转的原因 */
    @Transient
    private String shipLockReason;

    /** 折合吨数，派生字段 */
    @Transient
    public BigDecimal getQuantityTon() {
        return quantityKg == null ? BigDecimal.ZERO
                : quantityKg.divide(BigDecimal.valueOf(1000), 2, java.math.RoundingMode.HALF_UP);
    }

    /** 糖度，派生字段：直接把 record 组件透给前端 */
    @Transient
    public BigDecimal getBrix() {
        return quality == null ? null : quality.getBrix();
    }

    /** 纯度，派生字段 */
    @Transient
    public BigDecimal getPurity() {
        return quality == null ? null : quality.getPurity();
    }
}
