package com.ruoyi.flowable.service.model;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfBpmnEventCode;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnEventCodeRequest;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnEventAuditView;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnEventNotificationView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.mapper.WfBpmnEventMapper;

/**
 * BPMN 错误与升级编码目录、审计和站内通知领域服务。
 */
@Service
public class WorkflowBpmnEventCodeService
{
    /** 可供新模型引用的目录状态。 */
    public static final String ENABLED = "ENABLED";
    /** 停用目录仍保留历史部署和审计回显。 */
    public static final String DISABLED = "DISABLED";

    private final WorkflowEngineOperations engineOperations;
    private final WfBpmnEventMapper eventMapper;

    /**
     * 创建 BPMN 事件目录服务。
     * @param engineOperations WorkflowEngineOperations，正式事务和当前身份边界
     * @param eventMapper WfBpmnEventMapper，目录、审计和通知数据访问层
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowBpmnEventCodeService(WorkflowEngineOperations engineOperations,
            WfBpmnEventMapper eventMapper)
    {
        this.engineOperations = engineOperations;
        this.eventMapper = eventMapper;
    }

    /** @return List&lt;WfBpmnEventCode&gt;，全部真实目录。 */
    public List<WfBpmnEventCode> listManagement()
    {
        return engineOperations.read(() -> List.copyOf(eventMapper.selectCodeList()));
    }

    /**
     * 查询设计器可选择的启用目录。
     * @param eventType String，ERROR 或 ESCALATION
     * @return List&lt;WfBpmnEventCode&gt;，真实数据库选项
     */
    public List<WfBpmnEventCode> listEnabled(String eventType)
    {
        requireEventType(eventType);
        return engineOperations.read(() -> List.copyOf(eventMapper.selectEnabledCodes(eventType)));
    }

    /**
     * 新增稳定编码目录。
     * @param request WorkflowBpmnEventCodeRequest，目录业务字段
     * @return Long，数据库生成主键
     */
    public Long create(WorkflowBpmnEventCodeRequest request)
    {
        return engineOperations.writeAsCurrentUser(identity ->
        {
            String eventType = request.eventType().trim();
            String eventCode = request.eventCode().trim();
            if (eventMapper.selectCode(eventType, eventCode) != null)
            {
                throw new ServiceException("BPMN 事件编码已存在", HttpStatus.CONFLICT);
            }
            WfBpmnEventCode code = new WfBpmnEventCode();
            code.setEventType(eventType);
            code.setEventCode(eventCode);
            code.setEventName(request.eventName().trim());
            code.setNotificationPolicy(request.notificationPolicy());
            code.setStatus(ENABLED);
            code.setRemark(trimToNull(request.description()));
            code.setCreateBy(identity.userId());
            if (eventMapper.insertCode(code) != 1 || code.getEventCodeId() == null)
            {
                throw new ServiceException("BPMN 事件编码保存不完整", HttpStatus.ERROR);
            }
            return code.getEventCodeId();
        });
    }

    /**
     * 修改名称、通知策略和说明，稳定类型及编码不得变更。
     * @param eventCodeId Long，目录主键
     * @param request WorkflowBpmnEventCodeRequest，完整目录字段
     * @return void，更新失败时抛出稳定业务异常
     */
    public void update(Long eventCodeId, WorkflowBpmnEventCodeRequest request)
    {
        requirePositiveId(eventCodeId);
        engineOperations.writeAsCurrentUser(identity ->
        {
            WfBpmnEventCode existing = eventMapper.selectCodeForUpdate(eventCodeId);
            if (existing == null)
            {
                throw new ServiceException("BPMN 事件编码不存在", HttpStatus.NOT_FOUND);
            }
            // 稳定编码已经进入作者 XML 和历史部署，修改会破坏捕获匹配，因此只允许维护元数据。
            if (!existing.getEventType().equals(request.eventType().trim())
                    || !existing.getEventCode().equals(request.eventCode().trim()))
            {
                throw new ServiceException("BPMN 事件类型和编码发布后不可修改", HttpStatus.CONFLICT);
            }
            existing.setEventName(request.eventName().trim());
            existing.setNotificationPolicy(request.notificationPolicy());
            existing.setRemark(trimToNull(request.description()));
            existing.setUpdateBy(identity.userId());
            if (eventMapper.updateCode(existing) != 1)
            {
                throw new ServiceException("BPMN 事件编码更新失败", HttpStatus.CONFLICT);
            }
            return null;
        });
    }

