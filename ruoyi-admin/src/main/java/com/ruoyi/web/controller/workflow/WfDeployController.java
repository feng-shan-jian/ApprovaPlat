package com.ruoyi.web.controller.workflow;

import java.util.Arrays;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowDeploymentQueryDto;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;

/**
 * Flowable 8 流程定义和部署管理接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/deploy")
public class WfDeployController extends BaseController
{
    /** Flowable 查询单页上限，与领域服务门禁保持一致。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final WorkflowDeploymentService deploymentService;

    /**
     * 创建流程部署 Controller。
     *
     * @param deploymentService WorkflowDeploymentService，流程定义与部署业务服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfDeployController(WorkflowDeploymentService deploymentService)
    {
        this.deploymentService = deploymentService;
    }

    /**
     * 查询每个流程标识的最新流程定义。
     *
     * @param filter WorkflowDeploymentQueryDto，定义名称、标识、分类和状态过滤条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，最新流程定义分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:deploy:list')")
    @GetMapping("/list")
    public TableDataInfo list(WorkflowDeploymentQueryDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(deploymentService.listLatest(filter, pageNum, pageSize));
    }

    /**
     * 查询指定流程标识的全部已发布版本。
     *
     * @param processKey String，流程定义标识
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，流程发布版本分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:deploy:list')")
    @GetMapping("/publishList")
    public TableDataInfo publishList(
            @RequestParam @NotBlank(message = "流程标识不能为空") String processKey,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(deploymentService.publishList(processKey, pageNum, pageSize));
    }

    /**
     * 激活或挂起流程定义及其运行实例。
     *
     * @param state String，只接受 active 或 suspended
     * @param definitionId String，Flowable 流程定义主键
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:deploy:state')")
    @Log(title = "流程定义状态", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/changeState")
    public AjaxResult changeState(
            @RequestParam @NotBlank(message = "流程定义状态不能为空") String state,
            @RequestParam @NotBlank(message = "流程定义主键不能为空") String definitionId)
    {
        deploymentService.changeState(definitionId, state);
        return success();
    }

    /**
     * 读取并重新安全校验已部署 BPMN XML。
     *
     * @param definitionId String，Flowable 流程定义主键
     * @return AjaxResult，UTF-8 BPMN XML
     */
    @PreAuthorize("@ss.hasPermi('workflow:deploy:query')")
    @GetMapping("/bpmnXml/{definitionId}")
    public AjaxResult getBpmnXml(
            @PathVariable @NotBlank(message = "流程定义主键不能为空") String definitionId)
    {
        // String 同时匹配成功消息重载，必须显式作为 data 返回，保证部署详情与模型/流程 XML 契约一致。
        return success((Object) deploymentService.getBpmnXml(definitionId));
    }

    /**
     * 删除没有运行或历史实例引用的部署，禁止级联删除流程数据。
     *
     * @param deployIds String[]，待删除 Flowable 部署主键数组
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:deploy:remove')")
    @Log(title = "删除流程部署", businessType = BusinessType.DELETE)
    @DeleteMapping("/{deployIds}")
    public AjaxResult remove(
            @PathVariable @NotEmpty(message = "部署主键不能为空")
            @Size(max = 100, message = "单次最多删除100个部署") String[] deployIds)
    {
        deploymentService.deleteDeployments(Arrays.asList(deployIds));
        return success();
    }

}
