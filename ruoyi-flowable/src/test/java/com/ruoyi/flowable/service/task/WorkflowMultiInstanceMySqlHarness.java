package com.ruoyi.flowable.service.task;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.testsupport.*;

/** 复用相同正式业务装配；仅替换数据库资源，Mapper SQL 和事务链不变。 */
@Configuration(proxyBeanMethods = true)
class WorkflowMultiInstanceMySqlHarness extends WorkflowMultiInstanceEngineHarness
{
    @Bean(destroyMethod = "close")
    WorkflowMySqlTestDatabase privateDatabase() throws Exception
    {
        return new WorkflowMySqlTestDatabase("C05 multi-instance lifecycle");
    }

    @Override @Bean(destroyMethod = "close")
    WorkflowFlowableEngineTestSupport engineHarness(WorkflowUserSelectionValidator validator,
            WorkflowMultiInstanceTransitionCoordinator coordinator, LateBindingTaskListener listener)
    {
        try
        {
            return WorkflowFlowableEngineTestSupport.startOnExistingDatabase(privateDatabase().dataSource(),
                    Map.of("multiInstanceHandler", new WorkflowMultiInstanceHandler(validator, coordinator),
                            "userTaskListener", listener));
        }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    @Override @Bean("roundMapperDelegate")
    WfMultiInstanceRoundMapper roundMapperDelegate(DataSource source, JdbcTemplate jdbc)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(source, "c05-round",
                WfMultiInstanceRoundMapper.class, "mapper/flowable/WfMultiInstanceRoundMapper.xml");
    }

    @Override @Bean
    WfAttachmentMapper attachmentMapper(DataSource source)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(source, "c05-attachment",
                WfAttachmentMapper.class, "mapper/flowable/WfAttachmentMapper.xml");
    }

    @Override @Bean
    WfTaskSlaMapper taskSlaMapper(DataSource source)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(source, "c05-sla",
                WfTaskSlaMapper.class, "mapper/flowable/WfTaskSlaMapper.xml");
    }
}
