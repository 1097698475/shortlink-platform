# RedisKeyConstant 详解

## 常量定义位置

`project/src/main/java/com/lin1473/shortlink/project/common/constant/RedisKeyConstant.java`

---

## 1. GOTO_SHORT_LINK_KEY

```java
public static final String GOTO_SHORT_LINK_KEY = "short-link:goto:%s";
```

**数据结构：** String

**作用：** 缓存短链接 → 原始 URL 的映射，是跳转功能的核心缓存。

**完整 Key 示例：** `short-link:goto:nurl.ink:8003/abc123`

**使用位置：**

| 操作 | 位置 | 说明 |
|------|------|------|
| SET | `ShortLinkServiceImpl.createShortLink()` | 创建短链接时预热缓存 |
| GET | `ShortLinkServiceImpl.restoreUrl()` | 跳转时先查缓存 |
| SET | `ShortLinkServiceImpl.restoreUrl()` | 缓存穿透后从 DB 加载，回写缓存 |
| DEL | `ShortLinkServiceImpl.updateShortLink()` | 更新短链接时删除旧缓存 |
| DEL | `RecycleBinServiceImpl.saveRecycleBin()` | 移入回收站时删除缓存 |

```java
// 写入
stringRedisTemplate.opsForValue().set(
    String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),
    originUrl,
    getLinkCacheValidTime(validDate), TimeUnit.MILLISECONDS
);

// 读取
String originalLink = stringRedisTemplate.opsForValue()
    .get(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));

// 删除
stringRedisTemplate.delete(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));
```

---

## 2. GOTO_IS_NULL_SHORT_LINK_KEY

```java
public static final String GOTO_IS_NULL_SHORT_LINK_KEY = "short-link:is-null:goto_%s";
```

**数据结构：** String

**作用：** 空值缓存，防止缓存穿透。当短链接不存在或已过期时，缓存一个 `"-"` 占位符，避免每次都打到数据库。

**完整 Key 示例：** `short-link:is-null:goto_nurl.ink:8003/abc123`

**使用位置：**

| 操作 | 位置 | 说明 |
|------|------|------|
| GET | `ShortLinkServiceImpl.restoreUrl()` | 跳转前检查是否是已知的空链接 |
| SET | `ShortLinkServiceImpl.restoreUrl()` | 查 DB 发现不存在/已过期时写入 `"-"` |
| DEL | `ShortLinkServiceImpl.updateShortLink()` | 短链接从过期变为有效时清除空值缓存 |
| DEL | `RecycleBinServiceImpl.recoverRecycleBin()` | 从回收站恢复时清除空值缓存 |

```java
// 写入（TTL 30分钟）
stringRedisTemplate.opsForValue().set(
    String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl), "-", 30, TimeUnit.MINUTES
);

// 读取
String gotoIsNullShortLink = stringRedisTemplate.opsForValue()
    .get(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl));

// 删除
stringRedisTemplate.delete(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl));
```

---

## 3. LOCK_GOTO_SHORT_LINK_KEY

```java
public static final String LOCK_GOTO_SHORT_LINK_KEY = "short-link:lock:goto:%s";
```

**数据结构：** 分布式锁（Redisson RLock）

**作用：** 跳转时的互斥锁，防止缓存击穿。当缓存中没有数据时，多个并发请求同时查 DB，用这把锁保证只有一个请求去查，其余等待。

**完整 Key 示例：** `short-link:lock:goto:nurl.ink:8003/abc123`

**使用位置：** 仅 `ShortLinkServiceImpl.restoreUrl()`

```java
RLock lock = redissonClient.getLock(String.format(LOCK_GOTO_SHORT_LINK_KEY, fullShortUrl));
lock.lock();
try {
    // 双重检查：再次查缓存，防止锁等待期间其他线程已经回写
    originalLink = stringRedisTemplate.opsForValue().get(...);
    if (StrUtil.isNotBlank(originalLink)) { ... return; }
    // 查 DB，回写缓存
} finally {
    lock.unlock();
}
```

**为什么需要双重检查？** 锁等待期间，第一个拿到锁的线程已经把数据写入缓存了，后续线程拿到锁后直接读缓存即可，不用再查 DB。

---

## 4. LOCK_GID_UPDATE_KEY

```java
public static final String LOCK_GID_UPDATE_KEY = "short-link:lock:update-gid:%s";
```

**数据结构：** 读写锁（Redisson RReadWriteLock）

**作用：** 跨分组更新短链接时的写锁，同时在统计消费时加读锁，保证"更新分组"和"写入统计数据"不会并发冲突。

**完整 Key 示例：** `short-link:lock:update-gid:nurl.ink:8003/abc123`

**使用位置：**

| 操作 | 位置 | 说明 |
|------|------|------|
| 写锁 | `ShortLinkServiceImpl.updateShortLink()` | 跨分组更新时加写锁 |
| 读锁 | `ShortLinkStatsSaveConsumer` | 消费统计消息时加读锁 |

