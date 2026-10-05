-- H2（MODE=MySQL）测试库结构：与 MySQL schema.sql 同构，只放洗罐排程闭环测试需要的
-- evap_pan / pan_wash 两表；pan_wash 的生成列 + 唯一索引与 CHECK 约束一并保留，
-- 让「同一罐不能有两个未完成安排」「完成必须带完成时间」在数据库层也被验证。

DROP TABLE IF EXISTS pan_wash;
DROP TABLE IF EXISTS evap_pan;

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
