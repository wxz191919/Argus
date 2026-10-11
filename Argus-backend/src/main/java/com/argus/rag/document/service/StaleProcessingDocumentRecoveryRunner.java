package com.argus.rag.document.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时回收遗留在 PROCESSING 状态的文档。
 *
 * <p>当服务异常中断时，正在处理的文档会长时间保持在 PROCESSING 状态，导致前端永远显示"处理中"。
 * 本组件在 Spring 启动完成后执行一次<b>全量</b>回收——启动瞬间不可能存在合法的进行中处理，
 * 所有 PROCESSING 文档均视为遗留，标记为 FAILED，用户可通过重试功能重新触发处理。
 *
 * <p>服务运行期间的周期性回收由
 * {@link com.argus.rag.document.scheduler.StaleProcessingDocumentRecoveryScheduler} 负责，
 * 两者共享 {@link StaleProcessingDocumentRecoveryService} 中的回收逻辑与超时配置
 * {@code document.ingestion.processing-timeout-minutes}。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Component
@Slf4j
public class StaleProcessingDocumentRecoveryRunner implements ApplicationRunner {

    /** 遗留文档回收服务（启动全量回收 + 运行时超时回收共享逻辑） */
    private final StaleProcessingDocumentRecoveryService recoveryService;

    /**
     * 构造启动恢复组件。
     *
     * @param recoveryService 遗留文档回收服务
     */
    public StaleProcessingDocumentRecoveryRunner(StaleProcessingDocumentRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /**
     * 应用启动后执行：将遗留的 PROCESSING 文档标记为 FAILED。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        recoveryService.recoverAll();
    }
}
