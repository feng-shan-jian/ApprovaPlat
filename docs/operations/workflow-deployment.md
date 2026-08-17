# 工作流安装与运行

## 支持边界

当前开发期最终结构只支持全新空库安装，固定形成 `94/27` 表结构。部分初始化、旧结构或未知漂移不得续跑，必须废弃目标 schema 并从空库重建。

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

数据库按 [../database/workflow-baseline.md](../database/workflow-baseline.md) 安装。应用、DDL 和只读验收账号必须分离；运行账号仅拥有目标 schema 的 `SELECT`、`INSERT`、`UPDATE`、`DELETE`。

空库执行发布包 `sql/release-order.txt` 中的七个核心工作流 SQL；若依和 Quartz 基线由环境初始化先行执行。`integration/8.0.0__sms_oss.sql` 为可选集成资产，会额外创建 4 张表，不属于工作流核心安装、`94/27` 表数或 35 项只读门禁。

## 构建与安装流程

1. 在仓库根目录执行 `mvn clean package`，不得把跳过测试的构建结果直接当成已验收发布包。
2. 在 `ruoyi-ui` 执行 `npm ci` 和 `npm run build:prod`。
3. 将 JAR、前端静态文件、`sql` 和 `deployment` 安装到新的不可变版本目录。
4. 从 `deployment/config/ruoyi.env.example` 创建 `/etc/ruoyi/ruoyi.env`，由 root 填写真实值并设置为 `0600`。
5. 将生产 YAML 安装到 `/etc/ruoyi/`，使用 `deployment/systemd/ruoyi-backend.service` 启动后端。
6. 使用 `deployment/nginx/ruoyi.conf` 提供 TLS、静态前端和 `/prod-api/` 反向代理。
7. 运行发布包预检、数据库只读验收、真实登录和业务烟测后再开放流量。

安装目录必须采用版本目录加原子链接，禁止直接覆盖正在运行的 JAR 或前端目录：

```bash
release_id='<approved-release-id>'
release_root="/opt/ruoyi/releases/${release_id}"
install -d -o root -g root -m 0755 "$release_root/backend" "$release_root/frontend" "$release_root/config"
install -o root -g root -m 0444 ruoyi-admin.jar "$release_root/backend/ruoyi-admin.jar"
cp -a -- frontend/. "$release_root/frontend/"
cp -a -- config/. "$release_root/config/"
find "$release_root" -type f -exec chmod a-w {} +
sha256sum -- "$release_root/backend/ruoyi-admin.jar"

ln -sfn -- "$release_root/backend/ruoyi-admin.jar" /opt/ruoyi/current/ruoyi-admin.jar
ln -sfn -- "$release_root/frontend" /opt/ruoyi/current/frontend
ln -sfn -- "$release_root/config" /opt/ruoyi/current/config
systemctl daemon-reload
systemctl restart ruoyi-backend.service
systemctl is-active --quiet ruoyi-backend.service
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/liveness
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness
```

运行中 JAR、前端入口文件和生产配置的哈希必须与批准发布包 `SHA256SUMS` 对账。Nginx 放量前先从本机回源地址执行真实登录和业务烟测，再按节点逐步恢复流量。

## 密钥与凭据

- 单节点未显式设置 `RUOYI_TOKEN_SECRET` 时，应用在 `/var/lib/ruoyi-secrets/token-secret` 原子生成并稳定复用 64 字节密钥。
- 多节点必须由密钥系统向全部节点注入同一个 `RUOYI_TOKEN_SECRET`，不能使用节点本地生成的不同密钥。
- 数据库、Redis、Druid 和集成 Token 不得写入 Git、发布包清单、命令行参数或验收报告。
- 密钥文件、附件和数据库备份必须进入加密备份范围。

## 运行拓扑

- 默认拓扑是单节点、executor 关闭、本地持久卷；附件按数据库行租约领取清理任务，不使用 MySQL advisory lock。
- 启用 executor 前必须确认数据库结构、容量批准、监控指标、deadletter 告警和唯一执行协调。
- 多节点必须使用共享附件存储，并通过 `.storage-id` 或等效标识证明所有节点连接同一存储。
- systemd 服务必须以非 root 用户运行，并保留 `NoNewPrivileges`、私有临时目录、只读系统目录和受控可写路径。

## 就绪与验收

开放入口流量前至少确认：

- 后端 liveness、readiness、数据库、Redis 和 `workflowRuntime` 健康均正常。
- Prometheus 工作流运行快照未过期，附件清理租约和存储没有降级。
- `flowable.database-schema-update=false`，executor 状态与批准拓扑一致。
- 工作流核心表数固定为 94（若依 20、Quartz 11、Flowable 36、`wf_*` 27），99 条菜单和三组共 35 项数据库验收全部通过；17 张累计退役表不存在，Flowable 部署制品、通知稳定来源关联、附件租约和凭据 Redis 限流结构完整。
- 管理员真实登录成功，五角色权限和核心发起/审批/附件主链通过。
- 附件目录可写、无符号链接逃逸，并完成真实备份恢复对账。

## 失败恢复

- 空库安装失败时废弃部分初始化 schema，从新的空 schema 重装。
- 应用制品切换失败时保持流量关闭，回切上一不可变应用、前端和配置制品；数据库仍必须满足同一最终基线。
- 具体发布、制品回切、时间线和验收规则见 [workflow-release.md](workflow-release.md)。
