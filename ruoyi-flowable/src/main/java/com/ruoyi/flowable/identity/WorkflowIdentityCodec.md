# WorkflowIdentityCodec

## 作用

统一工作流身份格式。用户固定使用数字 `sys_user.user_id`，候选组固定使用 `ROLE<roleId>` 或 `DEPT<deptId>`，避免各业务服务自行截取字符串。

## 公开方法

| 方法 | 用途 |
| --- | --- |
| `normalizeUserId(String)` | 校验并规范化数字用户 ID |
| `parseCandidateGroup(String)` | 解析角色或部门候选组 |
| `roleGroup(long)` | 生成角色候选组 |
| `deptGroup(long)` | 生成部门候选组 |

## 关键约束

- 只接受无前导零的正整数十进制主键，拒绝空值、零、负数、符号、小数、空白和 `long` 溢出。
- 前缀区分大小写，不兼容 `ROLE_1`、`role1` 等历史非标准格式。
- 候选组必须与生成方法的结果完全一致；`ROLE007`、`DEPT003` 即使数字部分可解析，也无法与 Flowable 当前用户组精确匹配，因此统一拒绝。
- 校验失败统一返回 HTTP 400 语义的 `ServiceException`，响应不回显原始非法值。

## 最小接入示例

```java
String userId = identityCodec.normalizeUserId(rawUserId);
WorkflowCandidateGroup group = identityCodec.parseCandidateGroup("ROLE12");
```
