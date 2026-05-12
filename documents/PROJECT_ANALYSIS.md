# 项目分析报告

## 一、项目概述

本项目是一个基于 Spring Cloud 微服务架构的 SaaS 短链接系统，面向需要 URL 缩短和访问统计服务的企业或个人用户。系统支持短链接的创建、分组管理、跳转重定向，以及 UV/UIP/PV 等多维度访问统计，具备高并发处理能力，通过 ShardingSphere 分库分表支撑大规模数据存储，并提供 Vue 3 管理控制台进行可视化操作。

---

## 二、目录结构

| 目录 | 说明 |
|---|---|
| `admin/` | 管理服务：用户注册/登录、租户管理、短链接分组管理 |
| `project/` | 核心服务：短链接创建、跳转重定向、访问统计 |
| `gateway/` | API 网关：JWT 鉴权、请求路由、用户上下文注入 |
| `aggregation/` | 聚合服务：将 admin + project 合并为单体 JAR 部署，适合开发/测试环境 |
| `console-vue/` | Vue 3 前端管理控制台 |
| `resources/` | 共享资源：数据库建表 SQL 脚本 |
| `format/` | 代码格式化配置（Spotless + Apache 2.0 License Header） |

---

## 三、技术架构

### 技术栈

| 分类 | 技术 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 核心框架 | Spring Boot 3.0.7、Spring Cloud 2022.0.3、Spring Cloud Alibaba 2022.0.0.0-RC2 |
| 数据库 | MySQL + ShardingSphere 5.3.2（分库分表） |
| ORM | MyBatis-Plus 3.5.3.1 |
| 缓存 | Redis + Redisson 3.27.2（分布式锁） |
| 消息队列 | RocketMQ 2.2.3（异步统计） |
| 服务注册/发现 | Nacos |
| 网关 | Spring Cloud Gateway |
| 限流 | Alibaba Sentinel |
| 认证 | JWT（jjwt 0.9.1） |
| 工具库 | Hutool、Guava、Fastjson2、Jsoup、EasyExcel、Dozer |
| 前端 | Vue 3、Vite、Element Plus、Vuex、Vue Router、Axios |
| 构建 | Maven（Spotless 代码格式化） |

### 架构设计

```
浏览器/客户端
     │
     ▼
┌─────────────┐
│   Gateway   │  端口 8000 — JWT 鉴权、路由转发、用户上下文注入
└──────┬──────┘
       │ Nacos 服务发现
       ▼
┌─────────────────────────────────┐
│         Aggregation             │  端口 8003（开发推荐）
│  ┌──────────┐  ┌─────────────┐  │
│  │  Admin   │  │   Project   │  │
│  └──────────┘  └─────────────┘  │
└─────────────────────────────────┘
       │                │
       ▼                ▼
    MySQL            RocketMQ
  (ShardingSphere)  (异步统计消费)
       │
       ▼
     Redis
  (缓存 + 分布式锁 + JWT存储)
```

**两种部署模式：**
- **微服务模式**：admin（8002）+ project（8001）+ gateway（8000）分别独立部署，通过 Nacos 互相发现
- **聚合模式（推荐开发用）**：aggregation（8003）+ gateway（8000），admin 和 project 合并在一个进程内

### 分库分表策略（ShardingSphere）

**admin 模块**（分片键：`username`，HASH_MOD 16 分片）：
- `t_user_0` ~ `t_user_15`
- `t_group_0` ~ `t_group_15`
- `phone`、`mail` 字段 AES 加密存储

**project 模块**：
- `t_link_0` ~ `t_link_15`：分片键 `gid`
- `t_link_goto_0` ~ `t_link_goto_15`：分片键 `full_short_url`
- `t_link_stats_today_0` ~ `t_link_stats_today_15`：分片键 `gid`，与 `t_link` 绑定表

---

## 四、功能模块

### 1. Admin 服务（端口 8002）

**职责：** 用户体系与分组管理