```java
RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(
    String.format(LOCK_GID_UPDATE_KEY, fullShortUrl)
);

// 更新时：写锁（互斥）
RLock writeLock = readWriteLock.writeLock();
writeLock.lock();

// 统计消费时：读锁（共享）
RLock readLock = readWriteLock.readLock();
readLock.lock();
```

**为什么用读写锁而不是普通锁？** 统计写入是高频操作，多个统计请求可以并发（读锁共享），只有在修改分组时才需要独占（写锁互斥），性能更好。

---

## 5. SHORT_LINK_CREATE_LOCK_KEY

```java
public static final String SHORT_LINK_CREATE_LOCK_KEY = "short-link:lock:create";
```

**数据结构：** 分布式锁（Redisson RLock）

**作用：** 创建短链接时的全局互斥锁，用于 `createShortLinkByLock()` 方法（基于分布式锁的创建方式，与 Bloom Filter 方式二选一）。

**注意：** 这是一把全局锁，所有创建请求共用，并发性能差，生产环境推荐用 Bloom Filter 方式。

**使用位置：** 仅 `ShortLinkServiceImpl.createShortLinkByLock()`

```java
RLock lock = redissonClient.getLock(SHORT_LINK_CREATE_LOCK_KEY);
lock.lock();
try {
    // 生成短链接后缀（查 DB 判断是否重复）
    // 插入数据库
    // 写入缓存
} finally {
    lock.unlock();
}
```

---

## 6. SHORT_LINK_STATS_UV_KEY

```java
public static final String SHORT_LINK_STATS_UV_KEY = "short-link:stats:uv:";
```

**数据结构：** Set

**作用：** 记录访问过某个短链接的 UV（独立访客）Cookie 值集合，用于判断当前访客是否是新用户。

**完整 Key 示例：** `short-link:stats:uv:nurl.ink:8003/abc123`

**使用位置：** `ShortLinkServiceImpl.buildLinkStatsRecordAndSetUser()`

```java
// 尝试将 uv cookie 值加入 Set
Long uvAdded = stringRedisTemplate.opsForSet()
    .add(SHORT_LINK_STATS_UV_KEY + fullShortUrl, uvCookieValue);

// 返回值 > 0 说明是新元素，即新 UV
boolean uvFirstFlag = uvAdded != null && uvAdded > 0L;
```

**原理：** Redis Set 的 `SADD` 命令，如果元素已存在返回 0，新增返回 1，借此判断是否首次访问。

---

## 7. SHORT_LINK_STATS_UIP_KEY

```java
public static final String SHORT_LINK_STATS_UIP_KEY = "short-link:stats:uip:";
```

**数据结构：** Set

**作用：** 记录访问过某个短链接的 UIP（独立 IP）集合，用于判断当前 IP 是否是新 IP。

**完整 Key 示例：** `short-link:stats:uip:nurl.ink:8003/abc123`

**使用位置：** `ShortLinkServiceImpl.buildLinkStatsRecordAndSetUser()`

```java
Long uipAdded = stringRedisTemplate.opsForSet()
    .add(SHORT_LINK_STATS_UIP_KEY + fullShortUrl, remoteAddr);

boolean uipFirstFlag = uipAdded != null && uipAdded > 0L;
```

**与 UV 的区别：** UV 基于 Cookie（同一浏览器），UIP 基于 IP 地址，两者统计维度不同。

---

## 8. SHORT_LINK_STATS_STREAM_TOPIC_KEY

```java
public static final String SHORT_LINK_STATS_STREAM_TOPIC_KEY = "short-link:stats-stream";
```

**数据结构：** Redis Stream（消息队列）

**作用：** 统计数据的消息队列 Topic，生产者把统计记录发到这个 Stream，消费者异步消费处理。

**使用位置：**

| 操作 | 位置 | 说明 |
|------|------|------|
| 发消息 | `ShortLinkStatsSaveProducer.send()` | 跳转时发送统计数据 |
| 建 Group | `ShortLinkStatsStreamInitializeTask` | 应用启动时初始化消费组 |
| 订阅 | `RedisStreamConfiguration` | 配置 Stream 监听器 |

```java
// 生产者发消息
stringRedisTemplate.opsForStream()
    .add(SHORT_LINK_STATS_STREAM_TOPIC_KEY, producerMap);

// 初始化消费组
stringRedisTemplate.opsForStream()
    .createGroup(SHORT_LINK_STATS_STREAM_TOPIC_KEY, SHORT_LINK_STATS_STREAM_GROUP_KEY);

// 消费者订阅
StreamOffset.create(SHORT_LINK_STATS_STREAM_TOPIC_KEY, ReadOffset.lastConsumed())
```

---

## 9. SHORT_LINK_STATS_STREAM_GROUP_KEY

