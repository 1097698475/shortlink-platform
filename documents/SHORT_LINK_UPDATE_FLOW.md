# 更新短链接流程详解

## 流程概述

更新短链接是项目的核心功能之一。系统根据是否修改分组（gid）提供了两种更新策略：
1. **同分组更新**：直接更新原记录（简单快速）
2. **跨分组更新**：逻辑删除旧记录 + 插入新记录（保留历史数据）

---

## 数据库设计

### 涉及的表

#### 1. t_link 表（短链接表）

```sql
CREATE TABLE t_link (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    domain VARCHAR(255),              -- 域名
    short_uri VARCHAR(255),           -- 短链接后缀
    full_short_url VARCHAR(255),      -- 完整短链接
    origin_url LONGTEXT,              -- 原始链接
    gid VARCHAR(255),                 -- 分组标识
    enable_status INT DEFAULT 0,      -- 启用状态（0: 启用，1: 未启用）
    valid_date_type INT,              -- 有效期类型（0: 永久，1: 自定义）
    valid_date DATETIME,              -- 有效期
    describe VARCHAR(255),            -- 描述
    favicon VARCHAR(255),             -- 网站图标
    total_pv INT DEFAULT 0,           -- 历史 PV
    total_uv INT DEFAULT 0,           -- 历史 UV
    total_uip INT DEFAULT 0,          -- 历史 UIP
    del_flag INT DEFAULT 0,           -- 删除标志（0: 未删除，1: 已删除）
    del_time BIGINT,                  -- 删除时间戳
    ...
);
```

#### 2. t_link_goto 表（短链接跳转表）

```sql
CREATE TABLE t_link_goto (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    gid VARCHAR(255),                 -- 分组标识
    full_short_url VARCHAR(255)       -- 完整短链接
);
```

**为什么需要两个表？**
- `t_link`: 存储短链接的详细信息和统计数据
- `t_link_goto`: 用于快速查询短链接对应的分组（优化查询性能，避免全表扫描）

---

## 更新短链接流程

### 完整流程图

```
前端请求
  ↓
PUT /api/short-link/v1/update
Body: {
  "fullShortUrl": "nurl.ink:8003/abc123",
  "originGid": "group_old",
  "gid": "group_new",
  "originUrl": "https://www.newexample.com",
  "validDateType": 1,
  "validDate": "2026-12-31 23:59:59",
  "describe": "更新后的描述"
}
  ↓
ShortLinkController.updateShortLink()
  ↓
ShortLinkServiceImpl.updateShortLink()
  ├─ 1. 验证白名单（verificationWhitelist）
  ├─ 2. 查询原始短链接记录
  ├─ 3. 判断是否修改分组
  │  ├─ 同分组（originGid == gid）
  │  │  ├─ 构建更新条件
  │  │  ├─ 更新 t_link 表
  │  │  └─ 更新缓存
  │  └─ 跨分组（originGid != gid）
  │     ├─ 获取读写锁（写锁）
  │     ├─ 逻辑删除旧记录（t_link）
  │     ├─ 插入新记录（t_link）
  │     ├─ 删除旧跳转记录（t_link_goto）
  │     ├─ 插入新跳转记录（t_link_goto）
  │     ├─ 释放锁
  │     └─ 更新缓存
  └─ 4. 返回成功
```

---

## 详细步骤

### 第1步：验证白名单

```java
verificationWhitelist(requestParam.getOriginUrl());
```

**作用：** 防止恶意用户将短链接更新为指向不安全网站的链接

**实现：** 与创建短链接时的白名单验证逻辑相同，检查原始 URL 的域名是否在白名单中

---

### 第2步：查询原始短链接记录

```java
LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
        .eq(ShortLinkDO::getGid, requestParam.getOriginGid())
        .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
        .eq(ShortLinkDO::getDelFlag, 0)
        .eq(ShortLinkDO::getEnableStatus, 0);
ShortLinkDO hasShortLinkDO = baseMapper.selectOne(queryWrapper);
if (hasShortLinkDO == null) {
    throw new ClientException("短链接记录不存在");
}
```

