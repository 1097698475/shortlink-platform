# SaaS Short Link System

A short link platform built with Java 17, Spring Boot 3, Spring Cloud, MySQL sharding, Redis, RocketMQ, and a Vue 3 management console.

## Modules

- `admin`: user, group, and console-side management APIs.
- `project`: core short link creation, redirect, recycle bin, and statistics logic.
- `gateway`: Spring Cloud Gateway routing and token validation.
- `aggregation`: combined deployment module for admin and project services.
- `console-vue`: Vue 3 frontend console.

## Tech Stack

- Backend: Java 17, Spring Boot 3.0.7, Spring Cloud 2022.0.3
- Database: MySQL with ShardingSphere
- Cache and locks: Redis, Redisson
- Message queue: RocketMQ
- Frontend: Vue 3, Vite, Element Plus
- Build: Maven

## Quick Start

Build backend modules:

```bash
./mvnw clean package -DskipTests
```

Build a single backend module:

```bash
./mvnw clean package -pl project -DskipTests
```

Run the frontend:

```bash
cd console-vue
pnpm install
pnpm dev
```

## Configuration

Review the following files before running locally:

- `admin/src/main/resources/application.yaml`
- `project/src/main/resources/application.yaml`
- `gateway/src/main/resources/application*.yaml`
- `aggregation/src/main/resources/application.yaml`
- ShardingSphere configuration files under module resources

Set local MySQL, Redis, Nacos, and RocketMQ connection details according to your environment.

## Local Multi-Instance Mode

Gateway is the single public entrypoint. `admin` and `project` can be started with multiple ports and registered to Nacos under the same service names:

```bash
./scripts/start-local-instances.sh
```

Default local layout:

```text
gateway: 8000
project: 8001, 8101
admin:   8002, 8102
```

Check instances in the Nacos console:

```text
http://localhost:8848/nacos
```

The gateway routes use `lb://short-link-admin` and `lb://short-link-project`, so traffic is load-balanced through Nacos service discovery.

## Nacos Config

`gateway`, `admin`, and `project` now support Nacos Config with local YAML as fallback. Create these Data IDs in Nacos when you want to manage config centrally:

```text
short-link-gateway.yaml
short-link-admin.yaml
short-link-project.yaml
```

Use group `DEFAULT_GROUP` and YAML format. Keep local `application.yaml` for startup bootstrap and move environment-specific values such as MySQL, Redis, RocketMQ, flow-limit, and whitelist settings into Nacos when deploying distributed instances.

## License

This project keeps the Apache License 2.0 license file. See `LICENSE` for details.
