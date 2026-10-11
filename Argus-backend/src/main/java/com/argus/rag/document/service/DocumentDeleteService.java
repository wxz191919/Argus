package com.argus.rag.document.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.vector.VectorIngestionService;
import com.argus.rag.group.service.GroupMembershipService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档删除与重试服务。
 */
@Service
@Slf4j
public class DocumentDeleteService {

    private final DocumentMapper documentMapper;
    private final GroupMembershipService groupMembershipService;
    private final VectorIngestionService vectorIngestionService;
    private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
    private final DocumentChunkMapper documentChunkMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    public DocumentDeleteService(DocumentMapper documentMapper,
                                 GroupMembershipService groupMembershipService,
                                 VectorIngestionService vectorIngestionService,
                                 ElasticsearchChunkIndexService elasticsearchChunkIndexService,
                                 DocumentChunkMapper documentChunkMapper,
                                 ApplicationEventPublisher applicationEventPublisher) {
        this.documentMapper = documentMapper;
        this.groupMembershipService = groupMembershipService;
        this.vectorIngestionService = vectorIngestionService;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
        this.documentChunkMapper = documentChunkMapper;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /** 软删除文档，需群组管理员权限 */
    public void softDeleteDocument(Long userId, Long groupId, Long documentId) {
        requireGroupId(groupId);
        groupMembershipService.requireGroupOwner(groupId);
        if (documentId == null || documentId <= 0) {
            throw new BusinessException("文档ID非法");
        }
        log.info("开始软删除文档: groupId={}, documentId={}", groupId, documentId);
        if (documentMapper.markDeleted(documentId, groupId) == 0) {
            throw new BusinessException("文档不存在或已删除");
        }
        vectorIngestionService.deleteDocumentVectors(documentId);
        elasticsearchChunkIndexService.deleteDocumentChunks(documentId);
        cleanupChunkRows(documentId);
        log.info("文档软删除完成: groupId={}, documentId={}", groupId, documentId);
    }

    /**
     * 清理文档在 MySQL 中残留的 chunk 行。
     *
     * <p>文档软删后，向量（PG）与 ES 索引均已清理，但 {@code document_chunks} 表的
     * 行此前会一直保留，随时间累积成为无法通过任何入口回收的死数据。
     * 清理失败仅记录日志不抛出——chunk 行残留不影响正确性
     * （读取入口均以 documents.deleted 过滤），不应因此让删除接口失败。
     *
     * @param documentId 文档 ID
     */
    private void cleanupChunkRows(Long documentId) {
        try {
            int removed = documentChunkMapper.deleteByDocumentId(documentId);
            log.info("软删文档 chunk 行清理完成: documentId={}, removed={}", documentId, removed);
        } catch (RuntimeException exception) {
            log.warn("软删文档 chunk 行清理失败（不影响删除）: documentId={}, reason={}",
                    documentId, exception.getMessage());
        }
    }

    /** 重新处理失败的文档，需群组管理员权限 */
    @Transactional
    public void retryFailedDocumentIngestion(Long userId, Long groupId, Long documentId) {
        requireGroupId(groupId);
        groupMembershipService.requireGroupOwner(groupId);
        if (documentId == null || documentId <= 0) {
            throw new BusinessException("文档ID非法");
        }
        DocumentEntity document = documentMapper.selectByIdAndGroupId(documentId, groupId);
        if (document == null) {
            throw new BusinessException("文档不存在或已删除");
        }
        if (!DocumentStatus.FAILED.name().equals(document.getStatus())) {
            throw new BusinessException("仅失败文档支持重新处理");
        }
        log.info("开始重试失败文档摄入: documentId={}, groupId={}", documentId, groupId);
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<DocumentEntity>()
                .eq(DocumentEntity::getId, documentId)
                .eq(DocumentEntity::getGroupId, groupId)
                .set(DocumentEntity::getStatus, DocumentStatus.PROCESSING.name())
                .set(DocumentEntity::getFailureReason, null)
                .set(DocumentEntity::getProcessedAt, null)
        );
        if (updated == 0) {
            throw new BusinessException("重置文档状态失败");
        }
        applicationEventPublisher.publishEvent(new DocumentIngestionRequestedEvent(documentId, groupId));
        log.info("失败文档重试已触发: documentId={}, groupId={}, newStatus={}", documentId, groupId, DocumentStatus.PROCESSING.name());
    }

    private void requireGroupId(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new BusinessException("groupId 非法");
        }
    }
}