**查询条件：**
- `gid`: 原始分组标识
- `fullShortUrl`: 完整短链接
- `delFlag = 0`: 未删除
- `enableStatus = 0`: 已启用

**异常处理：** 如果记录不存在，抛出客户端异常

---

### 第3步：判断是否修改分组

#### 场景1：同分组更新（originGid == gid）

```java
if (Objects.equals(hasShortLinkDO.getGid(), requestParam.getGid())) {
    // 构建更新条件
    LambdaUpdateWrapper<ShortLinkDO> updateWrapper = Wrappers.lambdaUpdate(ShortLinkDO.class)
            .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
            .eq(ShortLinkDO::getGid, requestParam.getGid())
            .eq(ShortLinkDO::getDelFlag, 0)
            .eq(ShortLinkDO::getEnableStatus, 0)
            .set(Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType()),
                 ShortLinkDO::getValidDate, null);

    // 构建更新对象
    ShortLinkDO shortLinkDO = ShortLinkDO.builder()
            .domain(hasShortLinkDO.getDomain())
            .shortUri(hasShortLinkDO.getShortUri())
            .favicon(Objects.equals(requestParam.getOriginUrl(), hasShortLinkDO.getOriginUrl())
                     ? hasShortLinkDO.getFavicon()
                     : getFavicon(requestParam.getOriginUrl()))
            .createdType(hasShortLinkDO.getCreatedType())
            .gid(requestParam.getGid())
            .originUrl(requestParam.getOriginUrl())
            .describe(requestParam.getDescribe())
            .validDateType(requestParam.getValidDateType())
            .validDate(requestParam.getValidDate())
            .build();

    // 执行更新
    baseMapper.update(shortLinkDO, updateWrapper);
}
```

**关键点：**
- **直接更新**：在原记录上修改字段
- **保留字段**：`domain`、`shortUri`、`createdType` 保持不变
- **Favicon 优化**：如果原始 URL 未改变，复用旧的 Favicon，避免重复请求
- **永久有效期处理**：如果有效期类型为永久（0），将 `validDate` 设置为 `null`

---

#### 场景2：跨分组更新（originGid != gid）

```java
else {
    // 获取读写锁（写锁）
    RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(
        String.format(LOCK_GID_UPDATE_KEY, requestParam.getFullShortUrl())
    );
    RLock rLock = readWriteLock.writeLock();
    rLock.lock();

    try {
        // 1. 逻辑删除旧记录
        LambdaUpdateWrapper<ShortLinkDO> linkUpdateWrapper = Wrappers.lambdaUpdate(ShortLinkDO.class)
                .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                .eq(ShortLinkDO::getGid, hasShortLinkDO.getGid())
                .eq(ShortLinkDO::getDelFlag, 0)
                .eq(ShortLinkDO::getDelTime, 0L)
                .eq(ShortLinkDO::getEnableStatus, 0);
        ShortLinkDO delShortLinkDO = ShortLinkDO.builder()
                .delTime(System.currentTimeMillis())
                .build();
        delShortLinkDO.setDelFlag(1);
        baseMapper.update(delShortLinkDO, linkUpdateWrapper);

        // 2. 插入新记录
        ShortLinkDO shortLinkDO = ShortLinkDO.builder()
                .domain(createShortLinkDefaultDomain)
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .createdType(hasShortLinkDO.getCreatedType())
                .validDateType(requestParam.getValidDateType())
                .validDate(requestParam.getValidDate())
                .describe(requestParam.getDescribe())
                .shortUri(hasShortLinkDO.getShortUri())
                .enableStatus(hasShortLinkDO.getEnableStatus())
                .totalPv(hasShortLinkDO.getTotalPv())
                .totalUv(hasShortLinkDO.getTotalUv())
                .totalUip(hasShortLinkDO.getTotalUip())
                .fullShortUrl(hasShortLinkDO.getFullShortUrl())
                .favicon(Objects.equals(requestParam.getOriginUrl(), hasShortLinkDO.getOriginUrl())
                         ? hasShortLinkDO.getFavicon()
                         : getFavicon(requestParam.getOriginUrl()))
                .delTime(0L)
                .build();
        baseMapper.insert(shortLinkDO);

        // 3. 更新 t_link_goto 表
        LambdaQueryWrapper<ShortLinkGotoDO> linkGotoQueryWrapper = Wrappers.lambdaQuery(ShortLinkGotoDO.class)
                .eq(ShortLinkGotoDO::getFullShortUrl, requestParam.getFullShortUrl())
                .eq(ShortLinkGotoDO::getGid, hasShortLinkDO.getGid());
        ShortLinkGotoDO shortLinkGotoDO = shortLinkGotoMapper.selectOne(linkGotoQueryWrapper);
        shortLinkGotoMapper.delete(linkGotoQueryWrapper);
        shortLinkGotoDO.setGid(requestParam.getGid());
        shortLinkGotoMapper.insert(shortLinkGotoDO);
    } finally {
        rLock.unlock();
    }
}
```

