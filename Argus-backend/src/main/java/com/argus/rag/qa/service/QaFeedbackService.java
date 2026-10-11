package com.argus.rag.qa.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.qa.mapper.QaFeedbackMapper;
import com.argus.rag.qa.mapper.QaRecordMapper;
import com.argus.rag.qa.model.entity.QaFeedbackEntity;
import com.argus.rag.qa.model.entity.QaRecordEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QA 问答反馈服务（阶段 8：问答反馈闭环）。
 *
 * <p>支持对已持久化的问答记录点赞/点踩（点踩可附原因），同一用户对同一记录
 * 仅保留一条反馈（重复提交视为修改），可取消反馈。群组成员可查看本群反馈统计。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Service
@Slf4j
public class QaFeedbackService {

    /** 反馈类型：点赞 */
    public static final String TYPE_LIKE = "LIKE";
    /** 反馈类型：点踩 */
    public static final String TYPE_DISLIKE = "DISLIKE";
    /** 反馈原因字段最大长度 */
    private static final int REASON_MAX_LENGTH = 255;

    private final QaFeedbackMapper qaFeedbackMapper;
    private final QaRecordMapper qaRecordMapper;
    private final CurrentUserService currentUserService;
    private final GroupMembershipService groupMembershipService;

    public QaFeedbackService(QaFeedbackMapper qaFeedbackMapper,
                             QaRecordMapper qaRecordMapper,
                             CurrentUserService currentUserService,
                             GroupMembershipService groupMembershipService) {
        this.qaFeedbackMapper = qaFeedbackMapper;
        this.qaRecordMapper = qaRecordMapper;
        this.currentUserService = currentUserService;
        this.groupMembershipService = groupMembershipService;
    }

    /**
     * 提交或更新反馈（幂等：同一用户对同一记录仅一条，重复提交视为修改）。
     *
     * <p>权限：仅问答记录所属群组的成员可反馈——反馈是群组内质量信号，
     * 不允许跨群组对不可见的问答记录刷反馈。
     *
     * @param recordId 问答记录 ID
     * @param type     反馈类型 LIKE / DISLIKE
     * @param reason   点踩原因（可空）
     * @return 反馈 ID
     */
    @Transactional
    public Long submit(Long recordId, String type, String reason) {
        if (!TYPE_LIKE.equals(type) && !TYPE_DISLIKE.equals(type)) {
            throw new BusinessException("反馈类型仅支持 LIKE/DISLIKE");
        }
        if (reason != null && reason.length() > REASON_MAX_LENGTH) {
            throw new BusinessException("反馈原因不能超过 " + REASON_MAX_LENGTH + " 字");
        }
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();

        QaRecordEntity record = qaRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException("问答记录不存在");
        }
        // 记录归属校验：只允许反馈自己发起的问答（qa_records 本身按用户维度组织）
        if (!record.getUserId().equals(currentUser.userId())) {
            throw new BusinessException("仅可反馈自己的问答记录");
        }

        QaFeedbackEntity existing = selectByRecordAndUser(recordId, currentUser.userId());
        if (existing != null) {
            existing.setFeedbackType(type);
            existing.setReason(TYPE_DISLIKE.equals(type) ? reason : null);
            existing.setUpdatedAt(LocalDateTime.now());
            qaFeedbackMapper.updateById(existing);
            log.info("问答反馈已更新: recordId={}, userId={}, type={}", recordId, currentUser.userId(), type);
            return existing.getId();
        }

        QaFeedbackEntity feedback = new QaFeedbackEntity();
        feedback.setRecordId(recordId);
        feedback.setUserId(currentUser.userId());
        feedback.setGroupId(record.getGroupId());
        feedback.setFeedbackType(type);
        feedback.setReason(TYPE_DISLIKE.equals(type) ? reason : null);
        feedback.setCreatedAt(LocalDateTime.now());
        feedback.setUpdatedAt(LocalDateTime.now());
        try {
            qaFeedbackMapper.insert(feedback);
        } catch (DuplicateKeyException exception) {
            // 并发重复提交：唯一键兜底，语义上等价于"已存在"
            throw new BusinessException("该记录已存在反馈");
        }
        log.info("问答反馈已提交: recordId={}, userId={}, type={}", recordId, currentUser.userId(), type);
        return feedback.getId();
    }

    /**
     * 取消反馈。
     *
     * @param recordId 问答记录 ID
     */
    @Transactional
    public void cancel(Long recordId) {
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        int removed = qaFeedbackMapper.delete(new LambdaQueryWrapper<QaFeedbackEntity>()
                .eq(QaFeedbackEntity::getRecordId, recordId)
                .eq(QaFeedbackEntity::getUserId, currentUser.userId()));
        if (removed == 0) {
            throw new BusinessException("未找到反馈记录");
        }
        log.info("问答反馈已取消: recordId={}, userId={}", recordId, currentUser.userId());
    }

    /**
     * 查询群组维度的反馈统计（点赞数、点踩数、点踩原因分布）。
     *
     * <p>权限：仅群组成员可查看本群统计；点踩原因列表仅返回群组管理员可见的聚合结果。
     *
     * @param groupId 群组 ID
     * @return 统计结果
     */
    public Map<String, Object> stats(Long groupId) {
        groupMembershipService.requireGroupReadable(groupId);
        List<QaFeedbackEntity> all = qaFeedbackMapper.selectList(new LambdaQueryWrapper<QaFeedbackEntity>()
                .eq(QaFeedbackEntity::getGroupId, groupId));

        long likeCount = all.stream().filter(f -> TYPE_LIKE.equals(f.getFeedbackType())).count();
        long dislikeCount = all.stream().filter(f -> TYPE_DISLIKE.equals(f.getFeedbackType())).count();

        // 点踩原因分布（保持插入顺序的聚合）
        Map<String, Long> reasonCounts = new LinkedHashMap<>();
        all.stream()
                .filter(f -> TYPE_DISLIKE.equals(f.getFeedbackType()))
                .filter(f -> f.getReason() != null && !f.getReason().isBlank())
                .forEach(f -> reasonCounts.merge(f.getReason(), 1L, Long::sum));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groupId", groupId);
        result.put("likeCount", likeCount);
        result.put("dislikeCount", dislikeCount);
        result.put("totalFeedback", likeCount + dislikeCount);
        result.put("dislikeReasons", reasonCounts);
        return result;
    }

    /** 查询当前用户对某条记录的反馈，无则返回 null。 */
    public QaFeedbackEntity selectByRecordAndUser(Long recordId, Long userId) {
        return qaFeedbackMapper.selectOne(new LambdaQueryWrapper<QaFeedbackEntity>()
                .eq(QaFeedbackEntity::getRecordId, recordId)
                .eq(QaFeedbackEntity::getUserId, userId));
    }
}
