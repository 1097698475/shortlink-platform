# 创建短链接流程详解

## 流程概述

创建短链接是项目的核心功能。系统提供了两种创建方式：
1. **基于 Bloom Filter 的高性能方式**（推荐）
2. **基于分布式锁的安全方式**

---

## 数据库设计

### 1. t_link 表（短链接表）

```sql
CREATE TABLE t_link (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    domain VARCHAR(255),              -- 域名（如：nurl.ink:8003）
    short_uri VARCHAR(255),           -- 短链接后缀（如：abc123）
    full_short_url VARCHAR(255),      -- 完整短链接（如：nurl.ink:8003/abc123）
    origin_url LONGTEXT,              -- 原始链接
    gid VARCHAR(255),                 -- 分组标识
    enable_status INT DEFAULT 0,      -- 启用状态（0: 启用，1: 未启用）
    created_type INT,                 -- 创建类型（0: 接口，1: 控制台）
    valid_date_type INT,              -- 有效期类型（0: 永久，1: 自定义）
    valid_date DATETIME,              -- 有效期
    describe VARCHAR(255),            -- 描述
    favicon VARCHAR(255),             -- 网站图标
    total_pv INT DEFAULT 0,           -- 历史 PV
    total_uv INT DEFAULT 0,           -- 历史 UV
    total_uip INT DEFAULT 0,          -- 历史 UIP
    del_flag INT DEFAULT 0,           -- 删除标志
    del_time BIGINT,                  -- 删除时间
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by VARCHAR(255),
    update_by VARCHAR(255)
);
```

### 2. t_link_goto 表（短链接跳转表）

```sql
CREATE TABLE t_link_goto (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    gid VARCHAR(255),                 -- 分组标识
    full_short_url VARCHAR(255)       -- 完整短链接
);
```

**为什么需要两个表？**
- `t_link`: 存储短链接的详细信息
- `t_link_goto`: 用于快速查询短链接对应的分组（优化查询性能）

---

## 创建短链接流程（基于 Bloom Filter）

### 完整流程图

```
前端请求
  ↓
POST /api/short-link/v1/create
Body: {
  "originUrl": "https://www.example.com",
  "gid": "abc123",
  "validDateType": 0,
  "describe": "我的链接"
}
  ↓
ShortLinkController.createShortLink()
  ↓
ShortLinkServiceImpl.createShortLink()
  ├─ 1. 验证白名单（verificationWhitelist）
  ├─ 2. 生成短链接后缀（generateSuffix）
  │  ├─ 生成随机 UUID
  │  ├─ 拼接原始 URL + UUID
  │  ├─ 哈希转 Base62
  │  └─ 检查 Bloom Filter（最多重试 10 次）
  ├─ 3. 构建完整短链接
  ├─ 4. 获取网站 Favicon（getFavicon）
  ├─ 5. 构建 ShortLinkDO 对象
  ├─ 6. 构建 ShortLinkGotoDO 对象
  ├─ 7. 插入数据库
  │  ├─ 插入 t_link 表
  │  └─ 插入 t_link_goto 表
  ├─ 8. 缓存到 Redis
  ├─ 9. 更新 Bloom Filter
  └─ 10. 返回结果
  ↓
返回短链接信息
```

### 详细步骤

#### 第1步：验证白名单

```java
private void verificationWhitelist(String originUrl) {
    // 检查是否启用白名单
    Boolean enable = gotoDomainWhiteListConfiguration.getEnable();
    if (enable == null || !enable) {
        return;
    }

    // 提取原始 URL 的域名
    String domain = LinkUtil.extractDomain(originUrl);
    if (StrUtil.isBlank(domain)) {
        throw new ClientException("跳转链接填写错误");
    }

    // 检查域名是否在白名单中
    List<String> details = gotoDomainWhiteListConfiguration.getDetails();
    if (!details.contains(domain)) {
        throw new ClientException("演示环境为避免恶意攻击，请生成以下网站跳转链接：" +
            gotoDomainWhiteListConfiguration.getNames());
    }
}
```