**为什么使用读写锁？**
- **并发安全**：防止多个请求同时修改同一个短链接的分组
- **性能优化**：读写锁允许多个读操作并发执行，只有写操作互斥
- **锁粒度**：锁的 Key 是 `fullShortUrl`，只锁定当前短链接，不影响其他短链接

**为什么逻辑删除而不是物理删除？**
- **保留历史数据**：统计数据（PV、UV、UIP）需要保留
- **数据追溯**：可以查询短链接的历史变更记录
- **分库分表兼容**：物理删除可能导致分片键变化，影响数据一致性

**关键点：**
- **逻辑删除**：设置 `delFlag = 1`，`delTime = 当前时间戳`
- **插入新记录**：复制旧记录的统计数据（PV、UV、UIP），更新分组标识
- **更新跳转表**：删除旧的 `t_link_goto` 记录，插入新的记录

---

### 第4步：更新缓存

```java
if (!Objects.equals(hasShortLinkDO.getValidDateType(), requestParam.getValidDateType())
        || !Objects.equals(hasShortLinkDO.getValidDate(), requestParam.getValidDate())
        || !Objects.equals(hasShortLinkDO.getOriginUrl(), requestParam.getOriginUrl())) {

    // 删除缓存
    stringRedisTemplate.delete(String.format(GOTO_SHORT_LINK_KEY, requestParam.getFullShortUrl()));

    // 处理过期短链接的空值缓存
    Date currentDate = new Date();
    if (hasShortLinkDO.getValidDate() != null && hasShortLinkDO.getValidDate().before(currentDate)) {
        if (Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType())
            || requestParam.getValidDate().after(currentDate)) {
            stringRedisTemplate.delete(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, requestParam.getFullShortUrl()));
        }
    }
}
```

**缓存更新策略：**
- **删除缓存**：采用 Cache Aside 模式，先更新数据库，再删除缓存
- **触发条件**：有效期类型、有效期、原始 URL 任一改变时删除缓存
- **空值缓存处理**：如果短链接从过期变为有效，删除空值缓存

**Redis Key 格式：**
```
goto:short-link:{fullShortUrl} → 原始 URL
goto:is_null:short-link:{fullShortUrl} → "-"（空值缓存）
```

**为什么不更新缓存而是删除缓存？**
- **避免并发问题**：更新缓存可能导致缓存与数据库不一致
- **懒加载**：下次访问时自动从数据库加载最新数据到缓存
- **性能优化**：删除操作比更新操作更快

---

## 两种更新策略对比

| 对比项 | 同分组更新 | 跨分组更新 |
|--------|-----------|-----------|
| **操作方式** | 直接更新原记录 | 逻辑删除 + 插入新记录 |
| **数据库操作** | 1 次 UPDATE | 1 次 UPDATE + 1 次 INSERT + 1 次 DELETE + 1 次 INSERT |
| **并发控制** | 无需加锁 | 需要读写锁 |
| **性能** | 快 | 较慢 |
| **历史数据** | 不保留 | 保留（逻辑删除） |
| **适用场景** | 修改描述、有效期、原始 URL | 修改分组标识 |

---

## 涉及的技术栈

