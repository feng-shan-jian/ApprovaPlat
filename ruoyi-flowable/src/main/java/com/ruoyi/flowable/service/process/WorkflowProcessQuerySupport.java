package com.ruoyi.flowable.service.process;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.service.ISysUserService;

/**
 * 流程工作台查询共用的分页、参数、部署分类和页内姓名解析规则。
 *
 * 不承接列表编排或身份授权，姓名缓存由每次列表查询独立创建。
 */
final class WorkflowProcessQuerySupport
{
    /** 单页最大记录数，避免工作台查询被用作无界导出。 */
    static final int MAX_PAGE_SIZE = 200;

    /** 普通查询文本允许的最大字符数。 */
    private static final int MAX_FILTER_LENGTH = 255;

    /**
     * 禁止实例化无状态查询支持类。
     *
     * 该类型仅通过静态方法复用稳定查询契约
     */
    private WorkflowProcessQuerySupport()
    {
    }

    /**
     * 将业务分类编码解析为 Flowable 正式部署主键集合。
     *
     * @param category String，分类目录提交的业务分类编码，允许为空
     * @return Set&lt;String&gt;，null 表示未启用分类筛选；空集合表示分类下没有部署
     */
    static Set<String> resolveCategoryDeploymentIds(
            RepositoryService repositoryService, String category)
    {
        String normalizedCategory = optionalText(category, "流程分类过长");
        if (normalizedCategory == null)
        {
            return null;
        }
        List<Deployment> deployments = repositoryService.createDeploymentQuery()
                .deploymentCategory(normalizedCategory)
                .list();
        if (deployments == null)
        {
            throw dataError("流程分类部署查询结果异常");
        }
        // deploymentIds 是分类筛选最终写入定义/任务原生查询的正式部署范围。
        Set<String> deploymentIds = new LinkedHashSet<>();
        for (Deployment deployment : deployments)
        {
            if (deployment == null || !StringUtils.hasText(deployment.getId())
                    || !normalizedCategory.equals(deployment.getCategory())
                    || !deploymentIds.add(deployment.getId()))
            {
                throw dataError("流程分类部署数据异常");
            }
        }
        return Set.copyOf(deploymentIds);
    }
    /**
     * 规范正式部署中的业务分类，禁止 BPMN 命名空间 URI 进入页面。
     *
     * @param category String，发布事务写入 Deployment.category 的业务分类编码
     * @return String，规范后的正式分类编码；字段为空或绝对 URI 时返回 null
     */
    static String resolveDeploymentCategory(String category)
    {
        if (!StringUtils.hasText(category))
        {
            return null;
        }
        String normalized = category.trim();
        // Deployment.category 若被错误写成带协议的绝对 URI，也不能作为业务分类回显。
        if (normalized.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*$"))
        {
            return null;
        }
        return normalized;
    }
    /**
     * 校验页码、页大小和整数偏移边界。
     *
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageWindow，可直接传给 Flowable listPage 或 Mapper 的安全窗口
     */
    static PageWindow requirePage(int pageNum, int pageSize)
    {
        if (pageNum <= 0 || pageSize <= 0 || pageSize > MAX_PAGE_SIZE)
        {
            throw invalidArgument("分页参数不合法");
        }
        long offset = (long) (pageNum - 1) * pageSize;
        if (offset > Integer.MAX_VALUE)
        {
            throw invalidArgument("分页偏移量过大");
        }
        return new PageWindow((int) offset, pageSize);
    }
    /**
     * 校验查询返回的总数非负。
     *
     * @param count long，Flowable 或 Mapper 返回的计数
     * @return long，原始非负计数
     */
    static long checkedCount(long count)
    {
        if (count < 0)
        {
            throw dataError("工作流分页总数异常");
        }
        return count;
    }
    /**
     * 校验分页查询结果非空引用且未超过请求上限。
     *
     * @param rows List&lt;T&gt;，Flowable 或 Mapper 返回的当前页
     * @param limit int，本次请求最大记录数
     * @param <T> 当前页数据类型
     * @return List&lt;T&gt;，原始合法结果集合
     */
    static <T> List<T> checkedRows(List<T> rows, int limit)
    {
        if (rows == null || rows.size() > limit)
        {
            throw dataError("工作流分页结果异常");
        }
        return rows;
    }
    /**
     * 校验时间范围下界不得晚于上界。
     *
     * @param lower Instant，时间下界，允许为空
     * @param upper Instant，时间上界，允许为空
     * @param message String，校验失败的稳定提示
     * @return 无返回值，范围非法时抛出 400
     */
    static void validateRange(Instant lower, Instant upper, String message)
    {
        if (lower != null && upper != null && lower.isAfter(upper))
        {
            throw invalidArgument(message);
        }
    }
    /**
     * 校验必填文本并规范首尾空白。
     *
     * @param value String，待校验文本
     * @param message String，空值时稳定提示
     * @return String，规范后的非空文本
     */
    static String requireText(String value, String message)
    {
        String normalized = optionalText(value, message);
        if (normalized == null)
        {
            throw invalidArgument(message);
        }
        return normalized;
    }
    /**
     * 规范可选文本并限制查询长度。
     *
     * @param value String，允许为空的待规范文本
     * @param message String，文本过长时稳定提示
     * @return String，去除首尾空白的文本或 null
     */
    static String optionalText(String value, String message)
    {
        if (!StringUtils.hasText(value))
        {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_FILTER_LENGTH)
        {
            throw invalidArgument(message);
        }
        return normalized;
    }
    /**
     * 校验两个服务端关系主键完全一致。
     *
     * @param expected String，可信对象中的期望主键
     * @param actual String，待核验对象中的实际主键
     * @param message String，关系不一致的稳定提示
     * @return 无返回值，不一致时抛出 409
     */
    static void requireSame(String expected, String actual, String message)
    {
        if (!StringUtils.hasText(expected) || !expected.equals(actual))
        {
            throw new ServiceException(message, HttpStatus.CONFLICT);
        }
    }
    /**
     * 将可空 Date 转换为不可变 Instant。
     *
     * @param value Date，引擎或业务实体时间，允许为空
     * @return Instant，不可变时间或 null
     */
    static Instant toInstant(Date value)
    {
        return value == null ? null : value.toInstant();
    }
    /**
     * 将可空流程版本转换为视图整数，缺失版本视为关联数据异常。
     *
     * @param version Integer，Flowable 历史定义版本
     * @return int，非负流程版本
     */
    static int safeVersion(Integer version)
    {
        if (version == null || version < 0)
        {
            throw dataError("历史流程定义版本异常");
        }
        return version;
    }
    /**
     * 创建请求参数异常。
     *
     * @param message String，稳定业务提示
     * @return ServiceException，HTTP 400 业务异常
     */
    static ServiceException invalidArgument(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建引擎与业务对象关联数据异常。
     *
     * @param message String，稳定业务提示
     * @return ServiceException，HTTP 500 业务异常
     */
    static ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }
    /**
     * 安全分页窗口。
     *
     * @param offset int，从零开始的分页偏移
     * @param pageSize int，每页最大记录数
     */
    static record PageWindow(int offset, int pageSize)
    {
    }

    /** 当前页共享姓名查询；缺失或非数字历史身份保留原 ID，不跨请求缓存。 */
    static final class UserNameCache
    {
        private final ISysUserService userService;
        private final Map<String, String> userNames = new HashMap<>();

        UserNameCache(ISysUserService userService)
        {
            this.userService = userService;
        }

        String resolve(String userId)
        {
            if (!StringUtils.hasText(userId))
            {
                return null;
            }
            if (userNames.containsKey(userId))
            {
                return userNames.get(userId);
            }
            String displayName = userId;
            try
            {
                SysUser user = userService.selectUserById(Long.valueOf(userId));
                if (user != null && StringUtils.hasText(user.getNickName()))
                {
                    displayName = user.getNickName();
                }
            }
            catch (NumberFormatException ignored)
            {
                // 存量异常身份不做名称猜测，保留原值便于迁移对账和问题追踪。
            }
            userNames.put(userId, displayName);
            return displayName;
        }
    }
}
