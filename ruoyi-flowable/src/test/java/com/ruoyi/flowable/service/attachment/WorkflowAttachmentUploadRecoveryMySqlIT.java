package com.ruoyi.flowable.service.attachment;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.support.TestPropertySourceUtils;
import com.alibaba.druid.pool.DruidDataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import com.ruoyi.framework.config.properties.DruidProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 用真实 InnoDB 事务执行同一恢复契约，包含 SKIP LOCKED、晚启动上传及旧租约回写。 */
class WorkflowAttachmentUploadRecoveryMySqlIT extends WorkflowAttachmentUploadRecoveryTest
{
    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void normalUploadEntryDoesNotNeedNestedPoolConnection(int maxActive) throws Exception
    {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
                DruidDataSource pool = new DruidDataSource())
        {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "spring.datasource.druid.initialSize=0", "spring.datasource.druid.minIdle=0",
                    "spring.datasource.druid.maxActive=" + maxActive,
                    "spring.datasource.druid.maxWait=2000", "spring.datasource.druid.connectTimeout=3000",
                    "spring.datasource.druid.socketTimeout=10000",
                    "spring.datasource.druid.timeBetweenEvictionRunsMillis=60000",
                    "spring.datasource.druid.minEvictableIdleTimeMillis=300000",
                    "spring.datasource.druid.maxEvictableIdleTimeMillis=900000",
                    "spring.datasource.druid.validationQuery=select 1",
                    "spring.datasource.druid.testWhileIdle=true",
                    "spring.datasource.druid.testOnBorrow=false",
                    "spring.datasource.druid.testOnReturn=false");
            context.register(DruidProperties.class);
            context.refresh();
            context.getBean(DruidProperties.class).dataSource(pool);
            MysqlDataSource connectionSettings = (MysqlDataSource) dataSource;
            pool.setUrl(connectionSettings.getURL());
            pool.setUsername(connectionSettings.getUser());
            pool.setPassword(connectionSettings.getPassword());
            var poolManager = new DataSourceTransactionManager(pool);
            var poolMapper = WorkflowH2SchemaMapperSupport.createSpringMapper(pool,
                    "attachment-small-pool", WfAttachmentMapper.class,
                    "mapper/flowable/WfAttachmentMapper.xml");
            var identity = mock(WorkflowIdentityResolver.class);
            CountDownLatch bothTransactions = new CountDownLatch(maxActive);
            when(identity.resolveCurrentIdentity()).thenAnswer(invocation -> {
                bothTransactions.countDown();
                assertThat(bothTransactions.await(5, TimeUnit.SECONDS)).isTrue();
                return new WorkflowCurrentIdentity(Long.toString(ownerId), Set.of());
            });
            var poolJournal = new WorkflowAttachmentUploadJournal(poolMapper, storage, properties, poolManager);
            var poolService = WorkflowMySqlITSupport.transactionalProxy(new WorkflowAttachmentService(
                    poolMapper, storage, properties, identity,
                    mock(WorkflowProcessAccessService.class), poolJournal, poolManager), poolManager);
            var executor = Executors.newFixedThreadPool(2);
            try
            {
                var first = executor.submit(() -> poolService.uploadTemporary("evidence",
                        new MockMultipartFile("file", "one.txt", "text/plain", new byte[] { 1 })));
                var second = executor.submit(() -> poolService.uploadTemporary("evidence",
                        new MockMultipartFile("file", "two.txt", "text/plain", new byte[] { 2 })));
                assertThat(first.get(8, TimeUnit.SECONDS).attachmentId()).isNotBlank();
                assertThat(second.get(8, TimeUnit.SECONDS).attachmentId()).isNotBlank();
                assertThat(jdbc.queryForObject("select count(*) from wf_attachment where owner_user_id=?",
                        Integer.class, ownerId)).isEqualTo(2);
                assertThat(pool.getActiveCount()).isZero();
                assertThat(pool.getActivePeak()).isLessThanOrEqualTo(maxActive);
            }
            finally
            {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Override
    DataSource createDataSource() throws Exception
    {
        var source = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(source, "附件上传持久恢复",
                "approvaplat_it", List.of("wf_attachment", "wf_attachment_quota_guard",
                        "wf_attachment_upload"));
        return source;
    }
}
