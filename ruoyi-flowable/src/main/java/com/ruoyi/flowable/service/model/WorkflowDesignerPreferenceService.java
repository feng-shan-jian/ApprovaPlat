package com.ruoyi.flowable.service.model;

import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDesignerPreference;
import com.ruoyi.flowable.domain.dto.WorkflowDesignerPreferenceRequest;
import com.ruoyi.flowable.domain.vo.WorkflowDesignerPreferenceView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfDesignerPreferenceMapper;

/**
 * 当前用户 BPMN 设计器偏好的持久化服务。
 */
@Service
public class WorkflowDesignerPreferenceService
{
    /** 未创建偏好记录时使用的生产默认值。 */
    private static final WorkflowDesignerPreferenceView DEFAULT_PREFERENCE =
            new WorkflowDesignerPreferenceView("SYSTEM", true, true, true, false, false);

    private final WorkflowEngineOperations engineOperations;

    private final WorkflowIdentityResolver identityResolver;

    private final WfDesignerPreferenceMapper preferenceMapper;

    /**
     * 创建设计器偏好服务。
     * @param engineOperations WorkflowEngineOperations，统一事务与异常边界
     * @param identityResolver WorkflowIdentityResolver，当前正式用户解析器
     * @param preferenceMapper WfDesignerPreferenceMapper，偏好持久化 Mapper
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowDesignerPreferenceService(WorkflowEngineOperations engineOperations,
            WorkflowIdentityResolver identityResolver,
            WfDesignerPreferenceMapper preferenceMapper)
    {
        this.engineOperations = engineOperations;
        this.identityResolver = identityResolver;
        this.preferenceMapper = preferenceMapper;
    }

    /**
     * 查询当前正式用户的设计器偏好，不存在时返回稳定服务端默认值。
     * @return WorkflowDesignerPreferenceView，当前用户偏好或默认值
     */
    public WorkflowDesignerPreferenceView getCurrentPreference()
    {
        return engineOperations.read(() ->
        {
            long userId = Long.parseLong(identityResolver.resolveCurrentIdentity().userId());
            return toView(preferenceMapper.selectByUserId(userId));
        });
    }

    /**
     * 在真实事务中原子保存当前正式用户的完整设计器偏好。
     * @param request WorkflowDesignerPreferenceRequest，已经通过 Web 参数校验的完整偏好
     * @return WorkflowDesignerPreferenceView，数据库回读后的真实偏好
     */
    public WorkflowDesignerPreferenceView saveCurrentPreference(
            WorkflowDesignerPreferenceRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("设计器偏好不能为空", HttpStatus.BAD_REQUEST);
        }
        return engineOperations.writeAsCurrentUser(identity ->
        {
            WfDesignerPreference preference = new WfDesignerPreference();
            preference.setUserId(Long.parseLong(identity.userId()));
            preference.setTheme(request.theme());
            preference.setGridEnabled(request.gridEnabled());
            preference.setMinimapEnabled(request.minimapEnabled());
            preference.setLintEnabled(request.lintEnabled());
            preference.setTokenSimulationEnabled(request.tokenSimulationEnabled());
            preference.setPropertiesCollapsed(request.propertiesCollapsed());
            if (preferenceMapper.upsert(preference) <= 0)
            {
                throw new ServiceException("设计器偏好保存失败", HttpStatus.CONFLICT);
            }
            WfDesignerPreference saved = preferenceMapper.selectByUserId(preference.getUserId());
            if (saved == null)
            {
                throw new ServiceException("设计器偏好保存结果不完整", HttpStatus.CONFLICT);
            }
            return toView(saved);
        });
    }

    /**
     * 将数据库偏好转换为不暴露用户主键的接口视图。
     * @param preference WfDesignerPreference，数据库记录，允许为空
     * @return WorkflowDesignerPreferenceView，正式偏好或稳定默认值
     */
    private WorkflowDesignerPreferenceView toView(WfDesignerPreference preference)
    {
        if (preference == null)
        {
            return DEFAULT_PREFERENCE;
        }
        return new WorkflowDesignerPreferenceView(preference.getTheme(),
                Boolean.TRUE.equals(preference.getGridEnabled()),
                Boolean.TRUE.equals(preference.getMinimapEnabled()),
                Boolean.TRUE.equals(preference.getLintEnabled()),
                Boolean.TRUE.equals(preference.getTokenSimulationEnabled()),
                Boolean.TRUE.equals(preference.getPropertiesCollapsed()));
    }
}
