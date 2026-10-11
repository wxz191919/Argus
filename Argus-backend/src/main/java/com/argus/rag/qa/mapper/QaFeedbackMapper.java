package com.argus.rag.qa.mapper;

import com.argus.rag.qa.model.entity.QaFeedbackEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** QA 问答反馈 Mapper，通用 CRUD 由 MyBatis-Plus BaseMapper 提供。 */
@Mapper
public interface QaFeedbackMapper extends BaseMapper<QaFeedbackEntity> {
}
