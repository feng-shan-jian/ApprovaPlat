# ApprovaPlat

**English** | [Simplified Chinese](README.zh-CN.md)

An enterprise approval platform built with Flowable 8 and Spring Boot 4.

ApprovaPlat focuses on the engineering integration and production adoption of Flowable 8 in the Spring Boot 4 ecosystem. It is not a workflow demonstration that stops after moving a process token from one task to another. It is a complete approval application covering process design, request submission, task approval, multi-instance approval, rejection, copy notifications, attachments, permission isolation, and audit trails.

ApprovaPlat aims to provide a runnable, verifiable, and sustainable reference implementation for new project evaluation, system modernization, and Flowable 8 adoption.

## Why ApprovaPlat

Many workflow projects demonstrate the basic engine APIs. A production approval platform must also keep engine state, business state, permissions, attachments, and audit records consistent under concurrent and exceptional conditions.

ApprovaPlat treats these concerns as part of the core implementation:

- Flowable 8 process execution integrated with Spring Boot 4
- Process modeling, deployment, versioning, and controlled activation
- Request submission, task approval, claim, transfer, rejection, and withdrawal
- Sequential and parallel multi-instance approval
- Candidate users, candidate groups, and object-level permission checks
- Immutable form snapshots and validated process variables
- Attachment access control, storage quotas, and lifecycle management
- Copy notifications, approval comments, operation logs, and audit trails
- Transaction consistency between workflow engine data and business data
- Real-service integration tests and production release gates

## Technology Stack

| Area | Technology |
| --- | --- |
| Backend | Spring Boot 4.0.6, Java 17 |
| Workflow | Flowable 8.0.0 |
| Persistence | MyBatis, MySQL 8 |
| Security | Spring Security, JWT, Redis |
| Frontend | Vue 3, Vite, Element Plus |
| Process designer | BPMN.js |
| Observability | Spring Boot Actuator, Micrometer, Prometheus |
| Verification | JUnit 5, Playwright, k6 |

## Repository Layout

```text
ApprovaPlat/
|- back/         Spring Boot backend and database scripts
|- vite/         Vue 3 frontend
|- deployment/   Production configuration and release gates
|- docs/         Architecture, installation, verification, and rollback guides
`- testcount/    Controlled local test-account documentation
```

## Requirements

- JDK 17 or later
- Maven 3.9 or later
- Node.js 20.12 or later
- npm 10 or later
- MySQL 8
- Redis 6 or later

## Build

Build the backend:

```powershell
cd back
mvn clean package -DskipTests
```

Build the frontend:

```powershell
cd vite
npm install
npm run build:prod
```

Database initialization, runtime configuration, first-administrator setup, deployment, verification, and rollback require the complete production procedure. Do not place credentials in tracked configuration files.

## Documentation

- [Flowable 8 production documentation](docs/workflow-production/README.md)
- [Fresh installation runbook](docs/workflow-production/04-fresh-install-runbook.md)
- [Release and rollback runbook](docs/workflow-production/05-release-rollback-runbook.md)
- [Flowable 8 database scripts](back/sql/flowable/README.md)
- [Release gate script](deployment/scripts/workflow-release-gate.sh)
- [Load and lifecycle verification](deployment/k6/README.md)

## License

ApprovaPlat is released under the [MIT License](LICENSE).
