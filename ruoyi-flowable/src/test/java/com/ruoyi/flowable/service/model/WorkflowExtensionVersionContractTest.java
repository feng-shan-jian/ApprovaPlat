package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.flowable.domain.*;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.extension.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.listener.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.service.task.*;

class WorkflowExtensionVersionContractTest
{
    @Test
    void createsCanonicalVersionsForEverySupportedExtensionType()
    {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:extension-version-" + UUID.randomUUID());
        var identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        var proxy = new ProxyFactory(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                mock(org.flowable.engine.IdentityService.class), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        assertExtensionVersionBoundaries((WorkflowEngineOperations) proxy.getProxy());
    }

    private static void assertExtensionVersionBoundaries(WorkflowEngineOperations operations)
    {
        var mapper = mock(WfBpmnExtensionMapper.class);
        var javaHandler = new WorkflowSetVariableJavaHandler();
        var service = new WorkflowExtensionRegistryService(operations, mapper, null,
                new WorkflowJavaExtensionHandlerRegistry(List.of(javaHandler)));
        var inserted = new java.util.ArrayList<WfBpmnExtensionVersion>();
        when(mapper.selectMaxVersionNo(1L)).thenReturn(4);
        when(mapper.insertVersion(any())).thenAnswer(invocation -> {
            WfBpmnExtensionVersion version = invocation.getArgument(0);
            version.setVersionId(5L); inserted.add(version); return 1;
        });
        for (String type : List.of("JAVA", "CEL", "HTTP", "SQL", "FORM_FIELD"))
        {
            var extension = new WfBpmnExtension();
            extension.setExtensionId(1L); extension.setExtensionKey("cleanup-test");
            extension.setExtensionType(type);
            when(mapper.selectByIdForUpdate(1L)).thenReturn(extension);
            String key = "JAVA".equals(type) ? javaHandler.implementationKey() : null;
            assertThat(service.createVersion(1L,
                    new com.ruoyi.flowable.domain.dto.WorkflowExtensionVersionCreateRequest(key))).isEqualTo(5L);
            var version = inserted.get(inserted.size() - 1);
            assertThat(version.getVersionNo()).isEqualTo(5);
            assertThat(version.getChecksum()).isEqualTo(WorkflowExtensionChecksum.sha256(
                    "cleanup-test", type, "5", version.getImplementationKey(), version.getConfigSchema()));
            int count = inserted.size();
            if (!"JAVA".equals(type))
            {
                assertResourceFailure(() -> service.createVersion(1L,
                        new com.ruoyi.flowable.domain.dto.WorkflowExtensionVersionCreateRequest("SET_VARIABLE")),
                        "非 JAVA 扩展不得提交服务端实现键", 400);
                assertThat(inserted).hasSize(count);
            }
        }
        assertThat(inserted).hasSize(5);
    }

    private static void assertResourceFailure(Runnable action, String message, int code)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(com.ruoyi.common.exception.ServiceException.class,
                failure -> { assertThat(failure.getMessage()).isEqualTo(message); assertThat(failure.getCode()).isEqualTo(code); });
    }

}