**作用：** 防止恶意用户创建指向不安全网站的短链接

**配置示例：**
```yaml
short-link:
  goto-domain:
    white-list:
      enable: false  # 演示环境启用，生产环境关闭
      names: '知乎,掘金,博客园'
      details:
        - zhihu.com
        - juejin.cn
        - cnblogs.com
```

---

#### 第2步：生成短链接后缀

```java
private String generateSuffix(ShortLinkCreateReqDTO requestParam) {
    int customGenerateCount = 0;
    String shorUri;

    while (true) {
        // 最多重试 10 次
        if (customGenerateCount > 10) {
            throw new ServiceException("短链接频繁生成，请稍后再试");
        }

        // 1. 生成随机 UUID
        String originUrl = requestParam.getOriginUrl();
        originUrl += UUID.randomUUID().toString();

        // 2. 哈希转 Base62
        shorUri = HashUtil.hashToBase62(originUrl);

        // 3. 检查 Bloom Filter
        if (!shortUriCreateCachePenetrationBloomFilter.contains(
            createShortLinkDefaultDomain + "/" + shorUri)) {
            break;  // 短链接不存在，可以使用
        }

        customGenerateCount++;
    }

    return shorUri;
}
```

**关键点：**
- **UUID 随机性**：每次生成不同的 UUID，确保哈希结果不同
- **Base62 编码**：将哈希值转换为 Base62（0-9, a-z, A-Z），使短链接更短
- **Bloom Filter 检查**：快速判断短链接是否已存在
- **重试机制**：最多重试 10 次，防止无限循环

**哈希冲突解决：**
```
原始 URL: https://www.example.com
  ↓
添加 UUID: https://www.example.com + 550e8400-e29b-41d4-a716-446655440000
  ↓
哈希计算: SHA256 → 256 位二进制
  ↓
Base62 编码: abc123xyz
  ↓
检查 Bloom Filter: 是否存在？
  ├─ 不存在 → 使用
  └─ 存在 → 重试（添加新 UUID）
```

---

#### 第3步：构建完整短链接

```java
String fullShortUrl = StrBuilder.create(createShortLinkDefaultDomain)
        .append("/")
        .append(shortLinkSuffix)
        .toString();
// 结果：nurl.ink:8003/abc123
```

---

#### 第4步：获取网站 Favicon

```java
@SneakyThrows
private String getFavicon(String url) {
    URL targetUrl = new URL(url);
    HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
    connection.setRequestMethod("GET");
    connection.connect();

    int responseCode = connection.getResponseCode();
    if (HttpURLConnection.HTTP_OK == responseCode) {
        // 使用 Jsoup 解析 HTML
        Document document = Jsoup.connect(url).get();

        // 查找 favicon 链接
        Element faviconLink = document.select("link[rel~=(?i)^(shortcut )?icon]").first();
        if (faviconLink != null) {
            return faviconLink.attr("abs:href");
        }
    }

    return null;
}
```

**作用：** 获取原始网站的 Favicon，用于前端展示

**使用库：** Jsoup（HTML 解析库）

---

#### 第5-6步：构建数据对象

```java
// 构建短链接对象
ShortLinkDO shortLinkDO = ShortLinkDO.builder()
        .domain(createShortLinkDefaultDomain)           // 域名
        .originUrl(requestParam.getOriginUrl())         // 原始链接
        .gid(requestParam.getGid())                     // 分组标识
        .createdType(requestParam.getCreatedType())     // 创建类型
        .validDateType(requestParam.getValidDateType()) // 有效期类型
        .validDate(requestParam.getValidDate())         // 有效期
        .describe(requestParam.getDescribe())           // 描述
        .shortUri(shortLinkSuffix)                      // 短链接后缀
        .enableStatus(0)                                // 启用状态
        .totalPv(0)                                     // 初始 PV
        .totalUv(0)                                     // 初始 UV
        .totalUip(0)                                    // 初始 UIP
        .delTime(0L)                                    // 删除时间
        .fullShortUrl(fullShortUrl)                     // 完整短链接
        .favicon(getFavicon(requestParam.getOriginUrl())) // Favicon
        .build();

// 构建跳转对象
ShortLinkGotoDO linkGotoDO = ShortLinkGotoDO.builder()
        .fullShortUrl(fullShortUrl)
        .gid(requestParam.getGid())
        .build();
```

