package com.ruoyi.framework.config;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.mapper.SysUserMapper;

/**
 * 全新安装时一次性初始化预置管理员随机密码，避免仓库携带可直接登录的固定口令。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ruoyi.bootstrap-admin", name = "enabled",
        havingValue = "true")
public class AdminBootstrapInitializer implements ApplicationRunner
{
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapInitializer.class);

    /** SQL 基线中预置管理员的固定主键。 */
    static final long BOOTSTRAP_ADMIN_USER_ID = 1L;
    /** SQL 基线中预置管理员的固定账号。 */
    static final String BOOTSTRAP_ADMIN_USERNAME = "admin";
    /** SQL 基线中不可通过 BCrypt 验证的待初始化标记。 */
    static final String BOOTSTRAP_PASSWORD_MARKER = "!RUOYI_BOOTSTRAP_REQUIRED!";
    /** 首次管理员随机密码最小字符数。 */
    private static final int MIN_PASSWORD_LENGTH = 20;
    /** 首次管理员随机密码最大字符数，防止异常环境值消耗过多 BCrypt 资源。 */
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final SysUserMapper userMapper;
    /** 只在一次性初始化启动中由受控环境变量注入的明文随机密码。 */
    private final String bootstrapPassword;

    /**
     * 创建一次性管理员初始化器。
     * @param userMapper SysUserMapper，正式用户表原子更新 Mapper
     * @param bootstrapPassword String，受控环境变量中的首次随机密码
     * @return 无返回值，构造后由 Spring 容器管理
     */
    public AdminBootstrapInitializer(SysUserMapper userMapper,
            @Value("${ruoyi.bootstrap-admin.password:}") String bootstrapPassword)
    {
        this.userMapper = userMapper;
        this.bootstrapPassword = bootstrapPassword;
    }

    /**
     * 校验随机密码并在同一事务中把预置管理员从待初始化状态切换为可登录状态。
     * @param args ApplicationArguments，Spring Boot 启动参数，本流程不读取其内容
     * @return void，无返回值；账号状态或密码不符合门禁时中止应用启动
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args)
    {
        validateBootstrapPassword(bootstrapPassword);

        SysUser currentUser = requireBootstrapAdmin();
        if (BOOTSTRAP_PASSWORD_MARKER.equals(currentUser.getPassword())
                && UserStatus.DISABLE.getCode().equals(currentUser.getStatus()))
        {
            String encodedPassword = SecurityUtils.encryptPassword(bootstrapPassword);
            int updated = userMapper.initializeBootstrapAdminCredential(
                    BOOTSTRAP_ADMIN_USER_ID, BOOTSTRAP_ADMIN_USERNAME,
                    BOOTSTRAP_PASSWORD_MARKER, encodedPassword);
            if (updated != 1)
            {
                throw new IllegalStateException("预置管理员状态已变化，首次密码初始化已停止");
            }

            SysUser initializedUser = requireBootstrapAdmin();
            if (!UserStatus.OK.getCode().equals(initializedUser.getStatus())
                    || !SecurityUtils.matchesPassword(
                            bootstrapPassword, initializedUser.getPassword()))
            {
                throw new IllegalStateException("预置管理员首次密码写入后校验失败");
            }
            log.warn("预置管理员已完成一次性随机密码初始化，请立即移除初始化环境变量并重启服务");
            return;
        }

        if (UserStatus.OK.getCode().equals(currentUser.getStatus())
                && SecurityUtils.matchesPassword(bootstrapPassword, currentUser.getPassword()))
        {
            log.warn("预置管理员已使用当前随机密码完成初始化，请移除初始化环境变量并重启服务");
            return;
        }
        throw new IllegalStateException("预置管理员不处于允许的一次性初始化状态");
    }

    /**
     * 读取并核对固定预置管理员，禁止初始化器作用于其他账号或逻辑删除记录。
     * @return SysUser，主键和账号均与 SQL 基线一致的预置管理员
     */
    private SysUser requireBootstrapAdmin()
    {
        SysUser user = userMapper.selectUserById(BOOTSTRAP_ADMIN_USER_ID);
        if (user == null || !BOOTSTRAP_ADMIN_USERNAME.equals(user.getUserName())
                || !"0".equals(user.getDelFlag()))
        {
            throw new IllegalStateException("预置管理员记录不存在或身份不符合初始化门禁");
        }
        return user;
    }

    /**
     * 校验首次管理员密码的长度、字符范围和复杂度，避免弱口令进入正式用户表。
     * @param password String，受控环境变量注入的明文随机密码
     * @return void，无返回值；密码不符合门禁时中止应用启动
     */
    private void validateBootstrapPassword(String password)
    {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH
                || password.length() > MAX_PASSWORD_LENGTH
                || !password.matches("[\\x21-\\x7E]+")
                || !password.matches(".*[a-z].*")
                || !password.matches(".*[A-Z].*")
                || !password.matches(".*[0-9].*")
                || !password.matches(".*[^A-Za-z0-9].*")
                || password.toLowerCase(Locale.ROOT).contains(BOOTSTRAP_ADMIN_USERNAME))
        {
            throw new IllegalStateException(
                    "RUOYI_BOOTSTRAP_ADMIN_PASSWORD 必须为 20-128 位可打印随机复杂密码");
        }
    }
}
