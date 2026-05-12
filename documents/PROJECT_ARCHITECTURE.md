# 短链接系统项目架构笔记

## 项目概述

这是一个 SaaS 短链接系统，采用微服务架构。系统分为 4 个核心模块，通过 Gateway 进行统一入口和路由转发。

---

## 项目模块结构

### 1. Gateway（网关服务）- 8000 端口

**职责：**
- 统一请求入口与路由转发
- 负载均衡
- Token 校验过滤器

**详细说明：**

**统一请求入口与路由转发**
- 前端发来的所有请求都会被网关接收
- 网关根据请求路径转发给相应的微服务
- 路由规则配置在 `application-aggregation.yaml` 中：
  - `/api/short-link/admin/**` → 转发到 aggregation（Admin 模块）
  - `/api/short-link/**` → 转发到 aggregation（Project 模块）

**负载均衡**
- 使用 Spring Cloud LoadBalancer + Nacos 服务发现
- 当有多个服务实例时，自动选择其中一个进行转发
- 在 aggregation 模式下，只有一个 aggregation 实例，所以负载均衡作用不大
- 在微服务模式下（dev profile），可以启动多个 admin 和 project 实例，网关会自动负载均衡

**Token 校验过滤器**
- 设置登录、注册请求为白名单：
  - `/api/short-link/admin/v1/user/login`
  - `/api/short-link/admin/v1/user/has-username`
- 对非白名单请求进行 Token 校验
- 校验逻辑：检查 Redis 中存储的用户信息与请求中的 Token 是否一致
- 用户登录时，系统会将用户信息存储到 Redis，后续请求需要携带对应的 Token

---

### 2. Admin（后管服务）- 包含在 Aggregation 中

**职责：**
- 用户管理（登录、注册、用户信息维护）
- 短链接分组管理
- 调用 Project 服务获取短链接数据

**核心功能：**
- 用户登录/注册：生成 JWT Token，存储用户信息到 Redis
- 分组管理：创建、删除、修改短链接分组
- 短链接管理：通过 FeignClient 调用 Project 服务
- 统计数据查询：通过 FeignClient 调用 Project 服务获取统计信息

**如何调用 Project 服务？**

使用 OpenFeign 进行远程调用：

```java
@FeignClient(
    value = "short-link-project",
    url = "${aggregation.remote-url:}",
    configuration = OpenFeignConfiguration.class
)
public interface ShortLinkActualRemoteService {
    @PostMapping("/api/short-link/v1/create")
    Result<ShortLinkCreateRespDTO> createShortLink(@RequestBody ShortLinkCreateReqDTO requestParam);
    // ... 其他方法
}
```

**工作原理：**
- 在 aggregation 模式下：`url = "http://127.0.0.1:8003"`，直接调用本地 aggregation 实例
- 在微服务模式下：`url` 为空，通过 Nacos 服务发现获取 Project 服务地址，然后发起 HTTP 请求

---

### 3. Project（中台服务）- 包含在 Aggregation 中

**职责：**
- 短链接的核心业务逻辑
- 处理短链接的创建、查询、更新、删除
- 处理短链接重定向
- 统计数据收集和查询

**核心功能模块：**

1. **短链接创建**
   - 生成短链接编码
   - 验证分组权限
   - 存储到数据库（ShardingSphere 分片）
   - 缓存到 Redis

2. **短链接查询**
   - 分页查询短链接列表
   - 查询分组内短链接总数
   - 从 Redis 缓存或数据库获取

3. **短链接重定向**
   - 根据短链接编码查询原始 URL
   - 记录访问统计数据
   - 重定向到原始 URL

4. **统计数据**
   - 记录每次访问（IP、设备、浏览器、操作系统等）
   - 提供按时间、分组、短链接维度的统计查询
   - 支持访问记录详情查询

5. **回收站管理**
   - 删除短链接到回收站
   - 恢复短链接
   - 永久删除短链接

---

### 4. Aggregation（聚合服务）- 8003 端口

**职责：**
- 将 Admin 和 Project 两个模块聚合成一个 Fat JAR
- 提供统一的服务入口

**为什么需要 Aggregation？**

这是一个部署策略的选择：

- **Aggregation 模式**（当前使用）：
  - 将 Admin 和 Project 打包成一个 JAR
  - 启动一个 Aggregation 实例即可运行整个系统
  - 优点：部署简单，开发调试方便
  - 缺点：无法独立扩展 Admin 或 Project

- **微服务模式**（可选）：
  - 分别启动 Admin 和 Project 为独立服务
  - 通过 Nacos 服务发现
  - 优点：可以独立扩展，高可用
  - 缺点：部署复杂，运维成本高

---

## 为什么需要 Admin 和 Project 分开？

### 职责分离

1. **Admin 模块**
   - 处理用户相关的业务
   - 处理分组管理
   - 是一个"管理层"的概念

2. **Project 模块**
   - 处理短链接的核心业务
   - 是一个"业务层"的概念

### 独立扩展

