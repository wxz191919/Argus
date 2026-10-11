package com.argus.rag.ingestion.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.common.log.OperationLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ETL 运维统计接口（阶段 8：ETL 运维面板后端）。
 *
 * <p>面向系统管理员，提供文档处理流水线的整体健康度视图：
 * 各状态文档数量、近 7 天处理耗时分布、最近失败明细。
 * 前端可据此构建运维面板；亦可供脚本轮询做巡检。
 */
@OperationLog
@RestController
@RequestMapping("/api/admin/etl")
public class EtlOpsStatsController {

    /** 最近失败明细返回条数 */
    private static final int RECENT_FAILURE_LIMIT = 10;

    private final DocumentMapper documentMapper;
    private final CurrentUserService currentUserService;

    public EtlOpsStatsController(DocumentMapper documentMapper,
                                 CurrentUserService currentUserService) {
        this.documentMapper = documentMapper;
        this.currentUserService = currentUserService;
    }

    /**
     * ETL 流水线健康度统计。
     *
     * @param groupId 可选，按群组过滤
     * @return 统计结果
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(@RequestParam(required = false) Long groupId) {
        // 仅系统管理员可查看全局 ETL 运维数据
        currentUserService.requireSystemAdmin();

        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 各状态文档数量
        QueryWrapper<DocumentEntity> statusWrapper = new QueryWrapper<>();
        statusWrapper.select("status", "COUNT(*) AS cnt").eq("deleted", false);
        if (groupId != null) {
            statusWrapper.eq("group_id", groupId);
        }
        statusWrapper.groupBy("status");
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (DocumentStatus status : DocumentStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        documentMapper.selectMaps(statusWrapper).forEach(row -> {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            if (status != null && cnt != null) {
                byStatus.put(String.valueOf(status), ((Number) cnt).longValue());
            }
        });
        result.put("documentsByStatus", byStatus);

        // 2. 近 7 天已完成文档的处理耗时（uploaded_at → processed_at）
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        LambdaQueryWrapper<DocumentEntity> doneWrapper = new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getStatus, DocumentStatus.READY.name())
                .eq(DocumentEntity::getDeleted, false)
                .isNotNull(DocumentEntity::getProcessedAt)
                .ge(DocumentEntity::getProcessedAt, sevenDaysAgo);
        if (groupId != null) {
            doneWrapper.eq(DocumentEntity::getGroupId, groupId);
        }
        List<DocumentEntity> doneDocs = documentMapper.selectList(doneWrapper);
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("sampleCount", doneDocs.size());
        if (!doneDocs.isEmpty()) {
            List<Long> seconds = doneDocs.stream()
                    .map(d -> Duration.between(d.getUploadedAt(), d.getProcessedAt()).getSeconds())
                    .sorted()
                    .toList();
            latency.put("avgSeconds", seconds.stream().mapToLong(Long::longValue).average().orElse(0));
            latency.put("maxSeconds", seconds.get(seconds.size() - 1));
            latency.put("p50Seconds", seconds.get(seconds.size() / 2));
        }
        result.put("last7dProcessingLatency", latency);

        // 3. 最近失败明细（含失败原因，供运维定位）
        LambdaQueryWrapper<DocumentEntity> failWrapper = new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getStatus, DocumentStatus.FAILED.name())
                .eq(DocumentEntity::getDeleted, false)
                .orderByDesc(DocumentEntity::getUpdatedAt)
                .last("LIMIT " + RECENT_FAILURE_LIMIT);
        if (groupId != null) {
            failWrapper.eq(DocumentEntity::getGroupId, groupId);
        }
        List<Map<String, Object>> failures = documentMapper.selectList(failWrapper).stream()
                .map(d -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("documentId", d.getId());
                    item.put("fileName", d.getFileName());
                    item.put("groupId", d.getGroupId());
                    item.put("failureReason", d.getFailureReason());
                    item.put("updatedAt", d.getUpdatedAt());
                    return item;
                })
                .toList();
        result.put("recentFailures", failures);
        result.put("generatedAt", LocalDateTime.now());

        return ApiResponse.success(result);
    }
}
