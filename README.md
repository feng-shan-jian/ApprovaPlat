# ApprovaPlat

**English** | [Simplified Chinese](README.zh-CN.md)

An enterprise approval platform built with Flowable 8 and Spring Boot 4.

ApprovaPlat focuses on the engineering integration and production adoption of Flowable 8 in the Spring Boot 4 ecosystem. It aims to provide a complete approval application covering process design, request submission, task approval, multi-instance approval, rejection, copy notifications, attachments, permission isolation, and audit trails.

ApprovaPlat aims to provide a runnable, verifiable, and sustainable reference implementation for new project evaluation, system modernization, and Flowable 8 adoption.

## Core Features

- Flowable 8 process execution integrated with Spring Boot 4
- Process modeling, deployment, versioning, and controlled activation
- Request submission, task approval, claim, transfer, rejection, and withdrawal
- Sequential and parallel multi-instance approval
- Candidate users, candidate groups, and object-level permission checks
- Immutable form snapshots and validated process variables
- Attachment access control, storage quotas, and lifecycle management
- Copy notifications, approval comments, operation logs, and audit trails
- Transaction consistency between workflow engine data and business data

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
`- deployment/   Production configuration and release gates
```

## Requirements

- JDK 17 or later
- Maven 3.9 or later
- Node.js 20.12 or later
- npm 10 or later
- MySQL 8
- Redis 6 or later

## Test Accounts

The following accounts are intended only for the local ApprovaPlat test environment:

| Role | Username | Password |
| --- | --- | --- |
| Administrator | `admin` | `aprova**` |
| Document controller | `document_controller` | `aprova**` |

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

## License

[MIT License](LICENSE)
