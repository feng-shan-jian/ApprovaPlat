package com.ruoyi.flowable.service.retention;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.flowable.config.WorkflowDataRetentionProperties;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WfCollaborationMessageMapper;
import com.ruoyi.flowable.mapper.WfCollaborationOutboxMapper;
import com.ruoyi.flowable.mapper.WfProcessDraftMapper;
import com.ruoyi.flowable.mapper.WfRuntimeEventRequestMapper;

/**
 * 将各领域已有 Mapper 或 JdbcTemplate SQL 适配为独立生命周期清理器。
 */
@Configuration
public class WorkflowDataRetentionConfiguration
{
    /**
     * 创建通知 outbox 终态历史清理器。
     * @param jdbcTemplate JdbcTemplate，正式通知表数据访问入口
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除 PROCESSED、CANCELLED 的清理器
     */
    @Bean
    WorkflowDataRetentionCleaner notificationOutboxRetentionCleaner(
            JdbcTemplate jdbcTemplate, WorkflowDataRetentionProperties properties)
    {
        return new WorkflowJdbcDataRetentionCleaner(
                WorkflowDataRetentionDomain.NOTIFICATION_OUTBOX, jdbcTemplate,
                properties::getNotificationOutboxRetention, properties::getBatchSize,
                WorkflowNotificationRetentionSql.OUTBOX_SELECT,
                WorkflowNotificationRetentionSql.OUTBOX_DELETE_PREFIX,
                WorkflowNotificationRetentionSql.OUTBOX_DELETE_SUFFIX,
                WorkflowNotificationRetentionSql.OUTBOX_OLDEST);
    }

