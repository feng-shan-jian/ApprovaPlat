# 定时任务调用契约

`SysJob.invokeTarget` 继续使用 `beanName.method(arguments)` 或 `package.Class.method(arguments)`，存储字段、请求和响应结构不变。例如 `ryTask.ryParams('https://example.test')`、`com.ruoyi.quartz.task.RyTask.ryNoParams()`。URL 在这里是普通字符串，不表示网络请求。

Bean 名称和 alias 不受 Java 标识符规则限制，继续按 Spring 注册名称查找，例如 `ry-task.ryParams('plain')`。类与 Bean 沿用原有区分方式：目标名称中超过一个 `.` 时按类名处理。授权始终针对取得的真实类型，不因名称或 alias 放宽。

`JobInvokeUtil` 在新增／修改时解析目标及参数，在每次 Quartz 执行时重新解析并授权。授权只依据真实任务类和所选公开方法的声明类；两者的包必须等于 `Constants.JOB_WHITELIST_STR` 中的包，或是其以 `.` 分隔的子包。当前允许 `com.ruoyi.quartz.task`，不允许 `com.ruoyi.quartz.taskextra`。参数包含允许包名或旧禁用词不会改变目标授权。

Spring Bean 使用真实目标类型选择方法，实际调用仍经过原代理。类调用在目标和方法检查通过后才调用公开无参构造函数；保存时不创建该类实例。不开放 `Object` 或未授权父类声明的方法，也不允许属性访问、嵌套调用或表达式求值。未知 Bean／类、未知方法、参数与声明类型不匹配、引号或调用格式不完整都不能保存或执行。

只有目标 Bean 名称本身不存在才按输入无效处理。已注册任务的懒加载、工厂方法或依赖初始化失败保留 Spring 异常及原 cause 传播；运行时继续记录实际创建失败信息，不转换成“调用目标或参数不合法”。

参数沿用既有五种类型与精确方法签名，不作额外的隐式类型转换：

| 写法 | Java 参数类型 |
| --- | --- |
| `'text'` 或 `"text"` | `String`，去掉成对引号后原样传入 |
| `true` / `false`，大小写均可 | `Boolean` |
| `123L` | `Long` |
| `1.25D` | `Double` |
| `123` | `Integer` |

空参数列表使用 `()`。沿用原有逗号分隔规则，字符串首尾引号必须匹配；内部引号继续作为内容，例如 `'O'Brien'` 和 `'He said "it's ok"'` 原样传入，不要求改写或新增转义。字符串中的逗号、括号、URL 和引号属于内容；反斜杠不作为转义指令。参数之间可以留空白。不新增数组、对象、`null`、字符串拼接或其他表达式语法。

历史规则解析为一个 `String` 的内容始终是数据，不根据内容猜测表达式边界。例如 `ryTask.ryParams('count('x')')` 传入 `count('x')`；`ryTask.ryParams('a');ryTask.ryParams('b')` 传入单个字符串 `a');ryTask.ryParams('b`。每次只执行解析和授权得到的一个方法，既不求值参数，也不追加执行其中看起来像调用的文字。外侧调用结构、方法与参数签名、首尾引号及外侧尾随非参数文本仍检查。

Cron 校验、方法权限、事务、初始暂停状态、恢复／暂停、并发策略、misfire 和立即执行流程保持原有实现。绕过 HTTP 存入的作业或直接建立的 Quartz 作业仍须通过执行时授权；失败继续由 `AbstractQuartzJob` 写入状态为 `1` 的运行日志及异常信息，成功状态为 `0`。无持久格式或数据库迁移；回退代码会恢复旧的全文误拦截和缺少运行时授权问题。

## 验证入口和边界

```powershell
mvn -pl ruoyi-admin -am '-Dtest=JobInvocationTest,CronPreviewTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl ruoyi-admin -am verify
```

`JobInvocationTest` 使用生产 Controller、方法权限、带事务的服务、未经替换的 MyBatis XML Mapper、`ScheduleUtils` 和两种生产 Quartz Job。每个测试有专属 RAMJobStore、线程池、Spring 上下文和 H2 内存库；任务和执行日志实际写入 H2，并从 Mapper 读回。H2 表结构来自正式系统 SQL，使用 MySQL 模式和测试专属 `sysdate()` 函数适配。测试结束关闭调度器与数据库，恢复 Spring 静态上下文。

记录任务夹具继承 `RyTask`，仅记录收到的参数。菜单服务是 mock；任务 Mapper、事务和调度器不是 mock。测试覆盖新增、修改、HTTP 请求／响应与权限、自动 Cron 触发、立即执行、代理切面、参数类型、Bean 名称及 alias、内部引号兼容、非法输入运行时阻断、真实懒加载任务创建／依赖失败及运行日志、调度配置失败时的数据库回滚。`CronPreviewTest` 继续覆盖原有 Cron 预览与权限行为。

这些证据不代表完整应用／浏览器／JWT／审计切面、MySQL、Quartz JDBC JobStore、集群或进程重启持久恢复已经验证。H2 数据在测试结束销毁，RAMJobStore 不提供调度元数据持久化。测试不访问参数中的 URL，不执行外部命令。
