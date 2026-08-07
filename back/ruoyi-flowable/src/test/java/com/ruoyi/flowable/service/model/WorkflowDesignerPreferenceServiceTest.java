package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Set;
import org.flowable.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.flowable.domain.WfDesignerPreference;
import com.ruoyi.flowable.domain.dto.WorkflowDesignerPreferenceRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfDesignerPreferenceMapper;

class WorkflowDesignerPreferenceServiceTest
{
    private WfDesignerPreferenceMapper preferenceMapper;

    private WorkflowDesignerPreferenceService service;

    /**
     * 创建真实事务边界和当前用户身份，偏好 Mapper 使用可核验替身。
     * @return void，初始化完成后测试可执行读写路径
     */
    @BeforeEach
    void setUp()
    {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of("ROLE2")));
        WorkflowAuthenticationContext authenticationContext = new WorkflowAuthenticationContext(
                mock(IdentityService.class), new WorkflowIdentityCodec());
        WorkflowEngineOperations operations = new WorkflowEngineOperations(authenticationContext,
                new WorkflowExceptionTranslator(), identityResolver);
        preferenceMapper = mock(WfDesignerPreferenceMapper.class);
        service = new WorkflowDesignerPreferenceService(
                operations, identityResolver, preferenceMapper);
    }

    /**
     * 清理当前测试线程事务标记。
     * @return void，后续测试不继承当前事务特征
     */
    @AfterEach
    void tearDown()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证尚未保存偏好时只返回稳定服务端默认值，不执行隐式插入。
     * @return void，默认值或读路径产生写副作用时测试失败
     */
    @Test
    void returnsServerDefaultsWithoutImplicitWrite()
    {
        when(preferenceMapper.selectByUserId(7L)).thenReturn(null);

        var result = service.getCurrentPreference();

        assertThat(result.theme()).isEqualTo("SYSTEM");
        assertThat(result.gridEnabled()).isTrue();
        assertThat(result.minimapEnabled()).isTrue();
        assertThat(result.lintEnabled()).isTrue();
        assertThat(result.tokenSimulationEnabled()).isFalse();
        assertThat(result.propertiesCollapsed()).isFalse();
    }

    /**
     * 验证保存使用事务内可信用户主键，并以数据库回读值作为最终结果。
     * @return void，用户隔离、完整字段或回读语义不正确时测试失败
     */
    @Test
    void savesCompletePreferenceForTrustedCurrentUser()
    {
        WorkflowDesignerPreferenceRequest request = new WorkflowDesignerPreferenceRequest(
                "DARK", false, true, false, true, true);
        when(preferenceMapper.upsert(any(WfDesignerPreference.class))).thenReturn(1);
        WfDesignerPreference stored = preference(7L, "DARK", false, true, false, true, true);
        when(preferenceMapper.selectByUserId(7L)).thenReturn(stored);
        ArgumentCaptor<WfDesignerPreference> captor =
                ArgumentCaptor.forClass(WfDesignerPreference.class);

        var result = service.saveCurrentPreference(request);

        verify(preferenceMapper).upsert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getTheme()).isEqualTo("DARK");
        assertThat(result.tokenSimulationEnabled()).isTrue();
        assertThat(result.propertiesCollapsed()).isTrue();
    }

    /**
     * 构造字段完整的数据库偏好记录。
     * @param userId Long，正式用户主键
     * @param theme String，主题编码
     * @param grid boolean，网格状态
     * @param minimap boolean，小地图状态
     * @param lint boolean，Lint 状态
     * @param simulation boolean，Token 模拟状态
     * @param collapsed boolean，属性面板状态
     * @return WfDesignerPreference，可供 Mapper 回读的领域对象
     */
    private WfDesignerPreference preference(Long userId, String theme, boolean grid,
            boolean minimap, boolean lint, boolean simulation, boolean collapsed)
    {
        WfDesignerPreference preference = new WfDesignerPreference();
        preference.setUserId(userId);
        preference.setTheme(theme);
        preference.setGridEnabled(grid);
        preference.setMinimapEnabled(minimap);
        preference.setLintEnabled(lint);
        preference.setTokenSimulationEnabled(simulation);
        preference.setPropertiesCollapsed(collapsed);
        return preference;
    }
}
