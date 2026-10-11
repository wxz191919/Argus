package com.argus.rag.document.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.document.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 遗留 PROCESSING 文档回收服务。
 *
 * <p>文档 ETL 为异步执行，当处理线程异常终止（如服务重启、线程池队列丢失）时，
 * 文档会永远停留在 PROCESSING 状态，导致前端一直显示"处理中"且无法重新触发。
 * 本服务将这类"僵尸"文档标记为 FAILED，用户即可通过重试功能恢复处理。
 *
 * <p>提供两种回收策略：
 * <ol>
 *   <li><b>全量回收</b>（{@link #recoverAll()}）：服务启动时执行。
 *       启动瞬间不可能存在合法的进行中处理，因此所有 PROCESSING 文档均视为遗留，
 *       对应 {@link StaleProcessingDocumentRecoveryRunner} 的原有语义。</li>
 *   <li><b>超时回收</b>（{@link #recoverStale()}）：运行期由调度器周期执行。
 *       仅回收 {@code updated_at} 早于超时阈值的文档，避免误伤正常处理中的文档——
 *       正常 ETL 处理中会更新文档状态，只有长时间无任何进展的才是僵尸。</li>
 * </ol>
 *
 * <p>超时阈值通过配置项 {@code document.ingestion.processing-timeout-minutes}（默认 30 分钟）控制。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Service
@Slf4j
public class StaleProcessingDocumentRecoveryService {

    /** 默认失败原因：处理中断导致未完成 */
    private static final String DEFAULT_FAILURE_REASON = "文档处理任务因服务中断未完成，请重试。";

    /** 文档数据访问 */
    private final DocumentMapper documentMapper;

    /** 遗留超时阈值（分钟）：PROCESSING 状态超过该时长无进展即视为僵尸 */
    private final long staleTimeoutMinutes;

    /**
     * 构造回收服务。
     *
     * @param documentMapper      文档数据访问层
     * @param staleTimeoutMinutes 遗留超时阈值（分钟），配置项 document.ingestion.processing-timeout-minutes，默认 30
     */
    public StaleProcessingDocumentRecoveryService(
            DocumentMapper documentMapper,
            @Value("${document.ingestion.processing-timeout-minutes:30}") long staleTimeoutMinutes
    ) {
        this.documentMapper = documentMapper;
        this.staleTimeoutMinutes = staleTimeoutMinutes;
    }

    /**
     * 全量回收：将所有 PROCESSING 文档标记为 FAILED。
     *
     * <p>仅在服务启动时调用——启动瞬间不存在合法的进行中处理。
     * 在事务中执行，确保标记操作的原子性。
     *
     * @return 回收的文档数量
     */
    @Transactional
    public int recoverAll() {
        int recovered = documentMapper.markStaleProcessingDocumentsFailed(
                DocumentStatus.PROCESSING.name(),
                DocumentStatus.FAILED.name(),
                DEFAULT_FAILURE_REASON,
                null,
                LocalDateTime.now()
        );
        logRecovery("启动全量回收", recovered);
        return recovered;
    }

    /**
     * 超时回收：仅将 {@code updated_at} 早于超时阈值的 PROCESSING 文档标记为 FAILED。
     *
     * <p>供运行期调度器周期调用。阈值内的 PROCESSING 文档可能是正在正常处理的，
     * 一律跳过。在事务中执行，确保标记操作的原子性。
     *
     * @return 回收的文档数量
     */
    @Transactional
    public int recoverStale() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(staleTimeoutMinutes);
        int recovered = documentMapper.markStaleProcessingDocumentsFailed(
                DocumentStatus.PROCESSING.name(),
                DocumentStatus.FAILED.name(),
                DEFAULT_FAILURE_REASON,
                staleBefore,
                LocalDateTime.now()
        );
        if (recovered > 0) {
            logRecovery("运行时超时回收(staleBefore=" + staleBefore + ")", recovered);
        }
        return recovered;
    }

    /**
     * 输出回收结果日志。
     *
     * @param scenario  回收场景描述（用于日志区分）
     * @param recovered 回收数量
     */
    private void logRecovery(String scenario, int recovered) {
        if (recovered > 0) {
            log.warn("{}遗留处理中文档: recoveredCount={}", scenario, recovered);
        } else {
            log.info("{}检查完成，无需回收", scenario);
        }
    }
}
