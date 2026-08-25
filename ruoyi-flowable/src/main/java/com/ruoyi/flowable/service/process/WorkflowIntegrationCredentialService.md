# WorkflowIntegrationCredentialService

## 作用

`WorkflowIntegrationCredentialService` 管理集成凭据的创建、轮换、吊销、Token 哈希认证、scope 校验和 Redis 分钟限流。明文 Token 只在创建或轮换响应中返回一次。

## 认证链路

1. 按 Token 前 12 位读取凭据，并使用常量时间比较 SHA-256。
2. 校验吊销、到期和 `MESSAGE`、`SIGNAL`、`RECEIVE` scope。
3. 使用 Key `workflow:credential:rate:{credentialId}:{revision}:{yyyyMMddHHmm}` 调用 Redis Lua 原子计数，TTL 固定为 60 秒。
4. 计数超过 `rate_limit_per_minute` 返回 `429 / INTEGRATION_RATE_LIMITED`。
5. Redis 不可用返回 `503 / INTEGRATION_RATE_LIMIT_UNAVAILABLE`，不会继续进入 Flowable 或写业务台账。
6. `last_used_at` 最多每五分钟条件更新一次，避免热点凭据逐次写 MySQL。

## 持久化状态

| 状态 | 消费者 | 事务原因 | 保留期 |
| --- | --- | --- | --- |
| Token SHA-256、前缀、scope、revision、吊销和到期 | 管理 API、运行事件认证 | 轮换和吊销必须与 revision 条件原子提交 | 凭据管理生命周期内保留 |
| `last_used_at` | 管理页面和运维审计 | 低频条件更新，revision 防止并发轮换覆盖 | 随凭据保留 |
| Redis 分钟计数 | 本服务 | 跨节点原子限流，不参与 MySQL 事务 | 60 秒自动过期 |

数据库不保存 `rate_window_start`、`rate_window_count` 或其他高频运行计数。

## 最小调用示例

```java
AuthenticatedCredential credential = credentialService.authenticateAndConsume(
        integrationToken, "RECEIVE");
```

返回上下文只包含凭据主键、创建用户主键和允许变量集合，不包含 Token 或 Token 摘要。
