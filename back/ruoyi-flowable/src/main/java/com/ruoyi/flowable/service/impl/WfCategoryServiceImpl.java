package com.ruoyi.flowable.service.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.mapper.WfCategoryMapper;
import com.ruoyi.flowable.service.IWfCategoryService;
import com.ruoyi.flowable.service.WorkflowReferenceChecker;

/**
 * 工作流分类业务服务实现。
 */
@Service
public class WfCategoryServiceImpl implements IWfCategoryService
{
    static final String DUPLICATE_CODE_MESSAGE = "流程分类编码已存在";
    static final String NOT_FOUND_MESSAGE = "流程分类不存在或已删除";
    static final String REFERENCED_MESSAGE = "流程分类已被模型或流程定义引用，不能删除";
    static final String CONCURRENT_CHANGE_MESSAGE = "流程分类状态已变化，请刷新后重试";

    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_CODE_LENGTH = 64;
    private static final int MAX_REMARK_LENGTH = 500;
    private static final int MAX_AUDIT_USER_LENGTH = 64;
    private static final int MAX_EXPORT_LIMIT = 10001;
    private static final int MAX_DELETE_BATCH_SIZE = 1000;

    private final WfCategoryMapper categoryMapper;
    private final WorkflowReferenceChecker referenceChecker;

    /**
     * 创建分类业务服务。
     * @param categoryMapper WfCategoryMapper，分类数据访问层
     * @param referenceChecker WorkflowReferenceChecker，真实引擎引用检查器
     * @return 构造函数，无返回值
     */
    public WfCategoryServiceImpl(WfCategoryMapper categoryMapper,
            WorkflowReferenceChecker referenceChecker)
    {
        this.categoryMapper = categoryMapper;
        this.referenceChecker = referenceChecker;
    }

    /**
     * 查询有效分类。
     * @param categoryId Long，分类主键
     * @return WfCategory，有效分类；不存在时返回 null
     */
    @Override
    @Transactional(readOnly = true)
    public WfCategory queryById(Long categoryId)
    {
        validatePositiveId(categoryId);
        return categoryMapper.selectById(categoryId);
    }

    /**
     * 查询有效分类列表。
     * @param filter WfCategory，可选名称和编码过滤条件
     * @return List&lt;WfCategory&gt;，有效分类列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<WfCategory> queryList(WfCategory filter)
    {
        List<WfCategory> categories = categoryMapper.selectList(filter);
        return categories == null ? Collections.emptyList() : categories;
    }

    /**
     * 查询有界分类导出数据，允许额外一行供 Controller 判定是否超量。
     * @param filter WfCategory，可选名称和编码过滤条件
     * @param limit int，服务端请求上限，范围 1..10001
     * @return List&lt;WfCategory&gt;，不超过上限的有效分类列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<WfCategory> queryExportList(WfCategory filter, int limit)
    {
        validateExportLimit(limit);
        List<WfCategory> categories = categoryMapper.selectExportList(filter, limit);
        return categories == null ? Collections.emptyList() : categories;
    }

    /**
     * 校验唯一性并新增分类，数据库唯一键作为并发场景最终门禁。
     * @param category WfCategory，分类及创建审计信息
     * @return int，实际新增行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertCategory(WfCategory category)
    {
        validateAndNormalizeCategory(category, false);
        if (!checkCategoryCodeUnique(category))
        {
            throw new ServiceException(DUPLICATE_CODE_MESSAGE, HttpStatus.CONFLICT);
        }
        try
        {
            int affectedRows = categoryMapper.insert(category);
            if (affectedRows != 1)
            {
                throw new ServiceException("新增流程分类失败", HttpStatus.ERROR);
            }
            return affectedRows;
        }
        catch (DuplicateKeyException exception)
        {
            throw duplicateCodeException(exception);
        }
    }

    /**
     * 校验资源存在及编码唯一性后修改分类。
     * @param category WfCategory，分类主键、修改内容及更新审计信息
     * @return int，实际修改行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateCategory(WfCategory category)
    {
        validateAndNormalizeCategory(category, true);
        if (categoryMapper.selectById(category.getCategoryId()) == null)
        {
            throw new ServiceException(NOT_FOUND_MESSAGE, HttpStatus.NOT_FOUND);
        }
        if (!checkCategoryCodeUnique(category))
        {
            throw new ServiceException(DUPLICATE_CODE_MESSAGE, HttpStatus.CONFLICT);
        }
        try
        {
            int affectedRows = categoryMapper.update(category);
            if (affectedRows != 1)
            {
                throw new ServiceException(CONCURRENT_CHANGE_MESSAGE, HttpStatus.CONFLICT);
            }
            return affectedRows;
        }
        catch (DuplicateKeyException exception)
        {
            throw duplicateCodeException(exception);
        }
    }

    /**
     * 完成存在性、引擎引用和并发数量检查后批量逻辑删除分类。
     * @param categoryIds Collection&lt;Long&gt;，待删除分类主键集合
     * @param updateBy String，来自认证上下文的可信操作人账号
     * @return int，实际逻辑删除行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteWithValidByIds(Collection<Long> categoryIds, String updateBy)
    {
        Set<Long> normalizedIds = normalizeIds(categoryIds);
        String normalizedUpdateBy = validateAuditUser(updateBy);
        if (categoryMapper.countActiveByIds(normalizedIds) != normalizedIds.size())
        {
            throw new ServiceException(NOT_FOUND_MESSAGE, HttpStatus.NOT_FOUND);
        }

        for (Long categoryId : normalizedIds)
        {
            WfCategory category = categoryMapper.selectById(categoryId);
            if (category == null)
            {
                throw new ServiceException(CONCURRENT_CHANGE_MESSAGE, HttpStatus.CONFLICT);
            }
            if (referenceChecker.hasCategoryReference(category.getCode()))
            {
                throw new ServiceException(REFERENCED_MESSAGE, HttpStatus.CONFLICT);
            }
        }

        int affectedRows = categoryMapper.logicalDelete(normalizedIds, normalizedUpdateBy);
        if (affectedRows != normalizedIds.size())
        {
            throw new ServiceException(CONCURRENT_CHANGE_MESSAGE, HttpStatus.CONFLICT);
        }
        return affectedRows;
    }

    /**
     * 校验分类编码在有效记录中是否唯一；数据库唯一键继续覆盖并发和已删除记录冲突。
     * @param category WfCategory，分类主键和待校验编码
     * @return boolean，唯一返回 true，否则返回 false
     */
    @Override
    @Transactional(readOnly = true)
    public boolean checkCategoryCodeUnique(WfCategory category)
    {
        if (category == null || category.getCode() == null || category.getCode().isBlank())
        {
            return false;
        }
        WfCategory existing = categoryMapper.selectByCode(category.getCode().trim());
        return existing == null || Objects.equals(existing.getCategoryId(), category.getCategoryId());
    }

