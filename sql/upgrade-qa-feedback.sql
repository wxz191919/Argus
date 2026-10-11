-- ============================================================
-- 阶段 8 差异化功能：QA 问答反馈闭环
-- 对已存在的库执行本脚本即可升级（幂等）
-- ============================================================

-- 问答反馈表：每用户每条记录一条反馈（点踩可附原因）
CREATE TABLE IF NOT EXISTS qa_feedback (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    record_id     BIGINT       NOT NULL COMMENT '关联 qa_records.id',
    user_id       BIGINT       NOT NULL COMMENT '反馈用户 ID',
    group_id      BIGINT       NOT NULL COMMENT '问答所属群组 ID（冗余，便于按群统计）',
    feedback_type VARCHAR(16)  NOT NULL COMMENT '反馈类型：LIKE/DISLIKE',
    reason        VARCHAR(255) NULL COMMENT '点踩原因（可空）',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_user (record_id, user_id),
    KEY idx_group_type (group_id, feedback_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='QA 问答反馈';
