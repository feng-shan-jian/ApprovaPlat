# WorkflowIdentityCodec

## 作用

统一工作流身份格式。用户固定使用数字 `sys_user.user_id`，候选组固定使用 `ROLE<roleId>` 或 `DEPT<deptId>`，各业务服务复用同一解析结果。

## 公开方法

| 方法 | 用途 |
| --- | --- |
| `normalizeUserId(String)` | 校验并规范化数字用户 ID |
| `parseCandidateGroup(String)` | 解析角色或部门候选组 |
| `roleGroup(long)` | 生成角色候选组 |
| `deptGroup(long)` | 生成部门候选组 |

## 关键约束

- 主键规范为无前导零且位于 `long` 正整数范围内的十进制字符串，其余输入返回稳定格式错误。
- 前缀区分大小写，规范格式固定为 `ROLE<id>` 和 `DEPT<id>`；`ROLE_1`、`role1` 等非标准格式返回 `400`。
- 候选组与生成方法的规范结果完全一致；`ROLE007`、`DEPT003` 等非规范输入返回稳定格式错误。
- 校验失败统一返回 HTTP 400 语义的 `ServiceException`，响应仅包含稳定的通用错误信息。

## 最小接入示例

```java
String userId = identityCodec.normalizeUserId(rawUserId);
WorkflowCandidateGroup group = identityCodec.parseCandidateGroup("ROLE12");
```