| 技术 | 用途 | 说明 |
|------|------|------|
| **MyBatis Plus** | ORM 框架 | 数据库操作 |
| **Redis** | 缓存 | 短链接缓存、空值缓存 |
| **Redisson** | Redis 客户端 | 读写锁 |
| **Jsoup** | HTML 解析 | 获取网站 Favicon |
| **Spring Transaction** | 事务管理 | @Transactional 注解 |

---

## 涉及的中间件

| 中间件 | 作用 |
|--------|------|
| **MySQL** | 存储短链接数据 |
| **Redis** | 缓存、分布式锁 |

---

## 关键配置

```yaml
short-link:
  domain:
    default: nurl.ink:8003  # 默认域名
  goto-domain:
    white-list:
      enable: false         # 白名单开关
      names: '知乎,掘金,博客园'
      details:
        - zhihu.com
        - juejin.cn
        - cnblogs.com
```

---

## 数据流示例

### 同分组更新流程

```
用户请求
  ↓
PUT /api/short-link/v1/update
{
  "fullShortUrl": "nurl.ink:8003/abc123",
  "originGid": "group1",
  "gid": "group1",
  "originUrl": "https://www.newexample.com",
  "validDateType": 1,
  "validDate": "2026-12-31 23:59:59",
  "describe": "更新后的描述"
}
  ↓
验证白名单 ✓
  ↓
查询原始记录
  └─ SELECT * FROM t_link WHERE gid='group1' AND full_short_url='nurl.ink:8003/abc123' AND del_flag=0 AND enable_status=0
  ↓
判断分组: originGid == gid ✓
  ↓
构建更新对象
  ├─ 保留: domain, shortUri, createdType
  ├─ 更新: originUrl, describe, validDateType, validDate
  └─ Favicon: 原始 URL 改变，重新获取
  ↓
执行更新
  └─ UPDATE t_link SET origin_url='...', describe='...', valid_date_type=1, valid_date='2026-12-31 23:59:59', favicon='...' WHERE full_short_url='nurl.ink:8003/abc123' AND gid='group1' AND del_flag=0 AND enable_status=0
  ↓
删除缓存
  └─ DEL goto:short-link:nurl.ink:8003/abc123
  ↓
返回成功
```

---

### 跨分组更新流程

```
用户请求
  ↓
PUT /api/short-link/v1/update
{
  "fullShortUrl": "nurl.ink:8003/abc123",
  "originGid": "group1",
  "gid": "group2",
  "originUrl": "https://www.newexample.com",
  "validDateType": 1,
  "validDate": "2026-12-31 23:59:59",
  "describe": "更新后的描述"
}
  ↓
验证白名单 ✓
  ↓
查询原始记录
  └─ SELECT * FROM t_link WHERE gid='group1' AND full_short_url='nurl.ink:8003/abc123' AND del_flag=0 AND enable_status=0
  ↓
判断分组: originGid != gid ✓
  ↓
获取读写锁（写锁）
  └─ LOCK lock:gid_update:nurl.ink:8003/abc123
  ↓
逻辑删除旧记录
  └─ UPDATE t_link SET del_flag=1, del_time=1711929600000 WHERE full_short_url='nurl.ink:8003/abc123' AND gid='group1' AND del_flag=0 AND del_time=0 AND enable_status=0
  ↓
插入新记录
  └─ INSERT INTO t_link (domain, origin_url, gid, ..., total_pv, total_uv, total_uip, del_time) VALUES ('nurl.ink:8003', 'https://www.newexample.com', 'group2', ..., 100, 50, 30, 0)
  ↓
更新 t_link_goto 表
  ├─ SELECT * FROM t_link_goto WHERE full_short_url='nurl.ink:8003/abc123' AND gid='group1'
  ├─ DELETE FROM t_link_goto WHERE full_short_url='nurl.ink:8003/abc123' AND gid='group1'
  └─ INSERT INTO t_link_goto (full_short_url, gid) VALUES ('nurl.ink:8003/abc123', 'group2')
  ↓
释放锁
  └─ UNLOCK lock:gid_update:nurl.ink:8003/abc123
  ↓
删除缓存
  └─ DEL goto:short-link:nurl.ink:8003/abc123
  ↓
返回成功
```

---

## 高并发处理