- 如果短链接访问量很大，可以单独扩展 Project 模块
- 如果用户管理操作频繁，可以单独扩展 Admin 模块
- 两者可以独立部署、独立扩展

### 代码解耦

- Admin 不直接依赖 Project 的数据库
- Admin 通过 FeignClient 调用 Project 的 HTTP 接口
- 降低模块间的耦合度

### 业务清晰

- Admin：管理后台相关业务
- Project：短链接核心业务
- 职责明确，便于维护和扩展

---

## 请求流程示例

### 场景：前端创建短链接

```
1. 前端发送请求
   POST /api/short-link/v1/create
   Header: Authorization: Bearer <token>

2. Gateway 接收请求
   ├─ TokenValidate 过滤器校验 Token
   │  └─ 检查 Redis 中的用户信息与 Token 是否一致
   └─ 路由规则匹配：/api/short-link/** → aggregation
      └─ 转发到 http://127.0.0.1:8003/api/short-link/v1/create

3. Aggregation 处理请求
   ├─ Admin 模块接收请求
   ├─ 调用 Project 模块的 FeignClient
   │  └─ ShortLinkActualRemoteService.createShortLink()
   └─ Project 模块处理
      ├─ 生成短链接编码
      ├─ 验证分组权限
      ├─ 存储到数据库（ShardingSphere）
      ├─ 缓存到 Redis
      └─ 返回短链接信息

4. Gateway 返回响应给前端
   └─ 前端收到短链接信息，更新 UI
```

### 场景：前端用户登录

```
1. 前端发送请求
   POST /api/short-link/admin/v1/user/login
   Body: { username, password }

2. Gateway 接收请求
   ├─ TokenValidate 过滤器检查白名单
   │  └─ /api/short-link/admin/v1/user/login 在白名单中，不需要 Token
   └─ 转发到 aggregation

3. Aggregation 处理请求
   ├─ Admin 模块的 UserController.login()
   ├─ 验证用户名和密码
   ├─ 生成 JWT Token
   ├─ 将用户信息存储到 Redis
   └─ 返回 Token

4. Gateway 返回响应给前端
   └─ 前端保存 Token，后续请求都在 Header 中携带
```

---

## 技术栈

| 组件 | 技术 | 作用 |
|------|------|------|
| 网关 | Spring Cloud Gateway | 请求路由、负载均衡 |
| 服务发现 | Nacos | 服务注册与发现 |
| 远程调用 | OpenFeign | Admin 调用 Project |
| 数据库 | MySQL + ShardingSphere | 数据持久化、自动分片 |
| 缓存 | Redis + Redisson | 缓存、限流、Session 存储 |
| 限流 | Sentinel | 流量控制 |
| ORM | MyBatis Plus | 数据库操作 |

---

## 部署模式

### 当前模式（Aggregation）

```
前端 → Gateway (8000) → Aggregation (8003)
                        ├─ Admin 模块
                        ├─ Project 模块
                        ├─ MySQL
                        └─ Redis
```

**启动命令：**
```bash
# 启动 Gateway
java -jar shortlink-gateway.jar

# 启动 Aggregation
java -jar shortlink-aggregation.jar
```

### 可选模式（微服务）

```
前端 → Gateway (8000) → Admin (8001)
                     → Project (8002)
                        ├─ MySQL
                        └─ Redis
```

**启动命令：**
```bash
# 启动 Gateway
java -jar shortlink-gateway.jar --spring.profiles.active=dev

# 启动 Admin
java -jar shortlink-admin.jar

# 启动 Project
java -jar shortlink-project.jar
```

---

## 关键配置

### Gateway 配置（application-aggregation.yaml）

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: short-link-admin-aggregation
          uri: lb://short-link-aggregation/api/short-link/admin/**
          predicates:
            - Path=/api/short-link/admin/**
          filters:
            - name: TokenValidate
              args:
                whitePathList:
                  - /api/short-link/admin/v1/user/login
                  - /api/short-link/admin/v1/user/has-username

        - id: short-link-project-aggregation
          uri: lb://short-link-aggregation/api/short-link/**
          predicates:
            - Path=/api/short-link/**
          filters:
            - name: TokenValidate
```

### Aggregation 配置（application.yaml）

```yaml
server:
  port: 8003

spring:
  application:
    name: short-link-aggregation
  datasource:
    url: jdbc:shardingsphere:classpath:shardingsphere-config-dev.yaml
  data:
    redis:
      host: 127.0.0.1
      port: 6379

aggregation:
  remote-url: http://127.0.0.1:${server.port}
```

---

## 总结

| 模块 | 端口 | 职责 | 依赖 |
|------|------|------|------|
| Gateway | 8000 | 请求入口、路由、Token 校验 | Nacos |
| Admin | 8003* | 用户管理、分组管理 | Project (FeignClient) |
| Project | 8003* | 短链接核心业务 | MySQL、Redis |
| Aggregation | 8003 | 聚合 Admin + Project | MySQL、Redis |

*在 Aggregation 模式下，Admin 和 Project 都运行在 8003 端口的同一个 JVM 进程中。
