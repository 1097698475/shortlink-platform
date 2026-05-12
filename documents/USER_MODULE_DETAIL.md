# 用户模块详细讲解

## 模块概述

用户模块是 Admin 服务的核心功能，负责用户的注册、登录、信息管理等。该模块采用了多种高并发处理技术，包括分布式锁、布隆过滤器、Redis 缓存等。

---

## 接口列表

### 1. 查询用户信息

**接口：** `GET /api/short-link/admin/v1/user/{username}`

**功能：** 根据用户名查询用户信息（脱敏版本）

**参数：**
- `username` (Path): 用户名

**返回：** 用户信息（脱敏）
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "username": "admin",
    "realName": "张三",
    "phone": "****5678",
    "mail": "****@example.com"
  }
}
```

**实现方法：** `UserServiceImpl.getUserByUsername()`

---

### 2. 查询无脱敏用户信息

**接口：** `GET /api/short-link/admin/v1/actual/user/{username}`

**功能：** 根据用户名查询用户信息（无脱敏，完整信息）

**参数：**
- `username` (Path): 用户名

**返回：** 完整用户信息

**实现方法：** `UserServiceImpl.getUserByUsername()` + `BeanUtil.toBean()`

**注意：** 这个接口返回完整信息，应该只有当前用户或管理员才能调用

---

### 3. 检查用户名是否存在

**接口：** `GET /api/short-link/admin/v1/user/has-username`

**功能：** 检查用户名是否存在（用于注册时检查）

**参数：**
- `username` (Query): 用户名

**返回：**
```json
{
  "code": 0,
  "message": "success",
  "data": true  // true: 用户名不存在（可以注册），false: 用户名已存在
}
```

**实现方法：** `UserServiceImpl.hasUsername()`

**高并发处理：** 使用布隆过滤器（Bloom Filter）

---

### 4. 用户注册

**接口：** `POST /api/short-link/admin/v1/user`

**功能：** 注册新用户

**请求体：**
```json
{
  "username": "newuser",
  "password": "password123",
  "realName": "李四",
  "phone": "13800138000",
  "mail": "user@example.com"
}
```

**返回：**
```json
{
  "code": 0,
  "message": "success",
  "data": null
}
```

**实现方法：** `UserServiceImpl.register()`

**高并发处理：**
- 分布式锁（Redisson RLock）
- 布隆过滤器

---

### 5. 修改用户信息

**接口：** `PUT /api/short-link/admin/v1/user`

**功能：** 修改用户信息

**请求体：**
```json
{
  "username": "admin",
  "realName": "王五",
  "phone": "13900139000",
  "mail": "newemail@example.com"
}
```

**返回：**
```json
{
  "code": 0,
  "message": "success",
  "data": null
}
```

**实现方法：** `UserServiceImpl.update()`

**权限检查：** 只能修改当前登录用户的信息（通过 `UserContext.getUsername()` 验证）

---

### 6. 用户登录

**接口：** `POST /api/short-link/admin/v1/user/login`

**功能：** 用户登录，生成 Token

**请求体：**
```json
{
  "username": "admin",
  "password": "password123"
}
```

**返回：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

**实现方法：** `UserServiceImpl.login()`

**高并发处理：** Redis Hash 存储登录信息

---

### 7. 检查用户是否登录

**接口：** `GET /api/short-link/admin/v1/user/check-login`

**功能：** 检查用户是否已登录（验证 Token 有效性）

**参数：**
- `username` (Query): 用户名
- `token` (Query): Token

**返回：**
```json
{
  "code": 0,
  "message": "success",
  "data": true  // true: 已登录，false: 未登录或 Token 无效
}
```

**实现方法：** `UserServiceImpl.checkLogin()`

---

### 8. 用户退出登录

**接口：** `DELETE /api/short-link/admin/v1/user/logout`

**功能：** 用户退出登录

**参数：**
- `username` (Query): 用户名
- `token` (Query): Token

**返回：**
```json
{
  "code": 0,
  "message": "success",
  "data": null
}
```

**实现方法：** `UserServiceImpl.logout()`

---

## 数据库设计

### 用户表 (t_user)

```sql
CREATE TABLE t_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    real_name VARCHAR(255),
    phone VARCHAR(20),
    mail VARCHAR(255),
    del_flag INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by VARCHAR(255),
    update_by VARCHAR(255),
    del_time BIGINT
);
```

**字段说明：**
- `username`: 用户名（唯一）
- `password`: 密码（明文存储，实际应该加密）
- `real_name`: 真实姓名
- `phone`: 手机号
- `mail`: 邮箱
- `del_flag`: 删除标志（0: 未删除，1: 已删除）
- `del_time`: 删除时间戳

---

## 高并发处理技术

### 1. 布隆过滤器（Bloom Filter）

**用途：** 防止缓存穿透

**原理：**
- 用于快速判断用户名是否存在
- 避免每次都查询数据库
- 节省数据库查询压力

**配置：**
```java
@Bean
public RBloomFilter<String> userRegisterCachePenetrationBloomFilter(RedissonClient redissonClient) {
    RBloomFilter<String> cachePenetrationBloomFilter =
        redissonClient.getBloomFilter("userRegisterCachePenetrationBloomFilter");
    // 初始化：容量 1 亿，误差率 0.1%
    cachePenetrationBloomFilter.tryInit(100000000L, 0.001);
    return cachePenetrationBloomFilter;
}
```

**使用场景：**
```java
@Override
public Boolean hasUsername(String username) {
    // 如果 Bloom Filter 中不包含该用户名，说明用户名不存在
    return !userRegisterCachePenetrationBloomFilter.contains(username);
}
```

**工作流程：**
```
用户注册时：
1. 检查 Bloom Filter 中是否存在该用户名
2. 如果不存在，说明用户名可用
3. 注册成功后，将用户名添加到 Bloom Filter