    /**
     * 启用或停用目录；历史部署继续使用已冻结名称和通知策略。
     * @param eventCodeId Long，目录主键
     * @param enabled boolean，目标启用状态
     * @return void，目录不存在时返回 404
     */
    public void changeStatus(Long eventCodeId, boolean enabled)
    {
        requirePositiveId(eventCodeId);
        engineOperations.writeAsCurrentUser(identity ->
        {
            if (eventMapper.selectCodeForUpdate(eventCodeId) == null)
            {
                throw new ServiceException("BPMN 事件编码不存在", HttpStatus.NOT_FOUND);
            }
            if (eventMapper.updateCodeStatus(eventCodeId, enabled ? ENABLED : DISABLED,
                    identity.userId()) != 1)
            {
                throw new ServiceException("BPMN 事件编码状态更新失败", HttpStatus.CONFLICT);
            }
            return null;
        });
    }

    /**
     * 部署时锁定并返回启用目录，阻止停用或未知编码进入新部署。
     * @param eventType String，ERROR 或 ESCALATION
     * @param eventCode String，稳定业务编码
     * @return WfBpmnEventCode，当前启用目录
     */
    public WfBpmnEventCode requireEnabled(String eventType, String eventCode)
    {
        requireEventType(eventType);
        String normalizedCode = eventCode == null ? "" : eventCode.trim();
        WfBpmnEventCode code = eventMapper.selectCode(eventType, normalizedCode);
        if (code == null || !ENABLED.equals(code.getStatus()))
        {
            throw new ServiceException("BPMN " + eventType + " 编码未启用或不存在",
                    HttpStatus.CONFLICT);
        }
        return code;
    }

    /** @return List&lt;WorkflowBpmnEventAuditView&gt;，最近运行审计。 */
    public List<WorkflowBpmnEventAuditView> listAudit()
    {
        return engineOperations.read(() -> List.copyOf(eventMapper.selectAuditList()));
    }

    /** @return List&lt;WorkflowBpmnEventNotificationView&gt;，当前用户最近通知。 */
    public List<WorkflowBpmnEventNotificationView> myNotifications()
    {
        return engineOperations.read(() ->
                List.copyOf(eventMapper.selectNotifications(
                        com.ruoyi.common.utils.SecurityUtils.getUserId().toString())));
    }

    /**
     * 标记当前用户拥有的通知已读。
     * @param notificationId Long，通知主键
     * @return void，不存在、已读或越权时返回 404，避免泄露他人通知
     */
    public void markNotificationRead(Long notificationId)
    {
        requirePositiveId(notificationId);
        engineOperations.writeAsCurrentUser(identity ->
        {
            if (eventMapper.markNotificationRead(notificationId, identity.userId()) != 1)
            {
                throw new ServiceException("BPMN 事件通知不存在或已处理", HttpStatus.NOT_FOUND);
            }
            return null;
        });
    }

    /** @param eventType String，待核验类型；@return void，非法类型抛出 400。 */
    private void requireEventType(String eventType)
    {
        if (!"ERROR".equals(eventType) && !"ESCALATION".equals(eventType))
        {
            throw new ServiceException("BPMN 事件类型不受支持", HttpStatus.BAD_REQUEST);
        }
    }

    /** @param value Long，待核验主键；@return void，非正数抛出 400。 */
    private void requirePositiveId(Long value)
    {
        if (value == null || value <= 0)
        {
            throw new ServiceException("BPMN 事件主键不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /** @param value String，可空文本；@return String，空白转 null。 */
    private String trimToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
