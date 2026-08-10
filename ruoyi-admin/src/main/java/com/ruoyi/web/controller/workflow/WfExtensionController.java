package com.ruoyi.web.controller.workflow;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.ruoyi.flowable.domain.dto.WorkflowExtensionCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowExtensionStatusRequest;
import com.ruoyi.flowable.domain.dto.WorkflowExtensionVersionCreateRequest;
import com.ruoyi.flowable.service.model.WorkflowExtensionRegistryService;

/**
 * BPMN 受控扩展目录与不可变版本管理接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/extension")
public class WfExtensionController extends BaseController
{
    private final WorkflowExtensionRegistryService extensionService;

    /**
     * 创建扩展目录 Controller。
     * @param extensionService WorkflowExtensionRegistryService，扩展注册表领域服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WfExtensionController(WorkflowExtensionRegistryService extensionService)
    {
        this.extensionService = extensionService;
    }

    /**
     * 查询设计器可选择的已启用 Java 扩展最新版。
     * @return AjaxResult，真实数据库扩展选项
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')")
    @GetMapping("/options/java")
    public AjaxResult javaOptions()
    {
        return success(extensionService.listJavaOptions());
    }

    /**
     * 查询设计器可选择的已启用 CEL 扩展最新版。
     * @return AjaxResult，真实数据库 CEL 扩展选项
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')")
    @GetMapping("/options/cel")
    public AjaxResult celOptions()
    {
        return success(extensionService.listCelOptions());
    }

    /**
     * 查询设计器可选择的已启用 HTTP 扩展最新版。
     * @return AjaxResult，真实数据库 HTTP 扩展选项
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')")
    @GetMapping("/options/http")
    public AjaxResult httpOptions()
    {
        return success(extensionService.listHttpOptions());
    }

    /**
     * 查询设计器可选择的已启用 SQL 扩展最新版。
     * @return AjaxResult，真实数据库 SQL 扩展选项
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')")
    @GetMapping("/options/sql")
    public AjaxResult sqlOptions()
    {
        return success(extensionService.listSqlOptions());
    }

    /**
     * 查询设计器可选择的已启用自定义表单字段最新版。
     * @return AjaxResult，真实数据库 FORM_FIELD 扩展选项
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')")
    @GetMapping("/options/form-field")
    public AjaxResult formFieldOptions()
    {
        return success(extensionService.listFormFieldOptions());
    }

    /**
     * 查询扩展管理清单，包含停用和尚无版本目录。
     * @return AjaxResult，全部真实扩展目录及可选最新版
     */
    @PreAuthorize("@ss.hasPermi('workflow:extension:list')")
    @GetMapping("/list")
    public AjaxResult list()
    {
        return success(extensionService.listManagement());
    }

    /**
     * 查询服务端代码实际安装的 Java 处理器清单。
     * @return AjaxResult，处理器稳定键、名称和配置 Schema
     */
    @PreAuthorize("@ss.hasPermi('workflow:extension:list')")
    @GetMapping("/installed-handlers/java")
    public AjaxResult installedJavaHandlers()
    {
        return success(extensionService.listInstalledJavaHandlers());
    }

    /**
     * 创建受控扩展目录。
     * @param request WorkflowExtensionCreateRequest，目录业务字段
     * @return AjaxResult，包含数据库生成 extensionId
     */
    @PreAuthorize("@ss.hasPermi('workflow:extension:add')")
    @Log(title = "BPMN 扩展目录", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public AjaxResult create(@Valid @RequestBody WorkflowExtensionCreateRequest request)
    {
        return success(Map.of("extensionId", extensionService.createExtension(request)));
    }

    /**
     * 发布扩展不可变新版本。
     * @param extensionId Long，扩展目录主键
     * @param request WorkflowExtensionVersionCreateRequest，已安装处理器稳定键
     * @return AjaxResult，包含数据库生成 versionId
     */
    @PreAuthorize("@ss.hasPermi('workflow:extension:version:add')")
    @Log(title = "BPMN 扩展版本", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/{extensionId}/versions")
    public AjaxResult createVersion(
            @PathVariable @Positive(message = "扩展主键必须为正数") Long extensionId,
            @Valid @RequestBody WorkflowExtensionVersionCreateRequest request)
    {
        return success(Map.of("versionId", extensionService.createVersion(extensionId, request)));
    }

    /**
     * 启用或停用扩展目录，历史部署快照不受影响。
     * @param extensionId Long，扩展目录主键
     * @param request WorkflowExtensionStatusRequest，目标启停状态
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:extension:edit')")
    @Log(title = "BPMN 扩展目录状态", businessType = BusinessType.UPDATE)
    @PutMapping("/{extensionId}/status")
    public AjaxResult changeStatus(
            @PathVariable @Positive(message = "扩展主键必须为正数") Long extensionId,
            @Valid @RequestBody WorkflowExtensionStatusRequest request)
    {
        extensionService.changeStatus(extensionId, request.enabled());
        return success();
    }

    /**
     * 删除已停用且未被部署快照引用的非内置扩展目录。
     * @param extensionId Long，扩展目录主键
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:extension:remove')")
    @Log(title = "BPMN 扩展目录", businessType = BusinessType.DELETE)
    @DeleteMapping("/{extensionId}")
    public AjaxResult remove(
            @PathVariable @Positive(message = "扩展主键必须为正数") Long extensionId)
    {
        extensionService.removeExtension(extensionId);
        return success();
    }
}