---

#### 第7步：插入数据库

```java
try {
    // 插入 t_link 表
    baseMapper.insert(shortLinkDO);

    // 插入 t_link_goto 表
    shortLinkGotoMapper.insert(linkGotoDO);
} catch (DuplicateKeyException ex) {
    // 如果短链接已存在，更新 Bloom Filter
    if (!shortUriCreateCachePenetrationBloomFilter.contains(fullShortUrl)) {
        shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
    }
    throw new ServiceException(String.format("短链接：%s 生成重复", fullShortUrl));
}
```

**异常处理：**
- 如果插入失败（DuplicateKeyException），说明短链接已存在
- 更新 Bloom Filter，防止下次重复生成
- 抛出异常给前端

---

#### 第8步：缓存到 Redis

```java
stringRedisTemplate.opsForValue().set(
        String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),  // Key: goto:short-link:{fullShortUrl}
        requestParam.getOriginUrl(),                        // Value: 原始链接
        LinkUtil.getLinkCacheValidTime(requestParam.getValidDate()), // 过期时间
        TimeUnit.MILLISECONDS
);
```

**Redis Key 格式：**
```
goto:short-link:nurl.ink:8003/abc123 → https://www.example.com
```

**过期时间计算：**
- 如果有效期为永久，缓存 30 天
- 如果有效期为自定义，缓存到有效期结束

---

#### 第9步：更新 Bloom Filter

```java
shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
```

**作用：** 记录已创建的短链接，防止重复生成

---

#### 第10步：返回结果

```java
return ShortLinkCreateRespDTO.builder()
        .fullShortUrl("http://" + shortLinkDO.getFullShortUrl())
        .originUrl(requestParam.getOriginUrl())
        .gid(requestParam.getGid())
        .build();
```

**返回示例：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "fullShortUrl": "http://nurl.ink:8003/abc123",
    "originUrl": "https://www.example.com",
    "gid": "group123"
  }
}
```

---

## 高并发处理

### 1. Bloom Filter（推荐方式）

**优点：**
- 快速判断（O(1) 时间复杂度）
- 内存占用小
- 支持高并发

**缺点：**
- 有误差率（0.1%）
- 不支持删除

**配置：**
```java
@Bean
public RBloomFilter<String> shortUriCreateCachePenetrationBloomFilter(RedissonClient redissonClient) {
    RBloomFilter<String> cachePenetrationBloomFilter =
        redissonClient.getBloomFilter("shortUriCreateCachePenetrationBloomFilter");
    // 容量 1 亿，误差率 0.1%
    cachePenetrationBloomFilter.tryInit(100000000L, 0.001);
    return cachePenetrationBloomFilter;
}
```

### 2. 分布式锁（安全方式）

```java
@Override
public ShortLinkCreateRespDTO createShortLinkByLock(ShortLinkCreateReqDTO requestParam) {
    verificationWhitelist(requestParam.getOriginUrl());

    // 获取全局分布式锁
    RLock lock = redissonClient.getLock(SHORT_LINK_CREATE_LOCK_KEY);
    lock.lock();

    try {
        // 生成短链接（查询数据库）
        String shortLinkSuffix = generateSuffixByLock(requestParam);
        // ... 后续逻辑
    } finally {
        lock.unlock();
    }
}
```

**特点：**
- 全局锁，同一时间只有一个请求能创建短链接
- 性能较低，但绝对安全
- 适合对一致性要求高的场景

---

## 涉及的技术栈

| 技术 | 用途 | 说明 |
|------|------|------|
| **MyBatis Plus** | ORM 框架 | 数据库操作 |
| **ShardingSphere** | 数据库分片 | 海量数据存储 |
| **Redis** | 缓存 | 短链接缓存、Bloom Filter |
| **Redisson** | Redis 客户端 | 分布式锁、Bloom Filter |
| **Jsoup** | HTML 解析 | 获取网站 Favicon |
| **Base62** | 编码算法 | 短链接编码 |
| **SHA256** | 哈希算法 | 生成短链接后缀 |
| **Spring Transaction** | 事务管理 | @Transactional 注解 |

---

## 涉及的中间件

| 中间件 | 作用 |
|--------|------|
| **MySQL** | 存储短链接数据 |
| **Redis** | 缓存、Bloom Filter、分布式锁 |
| **RocketMQ** | 异步处理统计数据（后续） |

---

## 关键配置

```yaml
short-link:
  domain:
    default: nurl.ink:8003  # 默认域名
  flow-limit:
    enable: true
    time-window: 1
    max-access-count: 20    # 限流
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

