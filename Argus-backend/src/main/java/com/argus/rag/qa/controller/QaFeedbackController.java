package com.argus.rag.qa.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.log.OperationLog;
import com.argus.rag.qa.service.QaFeedbackService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * QA 问答反馈接口（阶段 8：问答反馈闭环）。
 *
 * <p>反馈对象是"自己发起的问答记录"，提交/更新/取消均按 当前用户 + 记录 维度幂等。
 */
@OperationLog
@RestController
@RequestMapping("/api/qa/records")
public class QaFeedbackController {

    private final QaFeedbackService qaFeedbackService;
    private final CurrentUserService currentUserService;

    public QaFeedbackController(QaFeedbackService qaFeedbackService,
                                CurrentUserService currentUserService) {
        this.qaFeedbackService = qaFeedbackService;
        this.currentUserService = currentUserService;
    }

    /**
     * 提交/更新反馈。
     *
     * <p>请求体：{@code {"type":"LIKE"}} 或 {@code {"type":"DISLIKE","reason":"答案与文档不符"}}
     *
     * @param recordId 问答记录 ID
     * @param body     反馈内容
     * @return 反馈 ID
     */
    @PutMapping("/{recordId}/feedback")
    public ApiResponse<Map<String, Object>> submit(@PathVariable Long recordId,
                                                   @RequestBody Map<String, String> body) {
        // 显式触发认证校验：未登录直接 401
        currentUserService.getRequiredCurrentUser();
        String type = body.getOrDefault("type", "");
        String reason = body.get("reason");
        Long feedbackId = qaFeedbackService.submit(recordId, type, reason);
        return ApiResponse.success(Map.of("feedbackId", feedbackId, "type", type, "recordId", recordId));
    }

    /** 取消反馈。 */
    @DeleteMapping("/{recordId}/feedback")
    public ApiResponse<Void> cancel(@PathVariable Long recordId) {
        qaFeedbackService.cancel(recordId);
        return ApiResponse.success(null);
    }

    /** 查询当前用户对某条记录的反馈。 */
    @GetMapping("/{recordId}/feedback")
    public ApiResponse<Map<String, Object>> getMine(@PathVariable Long recordId) {
        var currentUser = currentUserService.getRequiredCurrentUser();
        var feedback = qaFeedbackService.selectByRecordAndUser(recordId, currentUser.userId());
        if (feedback == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(Map.of(
                "feedbackId", feedback.getId(),
                "type", feedback.getFeedbackType(),
                "reason", feedback.getReason() == null ? "" : feedback.getReason()));
    }

    /**
     * 群组反馈统计（点赞/点踩/点踩原因分布）。
     *
     * <p>权限：群组成员可查看本群统计。
     */
    @GetMapping("/feedback/stats")
    public ApiResponse<Map<String, Object>> stats(@RequestParam Long groupId) {
        return ApiResponse.success(qaFeedbackService.stats(groupId));
    }
}
