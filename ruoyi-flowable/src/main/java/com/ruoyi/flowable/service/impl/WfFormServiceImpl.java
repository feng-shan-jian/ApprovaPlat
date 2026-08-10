package com.ruoyi.flowable.service.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.service.IWfFormService;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.WorkflowReferenceChecker;

/**
 * 可编辑工作流表单模板业务服务实现。
 */
@Service
public class WfFormServiceImpl implements IWfFormService
{
    static final String NOT_FOUND_MESSAGE = "流程表单不存在或已删除";
    static final String REFERENCED_MESSAGE = "流程表单已被模型、流程定义或部署快照引用，不能删除";
    static final String CONCURRENT_CHANGE_MESSAGE = "流程表单状态已变化，请刷新后重试";
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_REMARK_LENGTH = 255;
    private static final int MAX_AUDIT_USER_LENGTH = 64;
    private static final int MAX_EXPORT_LIMIT = 10001;
    private static final int MAX_DELETE_BATCH_SIZE = 1000;

    private final WfFormMapper formMapper;
    private final WorkflowReferenceChecker referenceChecker;
    private final WorkflowFormTemplateValidator templateValidator;

    /**
     * 创建表单模板业务服务。
     * @param formMapper WfFormMapper，表单模板数据访问层
     * @param referenceChecker WorkflowReferenceChecker，真实业务及引擎引用检查器
     * @param templateValidator WorkflowFormTemplateValidator，表单结构和安全白名单验证器
     * @return 构造函数，无返回值
     */
    public WfFormServiceImpl(WfFormMapper formMapper, WorkflowReferenceChecker referenceChecker,
            WorkflowFormTemplateValidator templateValidator)
    {
        this.formMapper = formMapper;
        this.referenceChecker = referenceChecker;
        this.templateValidator = templateValidator;
    }

    /**
     * 查询有效表单模板。
     * @param formId Long，表单主键
     * @return WfForm，有效表单；不存在时返回 null
     */
    @Override
    @Transactional(readOnly = true)
    public WfForm queryById(Long formId)
    {
        validatePositiveId(formId);
        return formMapper.selectById(formId);
    }

    /**
     * 查询有效表单模板列表。
     * @param filter WfForm，可选表单名称过滤条件
     * @return List&lt;WfForm&gt;，有效表单列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<WfForm> queryList(WfForm filter)
    {
        List<WfForm> forms = formMapper.selectList(filter);
        return forms == null ? Collections.emptyList() : forms;
    }

    /**
     * 查询不包含 content 的有界表单摘要，允许额外一行供 Controller 判定是否超量。
     * @param filter WfForm，可选表单名称过滤条件
     * @param limit int，服务端请求上限，范围 1..10001
     * @return List&lt;WfForm&gt;，不超过上限的有效表单摘要
     */
    @Override
    @Transactional(readOnly = true)
    public List<WfForm> querySummaryList(WfForm filter, int limit)
    {
        validateExportLimit(limit);
        List<WfForm> forms = formMapper.selectSummaryList(filter, limit);
        return forms == null ? Collections.emptyList() : forms;
    }