| 包 | 功能 |
|---|---|
| `controller/` | 用户注册、登录、信息修改；分组 CRUD |
| `service/` | 业务逻辑，含用户登录 Token 写入 Redis |
| `dao/` | MyBatis-Plus Mapper，操作 `t_user`、`t_group` 分片表 |
| `remote/` | Feign 客户端，调用 project 服务获取分组短链接数量 |
| `config/` | MyBatis-Plus 配置、用户上下文过滤器 |
| `toolkit/` | 工具类（Hash、随机字符串等） |

**对外暴露：** REST API，路径前缀 `/api/short-link/admin/v1/`

---

### 2. Project 服务（端口 8001）

**职责：** 短链接核心逻辑

| 包 | 功能 |
|---|---|
| `controller/` | 短链接 CRUD、跳转重定向、访问统计查询 |
| `service/` | 短链接生成（Base62 编码）、缓存优先重定向、布隆过滤器防穿透 |
| `dao/` | 操作 `t_link`、`t_link_goto`、`t_link_stats_today` 等分片表 |
| `mq/` | RocketMQ Producer（发布统计事件）+ Consumer（消费统计事件写库） |
| `handler/` | 自定义异常处理、Sentinel 限流处理 |
| `initialize/` | 应用启动时初始化布隆过滤器 |
| `toolkit/` | IP 提取（代理感知）、UA 解析、高德地图 API 调用 |

**重定向流程（缓存优先）：**
1. Redis 命中 → 直接 302 跳转
2. 布隆过滤器未命中 → 返回 404
3. 空值缓存命中 → 返回 404
4. 加分布式锁 → 查 `t_link_goto` 获取 gid → 查 `t_link` 验证有效期 → 写缓存

**统计指标：** PV、UV（30 天 Cookie + Redis Set）、UIP（真实 IP）、设备类型、浏览器、OS、地区

---

### 3. Gateway 服务（端口 8000）

**职责：** 统一入口、鉴权、路由

| 包 | 功能 |
|---|---|
| `filter/` | `TokenValidateGatewayFilterFactory`：验证 JWT，注入 `userId`、`realName` 请求头 |
| `config/` | 路由规则配置（聚合模式 / 微服务模式切换） |
| `dto/` | 公共响应结构 |

**路由规则（聚合模式）：**
- `/api/short-link/admin/**` → `short-link-aggregation`（白名单：登录、用户名检查）
- `/api/short-link/**` → `short-link-aggregation`（需鉴权）

---

### 4. Aggregation 服务（端口 8003）

**职责：** 开发/测试环境单体部署

通过 `@SpringBootApplication(scanBasePackages=...)` 和 `@MapperScan` 同时扫描 admin 和 project 的包，将两个服务合并为一个可运行 JAR。`aggregation.remote-url` 配置为本机地址，Feign 调用不走网络。

---

### 5. Console-Vue 前端

**职责：** 管理控制台 UI

| 目录 | 功能 |
|---|---|
| `views/` | 页面组件（登录、短链接列表、统计图表等） |
| `api/` | Axios 封装的后端接口调用 |
| `store/` | Vuex 状态管理（用户信息、Token） |
| `router/` | Vue Router 路由配置（含登录守卫） |
| `components/` | 公共组件 |

---

## 五、启动指南

### 1. 环境准备

| 工具 | 版本要求 |
|---|---|
| JDK | 17 |
| Maven | 3.6+ |
| Docker | 任意最新版（用于启动中间件） |
| Node.js | 16+（前端可选） |

---

### 2. 启动基础中间件（Docker）

**MySQL**
```bash
docker run -d \
  --name shortlink-mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=link \
  mysql:8.0
```

**Redis**
```bash
docker run -d \
  --name shortlink-redis \
  -p 6379:6379 \
  redis:7 \
  redis-server --requirepass 123456
```

**Nacos**
```bash
docker run -d \
  --name shortlink-nacos \
  -p 8848:8848 \
  -e MODE=standalone \
  nacos/nacos-server:v2.2.3
```

