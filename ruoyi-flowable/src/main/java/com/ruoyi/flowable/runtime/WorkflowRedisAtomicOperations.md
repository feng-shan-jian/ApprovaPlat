# WorkflowRedisAtomicOperations

## 作用

`WorkflowRedisAtomicOperations` 是工作流模块共享的短期 Redis 原子操作边界。业务服务负责 Key 命名、权限和 HTTP 错误语义，本组件只提供可验证的原子命令。

当前消费者：

- 集成凭据限流：Lua 原子执行 `INCR + EXPIRE`。
- 人工催办冷却：带 TTL 的 `SET NX`。

## 接入方式

业务服务通过构造器注入本组件，由本组件统一访问 `StringRedisTemplate`。

```java
long count = redisOperations.incrementWithExpiry(
        "workflow:credential:rate:19:3:202608161205", Duration.ofSeconds(60));

ExpiringSetResult result = redisOperations.setIfAbsent(
        "workflow:urge:cooldown:7:process-19", Duration.ofMinutes(5));
```

## 公开方法

| 方法 | 参数 | 返回值 | 原子语义 |
| --- | --- | --- | --- |
| `incrementWithExpiry` | 非敏感 Key、正整数秒 TTL | 递增后计数 | 单次 Lua 调用执行 `INCR`，首次计数时执行 `EXPIRE` |
| `setIfAbsent` | 非敏感 Key、正整数秒 TTL | 是否成功及剩余秒数 | Redis `SET NX` 与 TTL 同一命令提交 |

Redis `DataAccessException` 传播到业务服务，由各服务分别映射为稳定 `503`、结构化日志和低基数指标。

## 状态生命周期

- 凭据限流 Key 保留 60 秒，唯一消费者是集成凭据认证服务。
- 催办冷却 Key 按 `flowable.notification.urge-interval` 自动过期，唯一消费者是人工催办服务。
- 两类状态属于短期运行协调数据，仅存储在 Redis 并按 TTL 自动清理。

## 设计约束

- Key 只包含固定业务前缀和脱敏主键。
- TTL 使用正整数秒，与 Redis 秒级过期语义保持一致。
- `StringRedisTemplate` 原子命令直接替换本地缓存、Redisson 和额外持久化表方案。
