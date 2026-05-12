# Project Guidance

## Project Overview

This repository contains a SaaS short link system built with Java 17, Spring Boot 3, Spring Cloud, MySQL sharding, Redis, RocketMQ, and a Vue 3 frontend console.

## Structure

```text
shortlink/
├── admin/        # User, group, and management APIs
├── project/      # Core short link, redirect, recycle bin, and statistics logic
├── gateway/      # API Gateway and token validation
├── aggregation/  # Combined deployment module
├── console-vue/  # Vue 3 management console
├── resources/    # Database schemas and shared resources
├── format/       # Formatting configuration
└── pom.xml       # Root Maven POM
```

## Common Commands

Build all backend modules:

```bash
./mvnw clean package
```

Build one module:

```bash
./mvnw clean package -pl project
```

Skip tests during packaging:

```bash
./mvnw clean package -DskipTests
```

Apply Java formatting:

```bash
./mvnw spotless:apply
```

Frontend commands from `console-vue/`:

```bash
pnpm install
pnpm dev
pnpm build
```

## Notes

- Java package and Maven groupId use `com.lin1473.shortlink`.
- `ShortLink` in class and API names is a business term and should remain unless the product name changes.
- Check local database, Redis, Nacos, and RocketMQ settings before running services.
- Keep Apache License information intact when editing license-related files.
