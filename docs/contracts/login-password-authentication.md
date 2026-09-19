# 登录密码认证与重试状态

## 认证生命周期

`SecurityConfig` 仍提供真实 `BCryptPasswordEncoder` 与 Spring Security 的 `AuthenticationManager`。
既有 `SysPasswordService` 作为唯一的 `DaoAuthenticationProvider` Bean 被管理器发现，直接使用生产 `UserDetailsServiceImpl`。没有额外 Provider、密码结果缓存或新的认证框架。

1. 用户详情服务装载身份与权限，保留不存在、删除、停用的原有拒绝及顺序。
2. Provider 从 Redis 读取本次输入账号的实际重试次数。次数达到 `user.password.maxRetryCount` 时立即拒绝，不比对密码，也不刷新 TTL。
3. 未锁定时调用父类密码检查；父类使用生产 BCrypt 完成一次真实比对。
4. 错误密码原子累加一次失败并刷新有效期，仍返回原来的错误提示；达到上限的这一次仍是密码错误，后续观察到锁定的请求返回锁定提示。
5. 父类完成成功认证结果构造后删除该账号的重试键。Manager 保留凭据擦除行为。

用户装载阶段的 `validate`、独立 `matches` 和只服务于此链的原始凭据 `AuthenticationContextHolder` 已删除。权限上下文没有变更。每次独立认证都重新装载并比对，沿用 Provider 默认 `NullUserCache`，不缓存密码结论。生产配置仍是 BCrypt 默认强度、5 次上限、10 分钟锁定；测试用 1 分钟验证分钟与秒的换算。

## Redis 兼容与并发顺序

- 键仍是 `pwd_err_cnt:` 加本次输入的登录名（保留原输入名语义，不改为数据库规范名或用户 ID）。值仍是生产 Fastjson 序列化的 `Integer` 十进制数字；缓存查看与管理员解锁继续使用同一键。
- 失败用一个 Lua 脚本执行 `INCR` 和 `EXPIRE`，两个动作在 Redis 中连续原子执行，不丢失独立失败的更新。脚本参数是原有整数分钟值，由 Lua 换算秒数；不能传生产序列化器带类型后缀的 `Long`。
- 每个真正完成的错误比对都计数并将 TTL 刷新为完整锁定时长；锁定请求不刷新。旧值可直接被新代码累加，新值可被原 `RedisCache` 按 `Integer` 读取和写入，无数据迁移或第二个状态源。
- 锁定检查是 BCrypt 前的准入检查，不覆盖整个昂贵比对过程的分布式锁。并发进入时可能多于上限的请求已经获准比较；它们各自完成，错误次数可能超过上限。之后看到达到上限的请求都被阻止。
- 失败脚本与成功/管理员解锁的 `DEL` 按 Redis 执行顺序生效：失败先完成、成功后完成则清空；成功先完成、在途失败后完成则从 1 开始。正确密码请求若已通过预检查，即使另一在途失败先将计数推至上限，该成功仍可清理。删除前的“是否存在”预查已删除。

原有登录异常映射保留：错误密码的 `UserPasswordNotMatchException`、锁定的 `UserPasswordRetryLimitExceedException` 仍以 `InternalAuthenticationServiceException` 进入 `SysLoginService`，映射为原有 `ServiceException` 提示。不存在/删除/停用仍由身份装载拒绝。Redis 读取、脚本或清理失败会阻断登录，不把存储故障误判为认证成功，不吞掉错误。脚本执行成功而响应丢失的网络不确定性不自动重放；Redis 写入与 HTTP/JWT 输出没有跨系统事务。

管理员接口仍是 `GET /monitor/logininfor/unlock/{userName}`，权限仍为 `monitor:logininfor:unlock`，成功响应形状不变。

## 切换与恢复

同次发布这些生产修改即可切换，无缓存格式迁移。回退整组代码后旧键仍可读取，但旧版双重比较及并发计数丢失会恢复；混合部署期间旧实例仍可能覆盖新实例的累计值，应完成全部实例切换再认定并发修复生效。Redis 故障或错误键需按既有运维入口恢复服务/解锁；不在应用中旁路重试状态，也不以清空全部 Redis 恢复。
