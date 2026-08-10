package com.ruoyi.web.controller.workflow;

import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.domain.dto.WorkflowRuntimeEventRequest;
import com.ruoyi.flowable.domain.dto.WorkflowCollaborationMessageRequest;
import com.ruoyi.flowable.service.process.WorkflowCollaborationMessageService;
import com.ruoyi.flowable.service.process.WorkflowRuntimeEventService;

/**
 * 使用 X-Integration-Token 独立认证的 Flowable 运行事件发布入口。
 */
@Validated
@RestController
@RequestMapping("/workflow/runtime-event")
public class WfRuntimeEventController extends BaseController
{
    private final WorkflowRuntimeEventService runtimeEventService;
    private final WorkflowCollaborationMessageService collaborationMessageService;

    /**
     * 创建运行事件发布 Controller。
     * @param runtimeEventService WorkflowRuntimeEventService，包含 Token 强认证的领域服务
     * @return void，构造后由 Spring 管理
     */
    public WfRuntimeEventController(WorkflowRuntimeEventService runtimeEventService)
    {
        this(runtimeEventService, null);
    }

    /**
     * 创建同时支持单实例运行事件和跨 Participant 协作消息的入口。
     * @param runtimeEventService WorkflowRuntimeEventService，既有消息/信号/ReceiveTask 入口
     * @param collaborationMessageService WorkflowCollaborationMessageService，协作消息可靠投递服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WfRuntimeEventController(WorkflowRuntimeEventService runtimeEventService,
            WorkflowCollaborationMessageService collaborationMessageService)
    {
        this.runtimeEventService = runtimeEventService;
        this.collaborationMessageService = collaborationMessageService;
    }

    /**
     * 唯一关联并消费一个 Flowable Message Catch 订阅。
     * @param token String，集成 Token，请求头缺失时由服务返回统一 401
     * @param request WorkflowRuntimeEventRequest，消息名、关联条件和白名单变量
     * @return AjaxResult，幂等处理结果
     */
    @Anonymous
    @PostMapping("/message")
    public AjaxResult message(@RequestHeader(value = "X-Integration-Token", required = false)
            String token, @Valid @RequestBody WorkflowRuntimeEventRequest request)
    {
        return success(runtimeEventService.publish(token, "MESSAGE", request));
    }

    /**
     * 唯一关联并消费一个 Flowable Signal Catch 订阅。
     * @param token String，集成 Token
     * @param request WorkflowRuntimeEventRequest，信号名、关联条件和白名单变量
     * @return AjaxResult，幂等处理结果
     */
    @Anonymous
    @PostMapping("/signal")
    public AjaxResult signal(@RequestHeader(value = "X-Integration-Token", required = false)
            String token, @Valid @RequestBody WorkflowRuntimeEventRequest request)
    {
        return success(runtimeEventService.publish(token, "SIGNAL", request));
    }

    /**
     * 唯一关联并触发一个 Flowable ReceiveTask 执行。
     * @param token String，集成 Token
     * @param request WorkflowRuntimeEventRequest，activityId、关联条件和白名单变量
     * @return AjaxResult，幂等处理结果
     */
    @Anonymous
    @PostMapping("/receive")
    public AjaxResult receive(@RequestHeader(value = "X-Integration-Token", required = false)
            String token, @Valid @RequestBody WorkflowRuntimeEventRequest request)
    {
        return success(runtimeEventService.publish(token, "RECEIVE", request));
    }

    /**
     * 认证并可靠投递一条跨流程协作消息；消息名必须对应目标流程的 Message Catch/ReceiveTask。
     * @param token String，X-Integration-Token 正文
     * @param request WorkflowCollaborationMessageRequest，目标流程、关联键和变量
     * @return AjaxResult，协作消息幂等台账视图
     */
    @Anonymous
    @PostMapping("/collaboration/message")
    public AjaxResult collaborationMessage(
            @RequestHeader(value = "X-Integration-Token", required = false) String token,
            @Valid @RequestBody WorkflowCollaborationMessageRequest request)
    {
        if (collaborationMessageService == null)
        {
            throw new IllegalStateException("协作消息服务未初始化");
        }
        return success(collaborationMessageService.publish(token, request));
    }

}
