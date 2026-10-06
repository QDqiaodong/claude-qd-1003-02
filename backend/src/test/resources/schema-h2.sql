-- H2（MODE=MySQL）测试库结构：与 MySQL schema.sql 同构。
-- 放洗罐排程闭环需要的 evap_pan / pan_wash，以及成品糖质量复检—发运锁定需要的
-- sugar_batch / sugar_reinspection；生成列唯一索引、CHECK 约束与 MySQL 版一并保留，
-- 让「同一罐不能有两个未完成安排」「完成必须带完成时间」「复检不通过必须留原因」
-- 「按批号 + 版本只追加不改写」在数据库层也被验证。

DROP TABLE IF EXISTS pan_wash;
DROP TABLE IF EXISTS evap_pan;
DROP TABLE IF EXISTS sugar_reinspection;
DROP TABLE IF EXISTS sugar_batch;

CREATE TABLE evap_pan (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    pan_code     VARCHAR(24) NOT NULL UNIQUE,
    pan_name     VARCHAR(64) NOT NULL,
    pan_type     VARCHAR(16) NOT NULL,
    capacity_m3  DECIMAL(8,2) NOT NULL,
    status       VARCHAR(16) NOT NULL,
    last_wash_at TIMESTAMP
);

CREATE TABLE pan_wash (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    wash_no       VARCHAR(32) NOT NULL UNIQUE,
    pan_id        BIGINT NOT NULL,
    pan_code      VARCHAR(24) NOT NULL,
    pan_name      VARCHAR(64) NOT NULL,
    pan_type      VARCHAR(16) NOT NULL,
    window_at     TIMESTAMP NOT NULL,
    status        VARCHAR(16) NOT NULL,
    created_at    TIMESTAMP,
    started_at    TIMESTAMP,
    completed_at  TIMESTAMP,
    cancelled_at  TIMESTAMP,
    failed_reason VARCHAR(255),
    failed_at     TIMESTAMP,
    active_pan_id BIGINT GENERATED ALWAYS AS
                    (CASE WHEN status IN ('待执行', '清洗中') THEN pan_id ELSE NULL END),
    CONSTRAINT fk_wash_pan FOREIGN KEY (pan_id) REFERENCES evap_pan(id),
    CONSTRAINT ck_wash_status CHECK
      ((status = '已完成' AND completed_at IS NOT NULL AND started_at IS NOT NULL)
       OR (status = '清洗中' AND started_at IS NOT NULL AND completed_at IS NULL)
       OR (status = '待执行' AND started_at IS NULL AND completed_at IS NULL)
       OR (status = '已取消' AND cancelled_at IS NOT NULL)),
    CONSTRAINT uq_active_pan UNIQUE (active_pan_id)
);

CREATE TABLE sugar_batch (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no    VARCHAR(32) NOT NULL UNIQUE,
    grade       VARCHAR(16) NOT NULL,
    quantity_kg DECIMAL(12,2) NOT NULL,
    brix        DECIMAL(5,2) NOT NULL,
    purity      DECIMAL(5,2) NOT NULL,
    fiber       DECIMAL(5,2) NOT NULL DEFAULT 0,
    warehouse   VARCHAR(24) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    produced_at TIMESTAMP
);

CREATE TABLE sugar_reinspection (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      BIGINT NOT NULL,
    batch_no      VARCHAR(32) NOT NULL,
    version       INT NOT NULL,
    request_id    VARCHAR(36) NOT NULL UNIQUE,
    inspect_time  TIMESTAMP NOT NULL,
    brix          DECIMAL(5,2) NOT NULL,
    purity        DECIMAL(5,2) NOT NULL,
    fiber         DECIMAL(5,2) NOT NULL,
    result        VARCHAR(16) NOT NULL,
    reason        VARCHAR(255),
    created_at    TIMESTAMP,
    CONSTRAINT fk_review_batch FOREIGN KEY (batch_id) REFERENCES sugar_batch(id),
    CONSTRAINT uq_review_version UNIQUE (batch_id, version),
    CONSTRAINT ck_review_result CHECK (result IN ('通过', '不通过')),
    CONSTRAINT ck_review_reason CHECK
      ((result = '不通过' AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0)
       OR (result = '通过' AND reason IS NULL)),
    CONSTRAINT ck_review_quality CHECK
      (brix BETWEEN 0 AND 100 AND purity BETWEEN 0 AND 100 AND fiber BETWEEN 0 AND 100),
    CONSTRAINT ck_review_version CHECK (version > 0)
);