## 性能对比

### Bloom Filter 方式

```
并发 1000 个请求创建短链接
  ↓
每个请求：
  1. 检查 Bloom Filter（< 1ms）
  2. 插入数据库（~10ms）
  3. 缓存到 Redis（~5ms）
  4. 更新 Bloom Filter（< 1ms）
  ↓
总耗时：~16ms
吞吐量：~60 请求/秒
```

### 分布式锁方式

```
并发 1000 个请求创建短链接
  ↓
获取锁（等待）
  ↓
每个请求：
  1. 查询数据库（~10ms）
  2. 插入数据库（~10ms）
  3. 缓存到 Redis（~5ms）
  ↓
总耗时：~25ms（加上锁等待时间）
吞吐量：~40 请求/秒
```

**结论：** Bloom Filter 方式性能更好，适合高并发场景

---

## 数据流示例

### 创建短链接完整流程

```
用户请求
  ↓
POST /api/short-link/v1/create
{
  "originUrl": "https://www.example.com",
  "gid": "abc123",
  "validDateType": 0,
  "describe": "我的链接"
}
  ↓
验证白名单 ✓
  ↓
生成短链接后缀
  ├─ UUID: 550e8400-e29b-41d4-a716-446655440000
  ├─ 拼接: https://www.example.com550e8400-e29b-41d4-a716-446655440000
  ├─ 哈希: abc123xyz
  └─ Bloom Filter 检查: 不存在 ✓
  ↓
完整短链接: nurl.ink:8003/abc123xyz
  ↓
获取 Favicon: https://www.example.com/favicon.ico
  ↓
构建数据对象
  ├─ ShortLinkDO
  └─ ShortLinkGotoDO
  ↓
插入数据库
  ├─ INSERT INTO t_link (...)
  └─ INSERT INTO t_link_goto (...)
  ↓
缓存到 Redis
  └─ SET goto:short-link:nurl.ink:8003/abc123xyz https://www.example.com EX 2592000
  ↓
更新 Bloom Filter
  └─ ADD shortUriCreateCachePenetrationBloomFilter nurl.ink:8003/abc123xyz
  ↓
返回结果
{
  "fullShortUrl": "http://nurl.ink:8003/abc123xyz",
  "originUrl": "https://www.example.com",
  "gid": "abc123"
}
```

---

## 总结

| 方面 | 说明 |
|------|------|
| **核心流程** | 验证 → 生成 → 构建 → 插入 → 缓存 → 返回 |
| **高并发处理** | Bloom Filter + 重试机制 |
| **数据一致性** | 两个表 + 缓存 + Bloom Filter |
| **性能优化** | 缓存预热、Bloom Filter 快速判断 |
| **异常处理** | DuplicateKeyException 捕获、重试机制 |
| **涉及技术** | MyBatis Plus、Redis、Redisson、Jsoup、Base62、SHA256 |
| **涉及中间件** | MySQL、Redis、RocketMQ |