### 1. 读写锁（跨分组更新）

**优点：**
- 保证数据一致性
- 允许多个读操作并发执行
- 写操作互斥，防止并发修改

**缺点：**
- 性能较低（需要等待锁）
- 锁粒度较粗（锁定整个短链接）

**配置：**
```java
RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(
    String.format(LOCK_GID_UPDATE_KEY, requestParam.getFullShortUrl())
);
RLock rLock = readWriteLock.writeLock();
```

**Redis Key 格式：**
```
lock:gid_update:{fullShortUrl}
```

---

### 2. 缓存一致性

**策略：** Cache Aside 模式

**流程：**
1. 更新数据库
2. 删除缓存
3. 下次访问时从数据库加载最新数据到缓存

**优点：**
- 简单可靠
- 避免并发问题
- 懒加载，减少不必要的缓存更新

**缺点：**
- 短暂的缓存不一致（删除缓存到下次加载之间）
- 缓存穿透风险（大量请求同时访问未缓存的数据）

---

## 常见问题

### 1. 为什么跨分组更新需要读写锁？

**原因：**
- 跨分组更新涉及多个表操作（t_link、t_link_goto）
- 防止并发修改导致数据不一致
- 保证逻辑删除 + 插入新记录的原子性

**示例：**
```
请求1: 将短链接从 group1 移动到 group2
请求2: 将短链接从 group1 移动到 group3

如果不加锁：
  请求1: 逻辑删除 group1 记录
  请求2: 逻辑删除 group1 记录（失败，因为已被请求1删除）
  请求1: 插入 group2 记录
  请求2: 插入 group3 记录（成功，但数据不一致）

加锁后：
  请求1: 获取锁 → 逻辑删除 group1 记录 → 插入 group2 记录 → 释放锁
  请求2: 等待锁 → 获取锁 → 查询记录（已在 group2）→ 逻辑删除 group2 记录 → 插入 group3 记录 → 释放锁
```

---

### 2. 为什么同分组更新不需要加锁？

**原因：**
- 同分组更新只涉及单表单记录的 UPDATE 操作
- 数据库的行锁已经保证了并发安全
- 不涉及多表操作，不需要额外的分布式锁

---

### 3. 为什么逻辑删除而不是物理删除？

**原因：**
- **保留历史数据**：统计数据（PV、UV、UIP）需要保留
- **数据追溯**：可以查询短链接的历史变更记录
- **分库分表兼容**：物理删除可能导致分片键变化，影响数据一致性
- **性能优化**：逻辑删除比物理删除更快

---

### 4. 为什么删除缓存而不是更新缓存？

**原因：**
- **避免并发问题**：更新缓存可能导致缓存与数据库不一致
- **懒加载**：下次访问时自动从数据库加载最新数据到缓存
- **性能优化**：删除操作比更新操作更快

**示例：**
```
请求1: 更新短链接 A 的原始 URL 为 URL1
请求2: 更新短链接 A 的原始 URL 为 URL2

如果更新缓存：
  请求1: 更新数据库 → 更新缓存为 URL1
  请求2: 更新数据库 → 更新缓存为 URL2
  （如果请求1的缓存更新慢于请求2，缓存中是 URL1，数据库中是 URL2，数据不一致）

如果删除缓存：
  请求1: 更新数据库 → 删除缓存
  请求2: 更新数据库 → 删除缓存
  下次访问: 从数据库加载最新数据（URL2）到缓存
```

---

## 总结

| 方面 | 说明 |
|------|------|
| **核心流程** | 验证 → 查询 → 判断分组 → 更新/删除+插入 → 更新缓存 → 返回 |
| **两种策略** | 同分组更新（直接更新）、跨分组更新（逻辑删除 + 插入） |
| **并发控制** | 读写锁（跨分组更新） |
| **数据一致性** | 逻辑删除 + Cache Aside 模式 |
| **性能优化** | 同分组更新无需加锁、Favicon 复用 |
| **异常处理** | 记录不存在抛出异常 |
| **涉及技术** | MyBatis Plus、Redis、Redisson、Jsoup |
| **涉及中间件** | MySQL、Redis |