查询用户名是否存在时：
1. 先查询 Bloom Filter
2. 如果 Bloom Filter 说不存在，直接返回 false（不存在）
3. 如果 Bloom Filter 说存在，再查询数据库确认
```

**优势：**
- 快速判断（O(1) 时间复杂度）
- 节省数据库查询
- 支持高并发

**劣势：**
- 有误差率（0.1%）
- 不支持删除操作

---

### 2. 分布式锁（Redisson RLock）

**用途：** 防止重复注册

**原理：**
- 使用 Redis 实现的分布式锁
- 确保同一用户名只能被注册一次
- 防止并发注册导致的重复数据

**使用场景：**
```java
@Transactional(rollbackFor = Exception.class)
@Override
public void register(UserRegisterReqDTO requestParam) {
    // 1. 先检查 Bloom Filter
    if (!hasUsername(requestParam.getUsername())) {
        throw new ClientException(USER_NAME_EXIST);
    }

    // 2. 获取分布式锁
    RLock lock = redissonClient.getLock(LOCK_USER_REGISTER_KEY + requestParam.getUsername());

    // 3. 尝试获取锁（非阻塞）
    if (!lock.tryLock()) {
        throw new ClientException(USER_NAME_EXIST);
    }

    try {
        // 4. 执行注册逻辑
        int inserted = baseMapper.insert(BeanUtil.toBean(requestParam, UserDO.class));
        if (inserted < 1) {
            throw new ClientException(USER_SAVE_ERROR);
        }

        // 5. 创建默认分组
        groupService.saveGroup(requestParam.getUsername(), "默认分组");

        // 6. 将用户名添加到 Bloom Filter
        userRegisterCachePenetrationBloomFilter.add(requestParam.getUsername());
    } catch (DuplicateKeyException ex) {
        throw new ClientException(USER_EXIST);
    } finally {
        // 7. 释放锁
        lock.unlock();
    }
}
```

**工作流程：**
```
并发注册同一用户名时：

请求1                          请求2
  ↓                              ↓
检查 Bloom Filter              检查 Bloom Filter
  ↓                              ↓
获取锁 ✓                        获取锁 ✗（等待或失败）
  ↓                              ↓
执行注册                        抛出异常
  ↓
释放锁
  ↓
请求2 获取锁 ✓
  ↓
执行注册 → 数据库唯一性约束 → 异常
```

**Redis 锁的 Key：**
```
short-link:lock_user-register:{username}
```

---

### 3. Redis Hash 存储登录信息

**用途：** 存储用户登录状态和 Token

**数据结构：**
```
Key: short-link:login:{username}
Value: Hash
  {
    token1: JSON(userDO),
    token2: JSON(userDO),
    ...
  }
