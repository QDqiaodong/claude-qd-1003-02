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
 * 成品糖质量复检记录。
 *
 * <p>新建批次先进入「待复检」，值班员按批号逐版录入复检时间、糖度、纯度、纤维分与结论。
 * 复检记录只追加、不改写：版本号在批号内从 1 递增，重复点击 / 请求重试带同一
 * {@code requestId} 只能落一份有效记录（唯一索引兜底）；已入库 / 已发运的批次不能
 * 回写旧版本，重新复检必须以「当前版本 + 1」落新版本，历史逐版保留。</p>
 *
 * <p>结论为「不通过」时必须填写原因；最新一版通过是入库与发运的共同前提。</p>
 */
@Entity
@Table(name = "sugar_reinspection")
@Getter
@Setter
public class SugarReinspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被复检批次 */
    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 批号快照（复检按批号保存） */
    @Column(name = "batch_no", nullable = false, length = 32)
    private String batchNo;

    /** 版本号：批号内从 1 递增，与 batch_id 组合唯一 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 幂等键：一次复检提交只落一笔（重复点击 / 超时重试共用同一键） */
    @Column(name = "request_id", nullable = false, unique = true, length = 36)
    private String requestId;

    /** 值班员录入的复检时间 */
    @Column(name = "inspect_time", nullable = false)
    private LocalDateTime inspectTime;

    @CompositeType(JuiceQualityCompositeType.class)
    private JuiceQuality quality;

    /** 复检结论：通过 / 不通过 */
    @Column(name = "result", nullable = false, length = 16)
    private String result;

    /** 不通过原因：结论为不通过时必填，通过时留空 */
    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 是否最新一版（服务层派生，给前端高亮） */
    @Transient
    private boolean latest;

    /** 是否为幂等重放（同 requestId 重复提交，未新落记录） */
    @Transient
    private boolean idempotentReplay;

    /** 糖度，派生字段：透传 record 组件 */
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