```java
public static final String SHORT_LINK_STATS_STREAM_GROUP_KEY = "short-link:stats-stream:only-group";
```

**数据结构：** Redis Stream 消费组名称（字符串常量，非独立 Key）

**作用：** Stream 消费组的名称标识，配合 `SHORT_LINK_STATS_STREAM_TOPIC_KEY` 使用，保证消息被消费组内的消费者有序消费。

**使用位置：** `ShortLinkStatsStreamInitializeTask`、`RedisStreamConfiguration`

```java
// 创建消费组
stringRedisTemplate.opsForStream().createGroup(
    SHORT_LINK_STATS_STREAM_TOPIC_KEY,
    SHORT_LINK_STATS_STREAM_GROUP_KEY
);

// 消费者加入消费组
Consumer.from(SHORT_LINK_STATS_STREAM_GROUP_KEY, "stats-consumer")
```

---

## 10. DELAY_QUEUE_STATS_KEY

```java
public static final String DELAY_QUEUE_STATS_KEY = "short-link:delay-queue:stats";
```

**数据结构：** Redisson DelayedQueue（延迟队列）

**作用：** 统计数据的延迟队列，用于处理 Stream 消费失败时的兜底重试。

**使用位置：**

| 操作 | 位置 | 说明 |
|------|------|------|
| 入队 | `DelayShortLinkStatsProducer` | 发送延迟统计任务 |
| 消费 | `DelayShortLinkStatsConsumer` | 消费延迟任务 |

```java
// 生产者
RBlockingDeque<ShortLinkStatsRecordDTO> blockingDeque =
    redissonClient.getBlockingDeque(DELAY_QUEUE_STATS_KEY);
RDelayedQueue<ShortLinkStatsRecordDTO> delayedQueue =
    redissonClient.getDelayedQueue(blockingDeque);
delayedQueue.offer(statsRecord, delay, TimeUnit.SECONDS);

// 消费者
RBlockingDeque<ShortLinkStatsRecordDTO> blockingDeque =
    redissonClient.getBlockingDeque(DELAY_QUEUE_STATS_KEY);
ShortLinkStatsRecordDTO statsRecord = blockingDeque.poll(timeout, TimeUnit.MILLISECONDS);
```

---

## 汇总表

| 常量名 | Key 格式 | 数据结构 | 核心作用 |
|--------|---------|---------|---------|
| `GOTO_SHORT_LINK_KEY` | `short-link:goto:%s` | String | 短链接跳转缓存 |
| `GOTO_IS_NULL_SHORT_LINK_KEY` | `short-link:is-null:goto_%s` | String | 空值缓存，防缓存穿透 |
| `LOCK_GOTO_SHORT_LINK_KEY` | `short-link:lock:goto:%s` | 分布式锁 | 跳转时防缓存击穿 |
| `LOCK_GID_UPDATE_KEY` | `short-link:lock:update-gid:%s` | 读写锁 | 跨分组更新并发控制 |
| `SHORT_LINK_CREATE_LOCK_KEY` | `short-link:lock:create` | 分布式锁 | 创建短链接全局锁 |
| `SHORT_LINK_STATS_UV_KEY` | `short-link:stats:uv:` | Set | 判断是否新 UV |
| `SHORT_LINK_STATS_UIP_KEY` | `short-link:stats:uip:` | Set | 判断是否新 UIP |
| `SHORT_LINK_STATS_STREAM_TOPIC_KEY` | `short-link:stats-stream` | Stream | 统计数据消息队列 Topic |
| `SHORT_LINK_STATS_STREAM_GROUP_KEY` | `short-link:stats-stream:only-group` | Stream 消费组 | 统计消息消费组名称 |
| `DELAY_QUEUE_STATS_KEY` | `short-link:delay-queue:stats` | 延迟队列 | 统计失败兜底重试 |

---

## 跳转流程中的 Key 协作

```
用户访问短链接
  ↓
GET GOTO_SHORT_LINK_KEY（String）
  ├─ 命中 → 直接跳转，发统计消息
  └─ 未命中
      ↓
    GET GOTO_IS_NULL_SHORT_LINK_KEY（String）
      ├─ 命中（值为"-"）→ 返回 404
      └─ 未命中
          ↓
        加 LOCK_GOTO_SHORT_LINK_KEY（分布式锁）
          ↓
        双重检查缓存（防止重复查 DB）
          ↓
        查 DB → 回写 GOTO_SHORT_LINK_KEY
        （不存在/过期 → 写 GOTO_IS_NULL_SHORT_LINK_KEY）
          ↓
        释放锁
          ↓
        统计：
          SADD SHORT_LINK_STATS_UV_KEY（判断新 UV）
          SADD SHORT_LINK_STATS_UIP_KEY（判断新 UIP）
          发送 SHORT_LINK_STATS_STREAM_TOPIC_KEY（异步统计）
          （失败兜底）→ DELAY_QUEUE_STATS_KEY（延迟重试）
```