```

**示例：**
```
Key: short-link:login:admin
Value:
  {
    "550e8400-e29b-41d4-a716-446655440000": "{\"id\":1,\"username\":\"admin\",\"realName\":\"张三\",...}",
    "660e8400-e29b-41d4-a716-446655440001": "{\"id\":1,\"username\":\"admin\",\"realName\":\"张三\",...}"
  }
```

**登录流程：**
```java
@Override
public UserLoginRespDTO login(UserLoginReqDTO requestParam) {
    // 1. 查询数据库验证用户名和密码
    UserDO userDO = baseMapper.selectOne(
        Wrappers.lambdaQuery(UserDO.class)
            .eq(UserDO::getUsername, requestParam.getUsername())
            .eq(UserDO::getPassword, requestParam.getPassword())
            .eq(UserDO::getDelFlag, 0)
    );

    if (userDO == null) {
        throw new ClientException("用户不存在");
    }

    // 2. 检查用户是否已登录
    Map<Object, Object> hasLoginMap = stringRedisTemplate.opsForHash()
        .entries(USER_LOGIN_KEY + requestParam.getUsername());

    if (CollUtil.isNotEmpty(hasLoginMap)) {
        // 用户已登录，刷新过期时间，返回现有 Token
        stringRedisTemplate.expire(USER_LOGIN_KEY + requestParam.getUsername(), 30L, TimeUnit.MINUTES);
        String token = hasLoginMap.keySet().stream()
            .findFirst()
            .map(Object::toString)
            .orElseThrow(() -> new ClientException("用户登录错误"));
        return new UserLoginRespDTO(token);
    }

    // 3. 用户未登录，生成新 Token
    String uuid = UUID.randomUUID().toString();
    stringRedisTemplate.opsForHash().put(
        USER_LOGIN_KEY + requestParam.getUsername(),
        uuid,
        JSON.toJSONString(userDO)
    );

    // 4. 设置过期时间（30 分钟）
    stringRedisTemplate.expire(USER_LOGIN_KEY + requestParam.getUsername(), 30L, TimeUnit.MINUTES);

    return new UserLoginRespDTO(uuid);
}
```

**Token 验证流程：**
```java
@Override
public Boolean checkLogin(String username, String token) {
    // 直接查询 Redis Hash 中是否存在该 Token
    return stringRedisTemplate.opsForHash()
        .get(USER_LOGIN_KEY + username, token) != null;
}
```

**优势：**
- 快速验证 Token（O(1) 时间复杂度）
- 支持多个 Token（同一用户可以在多个设备登录）
- 自动过期（30 分钟）

---

## 高并发场景分析

### 场景1：大量用户同时注册

**问题：** 数据库压力大，可能出现重复注册

**解决方案：**
1. **Bloom Filter** - 快速判断用户名是否存在，减少数据库查询
2. **分布式锁** - 确保同一用户名只能被注册一次
3. **数据库唯一性约束** - 最后一道防线

**流程：**
```
100 个请求同时注册 "admin"
  ↓
Bloom Filter 检查 → 都通过
  ↓
分布式锁 → 只有 1 个获取成功，其他 99 个失败
  ↓
成功的请求执行注册
  ↓
其他 99 个请求抛出异常
```

### 场景2：大量用户同时登录

**问题：** 数据库压力大，Token 生成和验证频繁

**解决方案：**
1. **Redis Hash** - 快速存储和验证 Token
2. **自动过期** - 30 分钟自动清理过期 Token

**流程：**
```
1000 个用户同时登录
  ↓
数据库验证用户名和密码（可能有缓存）
  ↓
生成 Token 存储到 Redis Hash
  ↓
返回 Token 给前端
  ↓
后续请求验证 Token 时，直接查询 Redis（不查数据库）
```

### 场景3：大量请求检查用户名是否存在

**问题：** 数据库查询压力大

**解决方案：**
1. **Bloom Filter** - 快速判断，避免数据库查询

**流程：**
```
10000 个请求检查用户名是否存在
  ↓
Bloom Filter 检查（内存操作，非常快）
  ↓