**RocketMQ**
```bash
# NameServer
docker run -d \
  --name shortlink-rocketmq-namesrv \
  -p 9876:9876 \
  apache/rocketmq:5.1.0 \
  sh mqnamesrv

# Broker
docker run -d \
  --name shortlink-rocketmq-broker \
  -p 10911:10911 -p 10909:10909 \
  --link shortlink-rocketmq-namesrv:namesrv \
  -e "NAMESRV_ADDR=namesrv:9876" \
  apache/rocketmq:5.1.0 \
  sh mqbroker -n namesrv:9876
```

---

### 3. 修改配置文件

#### MySQL 配置

编辑以下文件中的 `username` 和 `password`（默认已是 `root`/`root`，如你的 MySQL 密码不同需修改）：

- `admin/src/main/resources/shardingsphere-config-dev.yaml`
- `project/src/main/resources/shardingsphere-config-dev.yaml`
- `aggregation/src/main/resources/shardingsphere-config-dev.yaml`

```yaml
# 修改这两行
username: root        # 你的 MySQL 用户名
password: root        # 你的 MySQL 密码
```

数据库名默认为 `link`，确保已创建：
```sql
CREATE DATABASE IF NOT EXISTS link DEFAULT CHARACTER SET utf8mb4;
```

#### Redis 配置

编辑以下文件（默认密码 `123456`，如不同需修改）：

- `admin/src/main/resources/application.yaml`
- `project/src/main/resources/application.yaml`
- `gateway/src/main/resources/application.yaml`
- `aggregation/src/main/resources/application.yaml`

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1    # Redis 地址
      port: 6379
      password: 123456   # Redis 密码
```

#### Nacos 配置

以上各 `application.yaml` 中均有：
```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848   # Nacos 地址，默认无需修改
```

#### RocketMQ 配置（project / aggregation）

```yaml
rocketmq:
  name-server: 127.0.0.1:9876   # RocketMQ NameServer 地址
```

---

### 4. 初始化数据库

```bash
# 连接 MySQL 后执行
mysql -u root -p link < resources/database/link.sql
mysql -u root -p link < resources/database/link-data.sql
```

---

### 5. 构建项目

```bash
./mvnw clean package -DskipTests
```

---

### 6. 启动服务

**推荐：聚合模式（开发用，只需启动 2 个服务）**

按以下顺序启动：

```bash
# 1. 先启动聚合服务（admin + project 合并）
java -jar aggregation/target/shortlink-aggregation.jar

# 2. 再启动网关
java -jar gateway/target/shortlink-gateway.jar
```

**微服务模式（生产用，启动 3 个服务）**

```bash
# 1. Admin 服务
java -jar admin/target/shortlink-admin.jar

# 2. Project 服务
java -jar project/target/shortlink-project.jar

# 3. Gateway（需修改 gateway/src/main/resources/application.yaml 中 profiles.active 为 dev）
java -jar gateway/target/shortlink-gateway.jar
```

---

### 7. 启动前端（可选）

```bash
cd console-vue
pnpm install
pnpm run dev
```

前端默认访问地址：`http://localhost:5173`

---

### 8. 验证

| 服务 | 验证方式 |
|---|---|
| 聚合服务 | 访问 `http://localhost:8003/actuator/health`（如有）或查看启动日志无报错 |
| 网关 | 访问 `http://localhost:8000/api/short-link/admin/v1/user/has-username?username=test` 应返回 JSON |
| 前端 | 浏览器打开 `http://localhost:5173` 看到登录页 |
| Nacos 控制台 | `http://localhost:8848/nacos`（默认账号 nacos/nacos），确认服务已注册 |

---

> **注意事项**
> - ShardingSphere 配置文件分 `dev` 和 `prod` 两套，默认使用 `dev`，生产部署前请核对 `shardingsphere-config-prod.yaml`
> - 高德地图 Key（`amap-key`）用于 IP 归属地查询，如需真实地理统计请替换为自己的 Key
> - 短链接默认域名配置为 `nurl.ink:8001`（project）/ `nurl.ink:8003`（aggregation），本地测试可改为 `localhost:端口`
