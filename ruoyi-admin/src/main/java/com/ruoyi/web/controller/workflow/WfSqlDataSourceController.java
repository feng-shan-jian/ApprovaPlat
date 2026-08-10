package com.ruoyi.web.controller.workflow;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowSqlDataSourceRequest;
import com.ruoyi.flowable.domain.dto.WorkflowSqlDataSourceStatusRequest;
import com.ruoyi.flowable.service.model.WorkflowSqlDataSourceService;

/**
 * SQL 连接器受控数据源目录与不可回退修订管理接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/sql-datasource")
public class WfSqlDataSourceController extends BaseController
{
    private final WorkflowSqlDataSourceService dataSourceService;

    /**
     * 创建 SQL 数据源 Controller。
     * @param dataSourceService WorkflowSqlDataSourceService，数据源目录领域服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WfSqlDataSourceController(WorkflowSqlDataSourceService dataSourceService)
    {
        this.dataSourceService = dataSourceService;
    }

    /**
     * 查询全部数据源管理清单。
     * @return AjaxResult，不包含凭据正文的真实目录
     */
    @PreAuthorize("@ss.hasPermi('workflow:sqlDatasource:list')")
    @GetMapping("/list")
    public AjaxResult list()
    {
        return success(dataSourceService.list());
    }

    /**
     * 查询设计器可选择的已启用数据源。
     * @return AjaxResult，已启用目录选项
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:sqlDatasource:list,workflow:model:designer')")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(dataSourceService.listOptions());
    }

    /**
     * 创建数据源目录修订 1。
     * @param request WorkflowSqlDataSourceRequest，逻辑连接和表白名单
     * @return AjaxResult，生成的数据源主键
     */
    @PreAuthorize("@ss.hasPermi('workflow:sqlDatasource:add')")
    @Log(title = "SQL 连接器数据源", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public AjaxResult create(@Valid @RequestBody WorkflowSqlDataSourceRequest request)
    {
        return success(Map.of("dataSourceId", dataSourceService.create(request)));
    }

    /**
     * 发布数据源下一不可回退修订。
     * @param dataSourceId Long，数据源主键
     * @param request WorkflowSqlDataSourceRequest，新修订配置
     * @return AjaxResult，新修订号
     */
    @PreAuthorize("@ss.hasPermi('workflow:sqlDatasource:edit')")
    @Log(title = "SQL 连接器数据源修订", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{dataSourceId}")
    public AjaxResult update(@PathVariable @Positive Long dataSourceId,
            @Valid @RequestBody WorkflowSqlDataSourceRequest request)
    {
        return success(Map.of("revisionNo", dataSourceService.update(dataSourceId, request)));
    }

    /**
     * 启用或停用数据源，历史部署仍使用冻结快照。
     * @param dataSourceId Long，数据源主键
     * @param request WorkflowSqlDataSourceStatusRequest，目标状态
     * @return AjaxResult，成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:sqlDatasource:edit')")
    @Log(title = "SQL 连接器数据源状态", businessType = BusinessType.UPDATE)
    @PutMapping("/{dataSourceId}/status")
    public AjaxResult status(@PathVariable @Positive Long dataSourceId,
            @Valid @RequestBody WorkflowSqlDataSourceStatusRequest request)
    {
        dataSourceService.changeStatus(dataSourceId, request.enabled());
        return success();
    }
}
