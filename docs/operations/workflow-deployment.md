# 工作流安装与运行

## 支持边界

首个正式版本只支持全新安装。目标 MySQL schema 必须为空；发现任何现有业务表时应停止安装，不得自动升级、覆盖或删除未知数据。

标准生产资产位于：

- `deployment/config/application.yml`
- `deployment/config/application-druid.yml`
- `deployment/config/ruoyi.env.example`
- `deployment/systemd/ruoyi-backend.service`
- `deployment/nginx/ruoyi.conf`
- `deployment/scripts/workflow-release-gate.sh`

## 基础依赖

- Java 17、Maven 3.9+、Node.js 20+、npm 10+
- MySQL 8、Redis 6+、Nginx 1.24+
- Linux/systemd 生产主机和位于 Web 根目录之外的持久化附件目录

数据库按 [../database/workflow-baseline.md](../database/workflow-baseline.md) 初始化。应用、DDL、只读验收和备份账号必须分离；运行账号仅拥有目标 schema 的 `SELECT`、`INSERT`、`UPDATE`、`DELETE`。

## 构建与安装流程

1. 在 `back` 执行 `mvn clean package`，不得把跳过测试的构建结果直接当成已验收发布包。
2. 在 `vite` 执行 `npm ci` 和 `npm run build:prod`。
3. 将 JAR、前端静态文件、`back/sql` 和 `deployment` 安装到新的不可变版本目录。
4. 从 `deployment/config/ruoyi.env.example` 创建 `/etc/ruoyi/ruoyi.env`，由 root 填写真实值并设置为 `0600`。
5. 将生产 YAML 安装到 `/etc/ruoyi/`，使用 `deployment/systemd/ruoyi-backend.service` 启动后端。
6. 使用 `deployment/nginx/ruoyi.conf` 提供 TLS、静态前端和 `/prod-api/` 反向代理。
7. 运行发布包预检、数据库只读验收、真实登录和业务烟测后再开放流量。

## 密钥与凭据

- 单节点未显式设置 `RUOYI_TOKEN_SECRET` 时，应用在 `/var/lib/ruoyi-secrets/token-secret` 原子生成并稳定复用 64 字节密钥。
- 多节点必须由密钥系统向全部节点注入同一个 `RUOYI_TOKEN_SECRET`，不能使用节点本地生成的不同密钥。
- 数据库、Redis、Druid 和集成 Token 不得写入 Git、发布包清单、命令行参数或验收报告。
- 密钥文件、附件和数据库备份必须进入加密备份范围。

## 运行拓扑

- 默认拓扑是单节点、executor 关闭、本地持久卷、MySQL advisory lock 附件清理锁。
- 启用 executor 前必须确认数据库结构、容量批准、监控指标、deadletter 告警和唯一执行协调。
- 多节点必须使用共享附件存储，并通过 `.storage-id` 或等效标识证明所有节点连接同一存储。
- systemd 服务必须以非 root 用户运行，并保留 `NoNewPrivileges`、私有临时目录、只读系统目录和受控可写路径。

## 就绪与验收

开放入口流量前至少确认：

- 后端 liveness、readiness、数据库、Redis 和 `workflowRuntime` 健康均正常。
- Prometheus 工作流运行快照未过期，清理锁和附件存储没有降级。
- `flowable.database-schema-update=false`，executor 状态与批准拓扑一致。
- 95 表（若依 20、Quartz 11、Flowable 36、ApprovaPlat `wf_*` 28）、82 条菜单和三组数据库验收通过。
- 管理员真实登录成功，五角色权限和核心发起/审批/附件主链通过。
- 附件目录可写、无符号链接逃逸，并完成真实备份恢复对账。