    /**
     * 创建运行事件终态请求清理器。
     * @param mapper WfRuntimeEventRequestMapper，运行事件领域数据访问层
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除 PROCESSED、FAILED 的清理器
     */
    @Bean
    WorkflowDataRetentionCleaner runtimeEventRetentionCleaner(
            WfRuntimeEventRequestMapper mapper, WorkflowDataRetentionProperties properties)
    {
        return mapperCleaner(WorkflowDataRetentionDomain.RUNTIME_EVENT,
                properties::getRuntimeEventRetention, properties, new WorkflowRetentionBatchOperations<String>()
                {
                    /** {@inheritDoc} */
                    @Override
                    public List<String> selectIdsForUpdate(LocalDateTime cutoffTime, int limit)
                    {
                        return mapper.selectRetentionIdsForUpdate(cutoffTime, limit);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public int deleteByIds(List<String> ids, LocalDateTime cutoffTime)
                    {
                        return mapper.deleteRetentionByIds(ids, cutoffTime);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public LocalDateTime selectOldestPendingTime()
                    {
                        return mapper.selectOldestRetentionTime();
                    }
                });
    }

    /**
     * 创建已终态流程草稿清理器。
     * @param mapper WfProcessDraftMapper，草稿领域数据访问层
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除 SUBMITTED、DELETED 的清理器
     */
    @Bean
    WorkflowDataRetentionCleaner processDraftRetentionCleaner(
            WfProcessDraftMapper mapper, WorkflowDataRetentionProperties properties)
    {
        return mapperCleaner(WorkflowDataRetentionDomain.PROCESS_DRAFT,
                properties::getProcessDraftRetention, properties, new WorkflowRetentionBatchOperations<String>()
                {
                    /** {@inheritDoc} */
                    @Override
                    public List<String> selectIdsForUpdate(LocalDateTime cutoffTime, int limit)
                    {
                        return mapper.selectRetentionIdsForUpdate(cutoffTime, limit);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public int deleteByIds(List<String> ids, LocalDateTime cutoffTime)
                    {
                        return mapper.deleteRetentionByIds(ids, cutoffTime);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public LocalDateTime selectOldestPendingTime()
                    {
                        return mapper.selectOldestRetentionTime();
                    }
                });
    }

    /**
     * 创建已完成入站协作消息清理器。
     * @param mapper WfCollaborationMessageMapper，协作消息数据访问层
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除 PROCESSED 且保留死信的清理器
     */
    @Bean
    WorkflowDataRetentionCleaner collaborationMessageRetentionCleaner(
            WfCollaborationMessageMapper mapper, WorkflowDataRetentionProperties properties)
    {
        return mapperCleaner(WorkflowDataRetentionDomain.COLLABORATION_MESSAGE,
                properties::getCollaborationRetention, properties, new WorkflowRetentionBatchOperations<String>()
                {
                    /** {@inheritDoc} */
                    @Override
                    public List<String> selectIdsForUpdate(LocalDateTime cutoffTime, int limit)
                    {
                        return mapper.selectRetentionIdsForUpdate(cutoffTime, limit);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public int deleteDependentRecords(List<String> ids)
                    {
                        return mapper.deleteRetentionAuditsByIds(ids);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public int deleteByIds(List<String> ids, LocalDateTime cutoffTime)
                    {
                        return mapper.deleteRetentionByIds(ids, cutoffTime);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public LocalDateTime selectOldestPendingTime()
                    {
                        return mapper.selectOldestRetentionTime();
                    }
                });
    }

    /**
     * 创建已完成出站协作 outbox 清理器。
     * @param mapper WfCollaborationOutboxMapper，协作 outbox 数据访问层
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除 PROCESSED、CANCELLED 且保留死信的清理器
     */
    @Bean
    WorkflowDataRetentionCleaner collaborationOutboxRetentionCleaner(
            WfCollaborationOutboxMapper mapper, WorkflowDataRetentionProperties properties)
    {
        return mapperCleaner(WorkflowDataRetentionDomain.COLLABORATION_OUTBOX,
                properties::getCollaborationRetention, properties, new WorkflowRetentionBatchOperations<String>()
                {
                    /** {@inheritDoc} */
                    @Override
                    public List<String> selectIdsForUpdate(LocalDateTime cutoffTime, int limit)
                    {
                        return mapper.selectRetentionIdsForUpdate(cutoffTime, limit);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public int deleteDependentRecords(List<String> ids)
                    {
                        return mapper.deleteRetentionAuditsByIds(ids);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public int deleteByIds(List<String> ids, LocalDateTime cutoffTime)
                    {
                        return mapper.deleteRetentionByIds(ids, cutoffTime);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public LocalDateTime selectOldestPendingTime()
                    {
                        return mapper.selectOldestRetentionTime();
                    }
                });
    }

    /**
     * 创建已物理删除附件元数据清理器。
     * @param mapper WfAttachmentMapper，附件领域数据访问层
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除 storage_deleted_time 已记录的元数据清理器
     */
    @Bean
    WorkflowDataRetentionCleaner attachmentMetadataRetentionCleaner(
            WfAttachmentMapper mapper, WorkflowDataRetentionProperties properties)
    {
        return mapperCleaner(WorkflowDataRetentionDomain.ATTACHMENT_METADATA,
                properties::getAttachmentMetadataRetention, properties, new WorkflowRetentionBatchOperations<String>()
                {
                    /** {@inheritDoc} */
                    @Override
                    public List<String> selectIdsForUpdate(LocalDateTime cutoffTime, int limit)
                    {
                        return mapper.selectRetentionIdsForUpdate(cutoffTime, limit);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public int deleteByIds(List<String> ids, LocalDateTime cutoffTime)
                    {
                        return mapper.deleteRetentionByIds(ids, cutoffTime);
                    }
                    /** {@inheritDoc} */
                    @Override
                    public LocalDateTime selectOldestPendingTime()
                    {
                        return mapper.selectOldestRetentionTime();
                    }
                });
    }

    /**
     * 创建已读站内通知清理器。
     * @param jdbcTemplate JdbcTemplate，正式通知表数据访问入口
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除 READ 且绝不删除 UNREAD 的清理器
     */
    @Bean
    WorkflowDataRetentionCleaner notificationInboxRetentionCleaner(
            JdbcTemplate jdbcTemplate, WorkflowDataRetentionProperties properties)
    {
        return new WorkflowJdbcDataRetentionCleaner(
                WorkflowDataRetentionDomain.NOTIFICATION_INBOX, jdbcTemplate,
                properties::getNotificationInboxRetention, properties::getBatchSize,
                WorkflowNotificationRetentionSql.INBOX_SELECT,
                WorkflowNotificationRetentionSql.INBOX_DELETE_PREFIX,
                WorkflowNotificationRetentionSql.INBOX_DELETE_SUFFIX,
                WorkflowNotificationRetentionSql.INBOX_OLDEST);
    }

    /**
     * 创建已结束流程 BPMN 事件审计清理器。
     * @param jdbcTemplate JdbcTemplate，正式工作流数据访问入口
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只清理已结束流程且超过保留期的事件审计
     */
    @Bean
    WorkflowDataRetentionCleaner bpmnEventAuditRetentionCleaner(
            JdbcTemplate jdbcTemplate, WorkflowDataRetentionProperties properties)
    {
        return jdbcCleaner(WorkflowDataRetentionDomain.BPMN_EVENT_AUDIT, jdbcTemplate,
                properties::getBpmnEventAuditRetention, properties,
                WorkflowCoreRetentionSql.BPMN_EVENT_SELECT,
                WorkflowCoreRetentionSql.BPMN_EVENT_DELETE_PREFIX,
                WorkflowCoreRetentionSql.BPMN_EVENT_DELETE_SUFFIX,
                WorkflowCoreRetentionSql.BPMN_EVENT_OLDEST);
    }

    /**
     * 创建终态 SLA 执行清理器，审计由数据库外键级联删除。
     * @param jdbcTemplate JdbcTemplate，正式工作流数据访问入口
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除 COMPLETED/ESCALATED 执行
     */
    @Bean
    WorkflowDataRetentionCleaner taskSlaRetentionCleaner(
            JdbcTemplate jdbcTemplate, WorkflowDataRetentionProperties properties)
    {
        return jdbcCleaner(WorkflowDataRetentionDomain.TASK_SLA_EXECUTION, jdbcTemplate,
                properties::getTaskSlaRetention, properties,
                WorkflowCoreRetentionSql.SLA_SELECT,
                WorkflowCoreRetentionSql.SLA_DELETE_PREFIX,
                WorkflowCoreRetentionSql.SLA_DELETE_SUFFIX,
                WorkflowCoreRetentionSql.SLA_OLDEST);
    }

    /**
     * 创建已结束流程抄送清理器，未读且有效的抄送永久保留。
     * @param jdbcTemplate JdbcTemplate，正式工作流数据访问入口
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除已读或逻辑删除且流程已结束的抄送
     */
    @Bean
    WorkflowDataRetentionCleaner copyRetentionCleaner(
            JdbcTemplate jdbcTemplate, WorkflowDataRetentionProperties properties)
    {
        return jdbcCleaner(WorkflowDataRetentionDomain.COPY, jdbcTemplate,
                properties::getCopyRetention, properties,
                WorkflowCoreRetentionSql.COPY_SELECT,
                WorkflowCoreRetentionSql.COPY_DELETE_PREFIX,
                WorkflowCoreRetentionSql.COPY_DELETE_SUFFIX,
                WorkflowCoreRetentionSql.COPY_OLDEST);
    }

    /**
     * 创建已结束流程受控循环执行清理器。
     * @param jdbcTemplate JdbcTemplate，正式工作流数据访问入口
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，只删除流程已结束且超过保留期的循环记录
     */
    @Bean
    WorkflowDataRetentionCleaner controlledLoopRetentionCleaner(
            JdbcTemplate jdbcTemplate, WorkflowDataRetentionProperties properties)
    {
        return jdbcCleaner(WorkflowDataRetentionDomain.CONTROLLED_LOOP_EXECUTION, jdbcTemplate,
                properties::getControlledLoopRetention, properties,
                WorkflowCoreRetentionSql.CONTROLLED_LOOP_SELECT,
                WorkflowCoreRetentionSql.CONTROLLED_LOOP_DELETE_PREFIX,
                WorkflowCoreRetentionSql.CONTROLLED_LOOP_DELETE_SUFFIX,
                WorkflowCoreRetentionSql.CONTROLLED_LOOP_OLDEST);
    }

    /**
     * 创建已结束流程多实例轮次快照清理器。
     * @param jdbcTemplate JdbcTemplate，正式工作流数据访问入口
     * @param properties WorkflowDataRetentionProperties，批次与保留期配置
     * @return WorkflowDataRetentionCleaner，仅按流程结束时间清理超过保留期的轮次快照
     */
    @Bean
    WorkflowDataRetentionCleaner multiInstanceRoundRetentionCleaner(
            JdbcTemplate jdbcTemplate, WorkflowDataRetentionProperties properties)
    {
        return jdbcCleaner(WorkflowDataRetentionDomain.MULTI_INSTANCE_ROUND, jdbcTemplate,
                properties::getMultiInstanceRoundRetention, properties,
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_SELECT,
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_DELETE_PREFIX,
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_DELETE_SUFFIX,
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_OLDEST);
    }

    /**
     * 创建使用 JdbcTemplate 的单域短事务清理器。
     * @param domain WorkflowDataRetentionDomain，固定数据域
     * @param jdbcTemplate JdbcTemplate，正式数据访问入口
     * @param retentionSupplier java.util.function.Supplier&lt;java.time.Duration&gt;，领域保留期
     * @param properties WorkflowDataRetentionProperties，提供动态批次上限
     * @param selectSql String，稳定主键有界领取 SQL
     * @param deletePrefix String，删除 SQL 主键条件前缀
     * @param deleteSuffix String，删除 SQL 终态复核后缀
     * @param oldestSql String，最老剩余可清理时间查询 SQL
     * @return WorkflowDataRetentionCleaner，可由 Spring 事务代理的领域清理 Bean
     */
    private WorkflowDataRetentionCleaner jdbcCleaner(
            WorkflowDataRetentionDomain domain, JdbcTemplate jdbcTemplate,
            java.util.function.Supplier<java.time.Duration> retentionSupplier,
            WorkflowDataRetentionProperties properties, String selectSql,
            String deletePrefix, String deleteSuffix, String oldestSql)
    {
        return new WorkflowJdbcDataRetentionCleaner(domain, jdbcTemplate, retentionSupplier,
                properties::getBatchSize, selectSql, deletePrefix, deleteSuffix, oldestSql);
    }

    /**
     * 创建通用 Mapper 短事务清理器，公共流程只负责批次一致性，不包含领域状态常量。
     * @param domain WorkflowDataRetentionDomain，固定数据域
     * @param retentionSupplier java.util.function.Supplier&lt;java.time.Duration&gt;，领域保留期
     * @param properties WorkflowDataRetentionProperties，提供动态批次上限
     * @param operations WorkflowRetentionBatchOperations&lt;T&gt;，领域数据操作
     * @param <T> 领域稳定主键类型
     * @return WorkflowDataRetentionCleaner，可由 Spring 事务代理的领域清理 Bean
     */
    private <T> WorkflowDataRetentionCleaner mapperCleaner(
            WorkflowDataRetentionDomain domain,
            java.util.function.Supplier<java.time.Duration> retentionSupplier,
            WorkflowDataRetentionProperties properties,
            WorkflowRetentionBatchOperations<T> operations)
    {
        return new WorkflowMapperDataRetentionCleaner<>(domain, retentionSupplier,
                properties::getBatchSize, operations);
    }
}