    /**
     * 校验并规范化分类写入字段。
     * @param category WfCategory，待校验分类
     * @param requireId boolean，是否要求有效分类主键
     * @return void，校验失败时抛出业务异常
     */
    private void validateAndNormalizeCategory(WfCategory category, boolean requireId)
    {
        if (category == null)
        {
            throw new ServiceException("流程分类不能为空", HttpStatus.BAD_REQUEST);
        }
        if (requireId)
        {
            validatePositiveId(category.getCategoryId());
        }
        String categoryName = normalizeRequiredText(category.getCategoryName(), "流程分类名称",
                MAX_NAME_LENGTH);
        String code = normalizeRequiredText(category.getCode(), "流程分类编码", MAX_CODE_LENGTH);
        if (category.getRemark() != null && category.getRemark().length() > MAX_REMARK_LENGTH)
        {
            throw new ServiceException("流程分类备注长度不能超过500个字符", HttpStatus.BAD_REQUEST);
        }
        category.setCategoryName(categoryName);
        category.setCode(code);
    }

    /**
     * 校验正数分类主键。
     * @param categoryId Long，待校验分类主键
     * @return void，校验失败时抛出业务异常
     */
    private void validatePositiveId(Long categoryId)
    {
        if (categoryId == null || categoryId <= 0)
        {
            throw new ServiceException("流程分类主键必须为正数", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 校验、去重并保持批量分类主键的输入顺序。
     * @param categoryIds Collection&lt;Long&gt;，原始分类主键集合
     * @return Set&lt;Long&gt;，合法且去重后的分类主键集合
     */
    private Set<Long> normalizeIds(Collection<Long> categoryIds)
    {
        if (categoryIds == null || categoryIds.isEmpty())
        {
            throw new ServiceException("待删除流程分类不能为空", HttpStatus.BAD_REQUEST);
        }
        // 在构造 IN 条件和逐项查询引用前限制原始请求规模，避免重复 ID 绕过事务负载门禁。
        if (categoryIds.size() > MAX_DELETE_BATCH_SIZE)
        {
            throw new ServiceException("单次最多删除1000个流程分类", HttpStatus.BAD_REQUEST);
        }
        Set<Long> normalized = new LinkedHashSet<>();
        for (Long categoryId : categoryIds)
        {
            validatePositiveId(categoryId);
            normalized.add(categoryId);
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
     * 校验分类导出查询上限，禁止调用方发起无界查询。
     * @param limit int，服务端请求的最大返回行数
     * @return void，超出 1..10001 时抛出 400 业务异常
     */
    private void validateExportLimit(int limit)
    {
        if (limit < 1 || limit > MAX_EXPORT_LIMIT)
        {
            throw new ServiceException("流程分类导出上限必须在1到10001之间", HttpStatus.BAD_REQUEST);
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

    /**
     * 将数据库唯一键异常转换为稳定的 409 业务异常。
     * @param cause DuplicateKeyException，数据库唯一键冲突
     * @return ServiceException，对外稳定的分类编码冲突异常
     */
    private ServiceException duplicateCodeException(DuplicateKeyException cause)
    {
        ServiceException exception = new ServiceException(DUPLICATE_CODE_MESSAGE, HttpStatus.CONFLICT);
        exception.initCause(cause);
        return exception;
    }
}
