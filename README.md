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

## License

This project keeps the Apache License 2.0 license file. See `LICENSE` for details.
