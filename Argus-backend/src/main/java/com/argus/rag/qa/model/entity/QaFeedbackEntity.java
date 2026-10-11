package com.argus.rag.qa.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** QA 问答反馈实体，对应 qa_feedback 表（阶段 8：问答反馈闭环）。 */
@Data
@TableName("qa_feedback")
public class QaFeedbackEntity {

    /** 反馈主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联的问答记录 ID（qa_records.id）。 */
    private Long recordId;
    /** 反馈用户 ID。 */
    private Long userId;
    /** 问答所属群组 ID（冗余自 qa_records，便于按群统计）。 */
    private Long groupId;
    /** 反馈类型：LIKE / DISLIKE。 */
    private String feedbackType;
    /** 点踩原因，点赞时为空。 */
    private String reason;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
