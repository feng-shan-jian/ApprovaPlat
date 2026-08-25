<div align="center">

# ApprovaPlat

**Run production-oriented approval workflows from design through audit.**

An open-source approval platform built on RuoYi, Flowable 8, Spring Boot 4, and Vue 3, covering design, runtime, authorization, data, and audit.

[中文](README.md) | **English**

[Quick start](#quick-start) · [Current capabilities](#what-works-today) · [Production acceptance boundary](#production-acceptance-boundary) · [Documentation (Chinese)](docs/README.md)

[![Flowable 8.0.0](https://img.shields.io/badge/Flowable-8.0.0-2f855a?style=flat-square)](https://github.com/flowable/flowable-engine) [![Spring Boot 4.0.6](https://img.shields.io/badge/Spring%20Boot-4.0.6-6db33f?style=flat-square)](https://spring.io/projects/spring-boot) [![Vue 3.5.26](https://img.shields.io/badge/Vue-3.5.26-42b883?style=flat-square)](https://vuejs.org/) [![Project status](https://img.shields.io/badge/Status-Early%20Stage-f59e0b?style=flat-square)](#where-the-project-stands) [![MIT License](https://img.shields.io/badge/License-MIT-1f2937?style=flat-square)](LICENSE)

</div>

<p align="center">
  <img src="docs/assets/readme/workflow-designer.png" width="100%" alt="ApprovaPlat dynamic multi-instance approval process designer" />
  <br />
  <sub>Dynamic multi-instance approval process designer</sub>
</p>

<table>
  <tr>
    <td width="33%" align="center"><strong>Design and release</strong><br />BPMN, forms, versions, and deployments</td>
    <td width="33%" align="center"><strong>Requests and approvals</strong><br />Multi-instance tasks, returns, withdrawals, and audits</td>
    <td width="33%" align="center"><strong>Engineering and migration</strong><br />Authorization, data, testing, and operations</td>
  </tr>
</table>

## Why ApprovaPlat exists

I encountered enterprise approvals during an internship on a B2B AI product and became interested in how an approval platform connects processes, forms, authorization, and business data.

After studying projects such as [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue), [Yudao](https://github.com/YunaiV/ruoyi-vue-pro), [Flowable](https://github.com/flowable/flowable-engine), and [bpmn.io](https://github.com/bpmn-io/bpmn-js), I wanted a modern Flowable 8 implementation with a clear open-source scope and complete public code and database assets.

ApprovaPlat therefore uses Flowable 8 as its runtime core, connects design, deployment, submission, task handling, authorization, data, and auditing, and maintains business rules, SQL, documentation, and verification entry points in one repository.

The project is still early, and its core code, SQL, documentation, and tests will remain public. Feedback from experienced developers is always welcome, and I hope anyone building, planning, or migrating an approval system can find implementations and lessons here that are genuinely useful.

## Where the project stands

ApprovaPlat is currently at an early stage. The capabilities below exist in the repository today.

Today, process design, deployment, submission, task handling, history, and audit queries form a working path. Flowable 8 executes the workflows, MySQL stores business data, and Redis handles login state and caching. It can serve as a learning, evaluation, or secondary-development base; production use follows successful deployment, capacity, failure, and recovery acceptance in the target environment.

It is currently best suited to:

- Learning Flowable 8 and how an approval system connects its frontend, backend, authorization, and data.
- Serving as a reference implementation or foundation for a new approval project.
- Validating target capabilities for a Flowable version upgrade or migration from another workflow engine.
- Running a pilot before production while completing deployment, capacity, and failure testing for the target environment.

## What works today

### Design and release

- Manage process categories, dynamic forms, BPMN models, versions, and deployment state.
- Import, export, preview source, run linting and token simulation, and edit node properties in the BPMN.js designer.
- Validate BPMN on the backend before deployment. Freeze form, extension, DMN, and SLA snapshots on deployment, then suspend the old definition while in-flight instances continue.

### Requests and approvals

- Use dedicated views for new requests, my requests, inbox, claimable tasks, completed work, and CC notifications.
- Start, withdraw, cancel, terminate, suspend, and activate process instances; claim, approve, delegate, transfer, return, and reject tasks.
- Run ALL and ANY multi-instance approval modes, add or remove approvers dynamically, and handle concurrent update conflicts.
- Persist submission and deployment form snapshots. Attachment handling covers object-level authorization, quotas, binding, downloads, deletion, and physical cleanup.

### Advanced workflows and integration

- Run controlled repeat approval with an exit condition, maximum iterations, and an audit trail for each round.
- Use BPMN Error, Escalation, boundary timers, business calendars, approval SLAs, and DMN decisions.
- Use controlled Java/CEL, HTTP and SQL connectors, integration credentials, and runtime event endpoints.
- Participant, MessageFlow, and multi-pool collaboration are backed by persistent delivery, idempotency, ordering, retries, dead letters, and auditing.

### Authorization, data, and operations

- Separate design, submission, approval, administration, and audit duties, with object-level authorization for instances, tasks, deployments, attachments, and audit data.
- Keep Flowable and business data in the same primary datasource and transaction boundary. Backend runtime facts and the formal database form the single authoritative workflow state.
- Provide health checks, runtime snapshots, Micrometer/Prometheus metrics, attachment cleanup locks, and runtime readiness validation.
- Include production configuration, systemd, and Nginx deployment assets.

## Screenshots

The view below shows a dynamic multi-instance approval completed in a real frontend, backend, Flowable, MySQL, and Redis environment. The page reloads the instance state and deployed BPMN, then highlights the path that actually ran.

![ApprovaPlat completed dynamic multi-instance approval](docs/assets/readme/process-trace.png)

## Production acceptance boundary

- The first production database baseline installs into an empty schema. Environments already on the formal 8.0.0 baseline move forward with `8.0.1__workflow_mail_config.sql`.
- Production fixes `flowable.database-schema-update=false`; maintained repository SQL and runtime validation provide the schema update path.
- Formal gateway entry points cover exclusive, parallel, inclusive, and event gateways. Controlled loops execute business repetition, while standard loops retain XML round-trip support.
- Async executors are enabled after database, topology, capacity, monitoring, and single-executor coordination acceptance, then run timers, SLAs, and background work.
- Multi-node operation, shared attachment storage, real external side effects, backup recovery, and long-duration stability enter production after acceptance in the target environment.

See the [approval behavior contract](docs/contracts/workflow-behavior.md) and [multi-pool collaboration contract](docs/contracts/workflow-collaboration.md) for precise boundaries.

## Quick start

### Requirements

- JDK 17+
- Maven 3.9+
- Node.js 20+
- npm 10+
- MySQL 8
- Redis 6+

### 1. Initialize the database

Create an empty MySQL schema and run the RuoYi, Quartz, Flowable, and ApprovaPlat SQL in the exact order documented in the [database baseline](docs/database/workflow-baseline.md). All MySQL client connections should explicitly use `utf8mb4`.

The clean-install scripts in the initial baseline are dedicated to an empty schema. Existing business databases use a reviewed forward migration, with Flowable automatic schema updates kept disabled.

Databases already on the formal 8.0.0 baseline run only `8.0.1__workflow_mail_config.sql`, then replay the idempotent menu script. The [existing-baseline upgrade](docs/database/workflow-baseline.md#现有基线升级) documents the backup, verification, and command sequence. The migration creates an empty configuration table; administrators save SMTP settings through the controlled application page.

### 2. Configure and start the backend

Set local environment variables in PowerShell using values from your MySQL environment:

```powershell
$env:RUOYI_DB_URL = 'jdbc:mysql://127.0.0.1:3306/approvaplat?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai'
$env:RUOYI_DB_USERNAME = '<application-user>'
$env:RUOYI_DB_PASSWORD = '<application-password>'
$env:DRUID_MONITOR_USERNAME = 'local'
$env:DRUID_MONITOR_PASSWORD = '<separate-monitor-password>'
$env:RUOYI_PROFILE = Join-Path $env:LOCALAPPDATA 'ApprovaPlat\uploads'

mvn -pl ruoyi-admin -am -DskipTests package
java -jar .\ruoyi-admin\target\ruoyi-admin.jar --server.address=127.0.0.1
```

`-DskipTests` shortens the first local startup. When `RUOYI_TOKEN_SECRET` is absent, a single-node installation generates and reuses a random HS512 secret in the user's private directory; production deployments manage the Token secret and database configuration explicitly.

Saved SMTP host, port, account, and sender identity take effect dynamically by database revision. The authorization code is encrypted with a purpose-specific subkey derived from the RuoYi Token secret, so one stable Token secret supports both login tokens and SMTP ciphertext. Keep that secret consistent across restarts and nodes; during key rotation, sign in again and save the SMTP authorization code again through the mail-service page.

### 3. Start the frontend

Open another PowerShell window:

```powershell
Set-Location ruoyi-ui
npm ci
$env:VITE_OPEN_BROWSER = 'false'
npm run dev -- --host 127.0.0.1 --port 1024
```

Open `http://127.0.0.1:1024`. The clean local baseline account is `admin` with initial password `wang`. Use it for local development and complete password rotation before exposing the service beyond your machine.

## Development and testing

Common development checks:

```powershell
mvn clean verify

Set-Location ruoyi-ui
npm run test:contracts
npm run build:prod
```

Real MySQL `*IT` tests use an opt-in Failsafe profile. CI first prepares a dedicated `approvaplat_it` schema and then supplies these environment variables. The profile connects to that isolated MySQL when all values are present and fails immediately when configuration is incomplete. Regular `mvn test` and `mvn verify` run unit and local integration tests.

```powershell
$env:WORKFLOW_MYSQL_TEST_URL = 'jdbc:mysql://127.0.0.1:3306/approvaplat_it?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia%2FShanghai'
$env:WORKFLOW_MYSQL_TEST_USERNAME = '<isolated-acceptance-user>'
$env:WORKFLOW_MYSQL_TEST_PASSWORD = '<isolated-acceptance-password>'

mvn -pl ruoyi-flowable -am -Pworkflow-mysql-it verify
```

Verification against real MySQL and Redis instances, real roles, and APIs uses the corresponding runtime environment and isolated data.

## Technology

| Area                  | Technology                                             |
| --------------------- | ------------------------------------------------------ |
| Backend               | Java 17, Spring Boot 4.0.6                             |
| Workflow and decision | Flowable 8.0.0 Process / DMN                           |
| Data                  | MyBatis, MySQL 8, Redis                                |
| Frontend              | Vue 3.5.26, Vite 6.4.3, Element Plus 2.13.1            |
| Designer              | BPMN.js 18.22.0                                        |
| Rules and connectors  | CEL, JSqlParser, controlled Java / HTTP / SQL          |
| Observability         | Spring Boot Actuator, Micrometer, Prometheus           |
| Verification          | JUnit 5, frontend contract tests, production builds    |

## Repository layout

```text
ApprovaPlat/
|- pom.xml       Maven reactor entry point
|- ruoyi-*/      Spring Boot backend modules and Flowable domain module
|- ruoyi-ui/     Vue 3 frontend, workflow designer, and contract tests
|- sql/          Database baseline, business schema, and menu permissions
|- docs/         Architecture, behavior, collaboration, and database contracts
`- deployment/   Production config, systemd, and Nginx
```

## Documentation

| Topic                                          | Start here                                                                                            |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| System boundaries and data flow                | [Platform architecture](docs/architecture/workflow-platform.md)                                       |
| Constraints for every approval action          | [Approval behavior contract](docs/contracts/workflow-behavior.md)                                     |
| Participant and MessageFlow                    | [Multi-pool collaboration contract](docs/contracts/workflow-collaboration.md)                         |
| Clean installation and managed migrations      | [Workflow database baseline](docs/database/workflow-baseline.md)                                      |

## Contributing

Issues and pull requests are welcome, especially when they bring a real approval scenario, migration problem, or hard-earned lesson into the project.

For a new BPMN element or approval action, explain how it is edited, how it runs, who may use it, where its data is stored, what remains after failure, and how it will be tested.

## Upstream projects and license

ApprovaPlat builds on open-source projects including [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue), [Flowable](https://github.com/flowable/flowable-engine), and [bpmn.io](https://github.com/bpmn-io/bpmn-js).

This is an independent open-source project with its own release and support boundaries. The code is available under the [MIT License](LICENSE).

> May we all find our direction in the age of AI, build what we set out to build, and enjoy success and a smooth road ahead.
