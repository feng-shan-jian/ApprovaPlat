package com.ruoyi.flowable.service.identity;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowIdentityOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.identity.WorkflowIdentityOptionType;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;

/**
 * 从若依正式用户、角色和部门表提供工作流专用最小身份目录。
 */
@Service
public class WorkflowIdentityDirectoryService
{
    /** 单次身份选项查询上限，防止设计器加载完整组织数据。 */
    public static final int MAX_PAGE_SIZE = 200;

    /** 身份目录模糊检索词上限。 */
    public static final int MAX_KEYWORD_LENGTH = 64;

    /** 允许的最大页码，避免异常输入造成数据库偏移量溢出。 */
    public static final int MAX_PAGE_NUMBER = 1_000_000;

    /** 仅返回具备直接办理完整资格用户的目录能力值。 */
    public static final String APPROVAL_CAPABILITY = "approval";

    /** 仅返回可走通候选认领及后续办理路径身份的目录能力值。 */
    public static final String CLAIM_CAPABILITY = "claim";

    private final WorkflowIdentityMapper identityMapper;

    /**
     * 创建工作流身份目录服务。
     *
     * @param identityMapper WorkflowIdentityMapper，若依正式身份主数据查询 Mapper
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowIdentityDirectoryService(WorkflowIdentityMapper identityMapper)
    {
        this.identityMapper = identityMapper;
    }

    /**
     * 分页查询启用且未删除的工作流身份选项。
     *
     * @param typeValue String，user、role 或 dept
     * @param keyword String，可为空的名称、账号或编码检索词
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数，上限 200
     * @return WorkflowPageResult&lt;WorkflowIdentityOptionView&gt;，最小身份分页结果
     */
    public WorkflowPageResult<WorkflowIdentityOptionView> listOptions(String typeValue,
            String keyword, int pageNum, int pageSize)
    {
        return listOptions(typeValue, keyword, pageNum, pageSize, null);
    }

    /**
     * 按可选业务能力分页查询工作流身份；办理和认领能力均由正式 RBAC 聚合结果决定。
     *
     * @param typeValue String，user、role 或 dept
     * @param keyword String，可为空的名称、账号或编码检索词
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数，上限 200
     * @param capability String，可为空；approval 查询直接办理用户，claim 查询候选用户或候选组
     * @return WorkflowPageResult&lt;WorkflowIdentityOptionView&gt;，最小身份分页结果
     */
    public WorkflowPageResult<WorkflowIdentityOptionView> listOptions(String typeValue,
            String keyword, int pageNum, int pageSize, String capability)
    {
        validatePagination(pageNum, pageSize);
        WorkflowIdentityOptionType type = WorkflowIdentityOptionType.fromValue(typeValue);
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedCapability = normalizeCapability(type, capability);
        long total;
        if (APPROVAL_CAPABILITY.equals(normalizedCapability))
        {
            total = identityMapper.countApprovalEligibleUserOptions(normalizedKeyword);
        }
        else if (CLAIM_CAPABILITY.equals(normalizedCapability))
        {
            total = identityMapper.countClaimEligibleIdentityOptions(
                    type.value(), normalizedKeyword);
        }
        else
        {
            total = identityMapper.countActiveIdentityOptions(type.value(), normalizedKeyword);
        }
        if (total == 0L)
        {
            return new WorkflowPageResult<>(List.of(), 0L);
        }

        // 使用 long 计算偏移量，避免较大合法页码在进入 MySQL 前发生 int 溢出。
        long offset = (long) (pageNum - 1) * pageSize;
        List<WorkflowIdentityOptionView> rows;
        if (APPROVAL_CAPABILITY.equals(normalizedCapability))
        {
            rows = identityMapper.selectApprovalEligibleUserOptions(
                    normalizedKeyword, offset, pageSize);
        }
        else if (CLAIM_CAPABILITY.equals(normalizedCapability))
        {
            rows = identityMapper.selectClaimEligibleIdentityOptions(
                    type.value(), normalizedKeyword, offset, pageSize);
        }
        else
        {
            rows = identityMapper.selectActiveIdentityOptions(
                    type.value(), normalizedKeyword, offset, pageSize);
        }
        if (rows == null || rows.size() > pageSize)
        {
            throw new ServiceException("工作流身份主数据查询结果异常", HttpStatus.ERROR);
        }
        return new WorkflowPageResult<>(rows, total);
    }

    /**
     * 规范身份目录业务能力并校验适用类型，避免调用方把候选组降级到通用有效目录。
     *
     * @param type WorkflowIdentityOptionType，已规范化的身份类型
     * @param capability String，客户端提交的可选业务能力
     * @return String，null、approval 或 claim 规范能力值
     */
    private String normalizeCapability(WorkflowIdentityOptionType type,
            String capability)
    {
        if (capability == null || capability.isBlank())
        {
            return null;
        }
        String normalizedCapability = capability.trim();
        if (APPROVAL_CAPABILITY.equals(normalizedCapability))
        {
            if (type == WorkflowIdentityOptionType.USER)
            {
                return normalizedCapability;
            }
        }
        else if (CLAIM_CAPABILITY.equals(normalizedCapability))
        {
            return normalizedCapability;
        }
        if (!APPROVAL_CAPABILITY.equals(normalizedCapability)
                && !CLAIM_CAPABILITY.equals(normalizedCapability))
        {
            throw invalidCapability();
        }
        throw invalidCapability();
    }

    /**
     * 创建稳定的身份目录能力参数异常。
     *
     * @return ServiceException，HTTP 400 能力或身份类型组合非法
     */
    private ServiceException invalidCapability()
    {
        return new ServiceException("工作流身份目录能力参数不合法",
                HttpStatus.BAD_REQUEST);
    }

    /**
     * 校验服务层分页边界，保证非 Controller 调用也不能绕过资源限制。
     *
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return 无返回值，非法分页参数抛出 400 业务异常
     */
    private void validatePagination(int pageNum, int pageSize)
    {
        if (pageNum < 1 || pageNum > MAX_PAGE_NUMBER || pageSize < 1 || pageSize > MAX_PAGE_SIZE)
        {
            throw new ServiceException("工作流身份分页参数不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 规范身份检索词并限制长度，空白文本按无过滤条件处理。
     *
     * @param keyword String，原始身份检索词
     * @return String，去除首尾空白后的检索词；无内容时为 null
     */
    private String normalizeKeyword(String keyword)
    {
        if (keyword == null || keyword.isBlank())
        {
            return null;
        }
        String normalized = keyword.trim();
        if (normalized.length() > MAX_KEYWORD_LENGTH)
        {
            throw new ServiceException("工作流身份检索词不能超过64个字符", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }
}