    /**
     * 校验名称、大小和 JSON 根节点后新增表单模板。
     * @param form WfForm，表单模板及创建审计信息
     * @return int，实际新增行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertForm(WfForm form)
    {
        validateAndNormalizeForm(form, false);
        int affectedRows = formMapper.insert(form);
        if (affectedRows != 1)
        {
            throw new ServiceException("新增流程表单失败", HttpStatus.ERROR);
        }
        return affectedRows;
    }

    /**
     * 校验资源存在及 JSON 内容后修改当前模板，已有部署快照不会被更新。
     * @param form WfForm，表单主键、模板内容及更新审计信息
     * @return int，实际修改行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateForm(WfForm form)
    {
        validateAndNormalizeForm(form, true);
        if (formMapper.selectById(form.getFormId()) == null)
        {
            throw new ServiceException(NOT_FOUND_MESSAGE, HttpStatus.NOT_FOUND);
        }
        int affectedRows = formMapper.update(form);
        if (affectedRows != 1)
        {
            throw new ServiceException(CONCURRENT_CHANGE_MESSAGE, HttpStatus.CONFLICT);
        }
        return affectedRows;
    }

    /**
     * 完成存在性、快照、模型、流程定义和并发数量检查后批量逻辑删除表单。
     * @param formIds Collection&lt;Long&gt;，待删除表单主键集合
     * @param updateBy String，来自认证上下文的可信操作人账号
     * @return int，实际逻辑删除行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteWithValidByIds(Collection<Long> formIds, String updateBy)
    {
        Set<Long> normalizedIds = normalizeIds(formIds);
        String normalizedUpdateBy = validateAuditUser(updateBy);
        if (formMapper.countActiveByIds(normalizedIds) != normalizedIds.size())
        {
            throw new ServiceException(NOT_FOUND_MESSAGE, HttpStatus.NOT_FOUND);
        }
        if (referenceChecker.hasFormReference(normalizedIds))
        {
            throw new ServiceException(REFERENCED_MESSAGE, HttpStatus.CONFLICT);
        }

        int affectedRows = formMapper.logicalDelete(normalizedIds, normalizedUpdateBy);
        if (affectedRows != normalizedIds.size())
        {
            throw new ServiceException(CONCURRENT_CHANGE_MESSAGE, HttpStatus.CONFLICT);
        }
        return affectedRows;
    }

    /**
     * 校验并规范化表单写入字段。
     * @param form WfForm，待校验表单模板
     * @param requireId boolean，是否要求有效表单主键
     * @return void，校验失败时抛出业务异常
     */
    private void validateAndNormalizeForm(WfForm form, boolean requireId)
    {
        if (form == null)
        {
            throw new ServiceException("流程表单不能为空", HttpStatus.BAD_REQUEST);
        }
        if (requireId)
        {
            validatePositiveId(form.getFormId());
        }
        String formName = normalizeRequiredText(form.getFormName(), "流程表单名称", MAX_NAME_LENGTH);
        if (form.getRemark() != null && form.getRemark().length() > MAX_REMARK_LENGTH)
        {
            throw new ServiceException("流程表单备注长度不能超过255个字符", HttpStatus.BAD_REQUEST);
        }
        templateValidator.validate(form.getContent());
        form.setFormName(formName);
    }

    /**
     * 校验正数表单主键。
     * @param formId Long，待校验表单主键
     * @return void，校验失败时抛出业务异常
     */
    private void validatePositiveId(Long formId)
    {
        if (formId == null || formId <= 0)
        {
            throw new ServiceException("流程表单主键必须为正数", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 校验、去重并保持批量表单主键的输入顺序。
     * @param formIds Collection&lt;Long&gt;，原始表单主键集合
     * @return Set&lt;Long&gt;，合法且去重后的表单主键集合
     */
    private Set<Long> normalizeIds(Collection<Long> formIds)
    {
        if (formIds == null || formIds.isEmpty())
        {
            throw new ServiceException("待删除流程表单不能为空", HttpStatus.BAD_REQUEST);
        }
        // 在构造 IN 条件和引擎引用查询前限制原始请求规模，避免重复 ID 绕过事务负载门禁。
        if (formIds.size() > MAX_DELETE_BATCH_SIZE)
        {
            throw new ServiceException("单次最多删除1000个流程表单", HttpStatus.BAD_REQUEST);
        }
        Set<Long> normalized = new LinkedHashSet<>();
        for (Long formId : formIds)
        {
            validatePositiveId(formId);
            normalized.add(formId);
        }
        return normalized;
    }

    /**
     * 校验逻辑删除操作人，避免写入不可审计记录。
     * @param updateBy String，来自认证上下文的操作人账号
     * @return String，去除首尾空白后的操作人账号
     */
    private String validateAuditUser(String updateBy)
    {
        return normalizeRequiredText(updateBy, "更新操作人", MAX_AUDIT_USER_LENGTH);
    }

    /**
     * 校验表单摘要查询上限，禁止调用方把大字段导出改造成无界查询。
     * @param limit int，服务端请求的最大返回行数
     * @return void，超出 1..10001 时抛出 400 业务异常
     */
    private void validateExportLimit(int limit)
    {
        if (limit < 1 || limit > MAX_EXPORT_LIMIT)
        {
            throw new ServiceException("流程表单导出上限必须在1到10001之间", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 规范化必填文本并校验最大长度。
     * @param value String，原始文本
     * @param fieldName String，稳定错误提示使用的字段名称
     * @param maxLength int，允许的最大字符数
     * @return String，去除首尾空白后的文本
     */
    private String normalizeRequiredText(String value, String fieldName, int maxLength)
    {
        if (value == null || value.isBlank())
        {
            throw new ServiceException(fieldName + "不能为空", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength)
        {
            throw new ServiceException(fieldName + "长度不能超过" + maxLength + "个字符",
                    HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }
}
