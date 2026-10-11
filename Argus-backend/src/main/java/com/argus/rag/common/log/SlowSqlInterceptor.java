package com.argus.rag.common.log;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * 慢 SQL 拦截器：执行耗时超过阈值的 SQL 记录 WARN 日志（阶段 6 可观测性）。
 *
 * <p>拦截 MyBatis Executor 的 query/update 方法，统计真实执行耗时
 * （含参数绑定与结果映射，比驱动层统计更贴近业务体感）。
 * 超过 {@code argus.observability.slow-sql-threshold-ms}（默认 1000ms）时输出：
 * <ul>
 *   <li>执行耗时</li>
 *   <li>MappedStatement ID（定位到 Mapper 方法）</li>
 *   <li>带参数占位符的 SQL 与实际参数值</li>
 * </ul>
 *
 * <p>设计取舍：只记录 WARN 日志而不做指标聚合——当前规模下日志足够定位问题；
 * 后续接入 Micrometer/Prometheus 时可将耗时直方图上报（见阶段 6 报告后续项）。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
@Slf4j
public class SlowSqlInterceptor implements Interceptor {

    /** 慢 SQL 阈值（毫秒） */
    private final long slowSqlThresholdMs;

    public SlowSqlInterceptor(
            @Value("${argus.observability.slow-sql-threshold-ms:1000}") long slowSqlThresholdMs) {
        this.slowSqlThresholdMs = slowSqlThresholdMs;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= slowSqlThresholdMs) {
                MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
                Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;
                String sql = extractSql(ms, parameter);
                log.warn("慢SQL: elapsed={}ms, mapper={}, sql={}, params={}",
                        elapsed, ms.getId(), sql, abbreviateParams(parameter));
            }
        }
    }

    /**
     * 提取 BoundSql 中的 SQL 文本；构造失败时降级为 Mapper ID。
     */
    private String extractSql(MappedStatement ms, Object parameter) {
        try {
            BoundSql boundSql = ms.getBoundSql(parameter);
            return boundSql.getSql().replaceAll("\\s+", " ").trim();
        } catch (RuntimeException exception) {
            return "<unavailable: " + exception.getMessage() + ">";
        }
    }

    /**
     * 参数摘要：仅保留类型与简单值，防止大对象/敏感字段刷爆日志。
     */
    private String abbreviateParams(Object parameter) {
        if (parameter == null) {
            return "null";
        }
        String s = String.valueOf(parameter);
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    @Override
    public Object plugin(Object target) {
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {
        // 无需额外属性，阈值走 Spring @Value
    }
}