返回结果（不查数据库）
```

---

## 相关工具和技术

| 工具/技术 | 用途 | 说明 |
|---------|------|------|
| **Redisson** | 分布式锁、Bloom Filter | Redis 客户端库 |
| **StringRedisTemplate** | Redis 操作 | Spring Data Redis 提供 |
| **MyBatis Plus** | ORM 框架 | 数据库操作 |
| **Hutool** | 工具库 | Bean 转换、UUID 生成等 |
| **FastJSON2** | JSON 序列化 | 用户信息序列化存储 |
| **Lombok** | 代码生成 | @Data、@RequiredArgsConstructor 等 |
| **Spring Transaction** | 事务管理 | @Transactional 注解 |

---

## 关键代码片段

### 1. 注册流程（完整）

```java
@Transactional(rollbackFor = Exception.class)
@Override
public void register(UserRegisterReqDTO requestParam) {
    // 第一层防护：Bloom Filter 快速判断
    if (!hasUsername(requestParam.getUsername())) {
        throw new ClientException(USER_NAME_EXIST);
    }

    // 第二层防护：分布式锁
    RLock lock = redissonClient.getLock(LOCK_USER_REGISTER_KEY + requestParam.getUsername());
    if (!lock.tryLock()) {
        throw new ClientException(USER_NAME_EXIST);
    }

    try {
        // 第三层防护：数据库唯一性约束
        int inserted = baseMapper.insert(BeanUtil.toBean(requestParam, UserDO.class));
        if (inserted < 1) {
            throw new ClientException(USER_SAVE_ERROR);
        }

        // 创建默认分组
        groupService.saveGroup(requestParam.getUsername(), "默认分组");

        // 更新 Bloom Filter
        userRegisterCachePenetrationBloomFilter.add(requestParam.getUsername());
    } catch (DuplicateKeyException ex) {
        throw new ClientException(USER_EXIST);
    } finally {
        lock.unlock();
    }
}
```

### 2. 登录流程（完整）

```java
@Override
public UserLoginRespDTO login(UserLoginReqDTO requestParam) {
    // 1. 数据库验证
    LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
            .eq(UserDO::getUsername, requestParam.getUsername())
            .eq(UserDO::getPassword, requestParam.getPassword())
            .eq(UserDO::getDelFlag, 0);
    UserDO userDO = baseMapper.selectOne(queryWrapper);

    if (userDO == null) {
        throw new ClientException("用户不存在");
    }

    // 2. 检查是否已登录
    Map<Object, Object> hasLoginMap = stringRedisTemplate.opsForHash()
        .entries(USER_LOGIN_KEY + requestParam.getUsername());

    if (CollUtil.isNotEmpty(hasLoginMap)) {
        // 已登录，刷新过期时间
        stringRedisTemplate.expire(USER_LOGIN_KEY + requestParam.getUsername(), 30L, TimeUnit.MINUTES);
        String token = hasLoginMap.keySet().stream()
            .findFirst()
            .map(Object::toString)
            .orElseThrow(() -> new ClientException("用户登录错误"));
        return new UserLoginRespDTO(token);
    }

    // 3. 未登录，生成新 Token
    String uuid = UUID.randomUUID().toString();
    stringRedisTemplate.opsForHash().put(
        USER_LOGIN_KEY + requestParam.getUsername(),
        uuid,
        JSON.toJSONString(userDO)
    );
    stringRedisTemplate.expire(USER_LOGIN_KEY + requestParam.getUsername(), 30L, TimeUnit.MINUTES);

    return new UserLoginRespDTO(uuid);
}
```

### 3. Token 验证流程（Gateway 中使用）

```java
@Override
public Boolean checkLogin(String username, String token) {
    return stringRedisTemplate.opsForHash()
        .get(USER_LOGIN_KEY + username, token) != null;
}
```

---

## 总结

| 方面 | 说明 |
|------|------|
| **接口数量** | 8 个 |
| **核心功能** | 注册、登录、查询、修改、退出 |
| **高并发处理** | Bloom Filter、分布式锁、Redis Hash |
| **缓存策略** | Bloom Filter（用户名）、Redis Hash（登录信息） |
| **数据库** | MySQL（t_user 表） |
| **Redis 使用** | 登录信息存储、分布式锁、Bloom Filter |
| **事务处理** | 注册时使用 @Transactional 保证原子性 |
| **Token 过期** | 30 分钟自动过期 |
| **并发安全** | 三层防护（Bloom Filter、分布式锁、数据库约束） |
