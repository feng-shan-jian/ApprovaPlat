package com.ruoyi.flowable.service.identity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowIdentityOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowIdentitySelectionView;
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

    /** 仅返回可读取抄送工作台和流程详情的身份目录能力值。 */
    public static final String COPY_CAPABILITY = "copy";

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
     * @param capability String，可为空；approval 查询直接办理用户，claim 查询候选用户或候选组，
     *        copy 查询具备抄送列表和流程详情权限的用户、角色或部门
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
        else if (COPY_CAPABILITY.equals(normalizedCapability))
        {
            total = identityMapper.countCopyEligibleIdentityOptions(
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
        else if (COPY_CAPABILITY.equals(normalizedCapability))
        {
            rows = identityMapper.selectCopyEligibleIdentityOptions(
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
     * 批量回显作者 BPMN 已保存的身份对象，并标明实时启用和业务资格状态。
     *
     * @param typeValue String，user、role 或 dept
     * @param capability String，可为空；approval 或 claim
     * @param values List&lt;String&gt;，用户数字主键或 ROLE/DEPT 受控目录值
     * @return List&lt;WorkflowIdentitySelectionView&gt;，保持请求顺序且不暴露已删除对象主键的显示结果
     */
    public List<WorkflowIdentitySelectionView> resolveSelections(String typeValue,
            String capability, List<String> values)
    {
        WorkflowIdentityOptionType type = WorkflowIdentityOptionType.fromValue(typeValue);
        String normalizedCapability = normalizeCapability(type, capability);
        if (values == null || values.isEmpty() || values.size() > MAX_PAGE_SIZE)
        {
            throw new ServiceException("已选身份数量不合法", HttpStatus.BAD_REQUEST);
        }

        // selections 同时保留作者选择顺序和可用于正式目录查询的正整数主键。
        LinkedHashMap<String, Long> selections = new LinkedHashMap<>();
        for (String value : values)
        {
            String normalizedValue = normalizeSelectionValue(type, value);
            selections.putIfAbsent(normalizedValue,
                    selectionId(type, normalizedValue));
        }
        List<Long> ids = List.copyOf(new LinkedHashSet<>(selections.values()));
        List<WorkflowIdentitySelectionView> directoryRows =
                identityMapper.selectIdentitySelectionsByIds(type.value(), ids);
        if (directoryRows == null || directoryRows.size() > ids.size())
        {
            throw new ServiceException("工作流已选身份查询结果异常", HttpStatus.ERROR);
        }

        Set<Long> availableIds = eligibleSelectionIds(type, normalizedCapability, ids);
        Map<String, WorkflowIdentitySelectionView> rowsByValue = indexSelectionRows(
                type, directoryRows, selections.keySet());
        List<WorkflowIdentitySelectionView> result = new ArrayList<>(selections.size());
        for (Map.Entry<String, Long> selection : selections.entrySet())
        {
            WorkflowIdentitySelectionView row = rowsByValue.get(selection.getKey());
            if (row == null)
            {
                // 物理删除对象不能回显裸主键，仍保留内部 value 供设计者删除或替换旧配置。
                result.add(new WorkflowIdentitySelectionView(selection.getKey(),
                        deletedSelectionLabel(type), type.value(), false));
                continue;
            }
            boolean available = row.available() && availableIds.contains(selection.getValue());
            String label = available ? row.label() : row.label() + "（已停用或无当前资格）";
            result.add(new WorkflowIdentitySelectionView(
                    row.value(), label, row.type(), available));
        }
        return List.copyOf(result);
    }

    /**
     * 按目录能力查询当前仍可用于新规则的身份主键。
     *
     * @param type WorkflowIdentityOptionType，已规范身份类型
     * @param capability String，null、approval 或 claim
     * @param ids List&lt;Long&gt;，待核验主键
     * @return Set&lt;Long&gt;，有效且满足对应办理资格的主键集合
     */
    private Set<Long> eligibleSelectionIds(WorkflowIdentityOptionType type,
            String capability, List<Long> ids)
    {
        List<Long> eligible;
        if (APPROVAL_CAPABILITY.equals(capability))
        {
            eligible = identityMapper.selectApprovalEligibleUserIdsByUserIds(ids);
        }
        else if (CLAIM_CAPABILITY.equals(capability))
        {
            eligible = switch (type)
            {
                case USER -> identityMapper.selectClaimEligibleUserIdsByUserIds(ids);
                case ROLE -> identityMapper.selectClaimEligibleRoleIdsByRoleIds(ids);
                case DEPT -> identityMapper.selectClaimEligibleDeptIdsByDeptIds(ids);
            };
        }
        else
        {
            eligible = switch (type)
            {
                case USER -> identityMapper.selectActiveUserIdsByUserIds(ids);
                case ROLE -> identityMapper.selectActiveRoleIdsByRoleIds(ids);
                case DEPT -> identityMapper.selectActiveDeptIdsByDeptIds(ids);
            };
        }
        if (eligible == null || eligible.stream().anyMatch(
                id -> id == null || id <= 0 || !ids.contains(id)))
        {
            throw new ServiceException("工作流身份资格查询结果异常", HttpStatus.ERROR);
        }
        return Set.copyOf(new HashSet<>(eligible));
    }

    /**
     * 校验 Mapper 回显行并按受控目录值建立唯一索引。
     *
     * @param type WorkflowIdentityOptionType，请求身份类型
     * @param rows List&lt;WorkflowIdentitySelectionView&gt;，正式目录查询结果
     * @param expectedValues Set&lt;String&gt;，作者请求的规范目录值
     * @return Map&lt;String,WorkflowIdentitySelectionView&gt;，可按作者值精确回显的目录行
     */
    private Map<String, WorkflowIdentitySelectionView> indexSelectionRows(
            WorkflowIdentityOptionType type, List<WorkflowIdentitySelectionView> rows,
            Set<String> expectedValues)
    {
        Map<String, WorkflowIdentitySelectionView> indexed = new HashMap<>();
        for (WorkflowIdentitySelectionView row : rows)
        {
            if (row == null || !type.value().equals(row.type())
                    || row.value() == null || !expectedValues.contains(row.value())
                    || row.label() == null || row.label().isBlank()
                    || indexed.put(row.value(), row) != null)
            {
                throw new ServiceException("工作流已选身份主数据异常", HttpStatus.ERROR);
            }
        }
        return Map.copyOf(indexed);
    }

    /**
     * 将客户端已保存值规范为与身份类型一致的受控目录值。
     *
     * @param type WorkflowIdentityOptionType，目标身份类型
     * @param value String，作者 BPMN 中的已保存值
     * @return String，用户数字主键或 ROLE/DEPT 前缀值
     */
    private String normalizeSelectionValue(WorkflowIdentityOptionType type, String value)
    {
        String normalized = value == null ? "" : value.trim();
        String pattern = switch (type)
        {
            case USER -> "[1-9][0-9]{0,18}";
            case ROLE -> "ROLE[1-9][0-9]{0,18}";
            case DEPT -> "DEPT[1-9][0-9]{0,18}";
        };
        if (!normalized.matches(pattern))
        {
            throw new ServiceException("已选身份值不合法", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 从规范目录值提取可查询的正整数主键。
     *
     * @param type WorkflowIdentityOptionType，身份类型
     * @param value String，已通过格式校验的受控目录值
     * @return long，正式身份表主键
     */
    private long selectionId(WorkflowIdentityOptionType type, String value)
    {
        String numeric = type == WorkflowIdentityOptionType.USER ? value : value.substring(4);
        try
        {
            return Long.parseLong(numeric);
        }
        catch (NumberFormatException exception)
        {
            throw new ServiceException("已选身份值不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 为已物理删除对象生成不包含主键的稳定中文说明。
     *
     * @param type WorkflowIdentityOptionType，身份类型
     * @return String，可直接显示在选择器中的不可用标签
     */
    private String deletedSelectionLabel(WorkflowIdentityOptionType type)
    {
        return switch (type)
        {
            case USER -> "已删除用户（不可用）";
            case ROLE -> "已删除角色（不可用）";
            case DEPT -> "已删除部门（不可用）";
        };
    }

    /**
     * 规范身份目录业务能力并校验适用类型，避免调用方把候选组降级到通用有效目录。
     *
     * @param type WorkflowIdentityOptionType，已规范化的身份类型
     * @param capability String，客户端提交的可选业务能力
     * @return String，null、approval、claim 或 copy 规范能力值
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
        else if (COPY_CAPABILITY.equals(normalizedCapability))
        {
            return normalizedCapability;
        }
        if (!APPROVAL_CAPABILITY.equals(normalizedCapability)
                && !CLAIM_CAPABILITY.equals(normalizedCapability)
                && !COPY_CAPABILITY.equals(normalizedCapability))
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
