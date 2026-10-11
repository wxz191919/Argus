package com.argus.rag.document.scheduler;

import com.argus.rag.document.service.StaleProcessingDocumentRecoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 遗留 PROCESSING 文档的运行时定时回收调度器。
 *
 * <p>{@link StaleProcessingDocumentRecoveryRunner} 只在服务启动时回收一次僵尸文档；
 * 若 ETL 线程在服务运行期间异常终止（未走完 {@code @Recover} 兜底，如线程被强制杀死、
 * JVM 外部中断等），文档同样会卡在 PROCESSING 状态直到下一次重启。
 * 本调度器弥补该空档：运行期间周期性回收超过超时阈值仍无进展的 PROCESSING 文档，
 * 使用户无需等待服务重启即可通过"重试"恢复处理。
 *
 * <p>调度参数：
 * <ul>
 *   <li>回收间隔：{@code document.ingestion.stale-recovery-interval-ms}，默认 5 分钟</li>
 *   <li>首次延迟：{@code document.ingestion.stale-recovery-initial-delay-ms}，默认 2 分钟
 *       （给启动时的全量回收留出时间，避免重复扫描）</li>
 *   <li>僵尸判定阈值：{@code document.ingestion.processing-timeout-minutes}，默认 30 分钟
 *       （与回收服务共享，仅回收该时长内无任何状态进展的文档，不误伤正常处理）</li>
 * </ul>
 *
 * <p>使用 {@code fixedDelay}（上一次执行完成后才开始计时）而非 {@code fixedRate}，
 * 避免回收耗时较长时任务堆积。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 * @see StaleProcessingDocumentRecoveryService#recoverStale()
 */
@Component
@Slf4j
public class StaleProcessingDocumentRecoveryScheduler {

    /** 遗留文档回收服务 */
    private final StaleProcessingDocumentRecoveryService recoveryService;

    /**
     * 构造调度器。
     *
     * @param recoveryService 遗留文档回收服务
     */
    public StaleProcessingDocumentRecoveryScheduler(StaleProcessingDocumentRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /**
     * 周期执行超时僵尸文档回收。
     *
     * <p>异常全部吞掉并记录日志——回收失败不应中断下一次调度，
     * 也不应影响其他定时任务（Spring 默认行为是异常仅在当前任务日志可见，这里显式兜底以示明确）。
     */
    @Scheduled(
            fixedDelayString = "${document.ingestion.stale-recovery-interval-ms:300000}",
            initialDelayString = "${document.ingestion.stale-recovery-initial-delay-ms:120000}"
    )
    public void recoverStaleDocuments() {
        try {
            recoveryService.recoverStale();
        } catch (RuntimeException exception) {
            log.error("运行时僵尸文档回收失败，等待下一轮调度: reason={}", exception.getMessage(), exception);
        }
    }
}
