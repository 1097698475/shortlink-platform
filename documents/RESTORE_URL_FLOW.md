# restoreUrl 短链接跳转流程详解

## 方法位置

`project/.../service/impl/ShortLinkServiceImpl.java` → `restoreUrl()`

---

## 涉及的 Redis Key

| Key | 格式 | 数据结构 | 作用 |
|-----|------|---------|------|
| `GOTO_SHORT_LINK_KEY` | `short-link:goto:%s` | String | 短链接 → 原始 URL 的跳转缓存 |
| `GOTO_IS_NULL_SHORT_LINK_KEY` | `short-link:is-null:goto_%s` | String | 空值缓存，防缓存穿透 |
| `LOCK_GOTO_SHORT_LINK_KEY` | `short-link:lock:goto:%s` | 分布式锁 | 防缓存击穿，保证只有一个请求查 DB |
| `SHORT_LINK_STATS_UV_KEY` | `short-link:stats:uv:` | Set | 判断是否新 UV（独立访客） |
| `SHORT_LINK_STATS_UIP_KEY` | `short-link:stats:uip:` | Set | 判断是否新 UIP（独立 IP） |
| `SHORT_LINK_STATS_STREAM_TOPIC_KEY` | `short-link:stats-stream` | Stream | 异步发送统计数据 |

---

## 完整流程图

```
用户访问 GET /{shortUri}
  ↓
拼接 fullShortUrl = serverName + serverPort + "/" + shortUri
  ↓
┌─────────────────────────────────────────────────────┐
│ 第1步：查跳转缓存                                     │
│ GET short-link:goto:{fullShortUrl}                  │
└─────────────────────────────────────────────────────┘
  ├─ 命中 → 统计 → sendRedirect(originalLink) ✓ 结束
  └─ 未命中
      ↓
┌─────────────────────────────────────────────────────┐
│ 第2步：查 Bloom Filter                               │
│ bloomFilter.contains(fullShortUrl)                  │
└─────────────────────────────────────────────────────┘
  ├─ 不存在 → sendRedirect("/page/notfound") ✓ 结束
  └─ 存在（可能存在）
      ↓
┌─────────────────────────────────────────────────────┐
│ 第3步：查空值缓存                                     │
│ GET short-link:is-null:goto_{fullShortUrl}          │
└─────────────────────────────────────────────────────┘
  ├─ 命中（值为"-"）→ sendRedirect("/page/notfound") ✓ 结束
  └─ 未命中
      ↓
┌─────────────────────────────────────────────────────┐
│ 第4步：加分布式锁                                     │
│ LOCK short-link:lock:goto:{fullShortUrl}            │
└─────────────────────────────────────────────────────┘
      ↓
┌─────────────────────────────────────────────────────┐
│ 第5步：双重检查（锁内再查一次缓存）                    │
│ GET short-link:goto:{fullShortUrl}                  │
│ GET short-link:is-null:goto_{fullShortUrl}          │
└─────────────────────────────────────────────────────┘
  ├─ 跳转缓存命中 → 统计 → sendRedirect ✓ 结束
  ├─ 空值缓存命中 → sendRedirect("/page/notfound") ✓ 结束
  └─ 均未命中
      ↓
┌─────────────────────────────────────────────────────┐
│ 第6步：查 t_link_goto 表（获取 gid）                  │
│ SELECT * FROM t_link_goto WHERE full_short_url = ?  │
└─────────────────────────────────────────────────────┘
  ├─ 不存在 → SET is-null:goto_ = "-" (30min)
  │           → sendRedirect("/page/notfound") ✓ 结束
  └─ 存在
      ↓
┌─────────────────────────────────────────────────────┐
│ 第7步：查 t_link 表（获取完整短链接信息）              │
│ SELECT * FROM t_link WHERE gid=? AND full_short_url=?│
│ AND del_flag=0 AND enable_status=0                  │
└─────────────────────────────────────────────────────┘
  ├─ 不存在 或 已过期（validDate < now）
  │   → SET is-null:goto_ = "-" (30min)
  │   → sendRedirect("/page/notfound") ✓ 结束
  └─ 存在且有效
      ↓
┌─────────────────────────────────────────────────────┐
│ 第8步：回写跳转缓存                                   │
│ SET short-link:goto:{fullShortUrl} = originUrl      │
└─────────────────────────────────────────────────────┘
      ↓
┌─────────────────────────────────────────────────────┐
│ 第9步：统计 + 跳转                                    │
│ buildLinkStatsRecordAndSetUser()                    │
│ shortLinkStats() → 发 Stream 消息                   │
│ sendRedirect(originUrl)                             │
└─────────────────────────────────────────────────────┘
      ↓
释放锁 UNLOCK short-link:lock:goto:{fullShortUrl}
```

---

## 详细步骤

### 第1步：拼接 fullShortUrl

```java
String serverName = request.getServerName();
String serverPort = Optional.of(request.getServerPort())
        .filter(each -> !Objects.equals(each, 80))  // 80端口不拼接
        .map(String::valueOf)
        .map(each -> ":" + each)
        .orElse("");
String fullShortUrl = serverName + serverPort + "/" + shortUri;
// 结果：nurl.ink:8003/abc123
```

---

### 第2步：查跳转缓存（最快路径）

```java
String originalLink = stringRedisTemplate.opsForValue()
    .get(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));
if (StrUtil.isNotBlank(originalLink)) {
    shortLinkStats(...);
    response.sendRedirect(originalLink);
    return;
}
```

**命中则直接跳转，是性能最优路径，绝大多数请求在这里结束。**

---

### 第3步：查 Bloom Filter（防缓存穿透第一道防线）

```java
boolean contains = shortUriCreateCachePenetrationBloomFilter.contains(fullShortUrl);
if (!contains) {
    response.sendRedirect("/page/notfound");
    return;
}
```

**Bloom Filter 说不存在，则一定不存在，直接返回 404，不查 DB。**

注意：Bloom Filter 说存在，不一定真的存在（有误差率），所以还需要后续验证。

---

### 第4步：查空值缓存（防缓存穿透第二道防线）

```java
String gotoIsNullShortLink = stringRedisTemplate.opsForValue()
    .get(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl));
if (StrUtil.isNotBlank(gotoIsNullShortLink)) {
    response.sendRedirect("/page/notfound");
    return;
}
```

**如果之前已经查过 DB 确认不存在，这里会命中空值缓存，直接返回 404，不再查 DB。**

---

### 第5步：加分布式锁

```java
RLock lock = redissonClient.getLock(String.format(LOCK_GOTO_SHORT_LINK_KEY, fullShortUrl));
lock.lock();
```

**防止缓存击穿：** 大量并发请求同时到达，缓存未命中，如果不加锁会全部打到 DB。加锁后只有一个请求查 DB，其余等待。

---

### 第6步：锁内双重检查

```java
// 再查一次跳转缓存
originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));
if (StrUtil.isNotBlank(originalLink)) { ... return; }

// 再查一次空值缓存
gotoIsNullShortLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl));
if (StrUtil.isNotBlank(gotoIsNullShortLink)) { ... return; }
```

**为什么要双重检查？** 等待锁的期间，第一个拿到锁的线程已经完成了 DB 查询和缓存回写，后续线程拿到锁后直接读缓存即可，避免重复查 DB。

---

### 第7步：查 t_link_goto 表

```java
LambdaQueryWrapper<ShortLinkGotoDO> linkGotoQueryWrapper = Wrappers.lambdaQuery(ShortLinkGotoDO.class)
        .eq(ShortLinkGotoDO::getFullShortUrl, fullShortUrl);
ShortLinkGotoDO shortLinkGotoDO = shortLinkGotoMapper.selectOne(linkGotoQueryWrapper);
if (shortLinkGotoDO == null) {
    // 写空值缓存，TTL 30分钟
    stringRedisTemplate.opsForValue().set(
        String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl), "-", 30, TimeUnit.MINUTES
    );
    response.sendRedirect("/page/notfound");
    return;
}
```

**为什么先查 t_link_goto 而不是直接查 t_link？**

`t_link` 表按 `gid` 分片，查询时必须带上 `gid` 才能定位到正确的分片。`t_link_goto` 表存储了 `fullShortUrl → gid` 的映射，先查它拿到 `gid`，再去查 `t_link`。

---

### 第8步：查 t_link 表

```java
LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
        .eq(ShortLinkDO::getGid, shortLinkGotoDO.getGid())
        .eq(ShortLinkDO::getFullShortUrl, fullShortUrl)
        .eq(ShortLinkDO::getDelFlag, 0)
        .eq(ShortLinkDO::getEnableStatus, 0);
ShortLinkDO shortLinkDO = baseMapper.selectOne(queryWrapper);

// 不存在 或 已过期
if (shortLinkDO == null || (shortLinkDO.getValidDate() != null && shortLinkDO.getValidDate().before(new Date()))) {
    stringRedisTemplate.opsForValue().set(
        String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl), "-", 30, TimeUnit.MINUTES
    );
    response.sendRedirect("/page/notfound");
    return;
}
```

**查询条件：**
- `gid`：分片键，定位分片
- `fullShortUrl`：精确匹配
- `delFlag = 0`：未删除
- `enableStatus = 0`：已启用

**过期判断：** `validDate != null && validDate < now` → 写空值缓存，返回 404

---

### 第9步：回写缓存

```java
stringRedisTemplate.opsForValue().set(
    String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),
    shortLinkDO.getOriginUrl(),
    LinkUtil.getLinkCacheValidTime(shortLinkDO.getValidDate()), TimeUnit.MILLISECONDS
);
```

**过期时间计算：**
- 永久有效 → 缓存 30 天
- 自定义有效期 → 缓存到有效期结束

---

### 第10步：统计（buildLinkStatsRecordAndSetUser）

```java
shortLinkStats(buildLinkStatsRecordAndSetUser(fullShortUrl, request, response));
```

**UV 统计（独立访客）：**

```java
// 有 uv Cookie → 尝试加入 Set，判断是否新 UV
Long uvAdded = stringRedisTemplate.opsForSet()
    .add(SHORT_LINK_STATS_UV_KEY + fullShortUrl, cookieValue);
uvFirstFlag = uvAdded != null && uvAdded > 0L;

// 没有 uv Cookie → 生成新 UUID，写 Cookie，加入 Set，标记为新 UV
uv.set(UUID.fastUUID().toString());
Cookie uvCookie = new Cookie("uv", uv.get());
uvCookie.setMaxAge(60 * 60 * 24 * 30);  // Cookie 有效期 30 天
response.addCookie(uvCookie);
uvFirstFlag.set(true);
stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UV_KEY + fullShortUrl, uv.get());
```

**UIP 统计（独立 IP）：**

```java
String remoteAddr = LinkUtil.getActualIp(request);  // 获取真实 IP（考虑代理）
Long uipAdded = stringRedisTemplate.opsForSet()
    .add(SHORT_LINK_STATS_UIP_KEY + fullShortUrl, remoteAddr);
boolean uipFirstFlag = uipAdded != null && uipAdded > 0L;
```

**构建统计 DTO 并发送到 Stream：**

```java
ShortLinkStatsRecordDTO statsRecord = ShortLinkStatsRecordDTO.builder()
    .fullShortUrl(fullShortUrl)
    .uv(uv.get())
    .uvFirstFlag(uvFirstFlag.get())   // 是否新 UV
    .uipFirstFlag(uipFirstFlag)       // 是否新 UIP
    .remoteAddr(remoteAddr)           // IP
    .os(os)                           // 操作系统
    .browser(browser)                 // 浏览器
    .device(device)                   // 设备类型
    .network(network)                 // 网络类型
    .currentDate(new Date())
    .build();

// 发送到 Redis Stream，异步消费
stringRedisTemplate.opsForStream().add(SHORT_LINK_STATS_STREAM_TOPIC_KEY, producerMap);
```

---

### 第11步：跳转 + 释放锁

```java
response.sendRedirect(shortLinkDO.getOriginUrl());
// finally 块中释放锁
lock.unlock();
```

---

## 三大缓存问题的解决方案

| 问题 | 场景 | 解决方案 |
|------|------|---------|
| **缓存穿透** | 请求一个根本不存在的短链接，每次都打到 DB | Bloom Filter + 空值缓存（双重防护） |
| **缓存击穿** | 热点短链接缓存过期，大量并发请求同时查 DB | 分布式锁 `LOCK_GOTO_SHORT_LINK_KEY` |
| **缓存雪崩** | 大量缓存同时过期 | 过期时间根据有效期动态计算，避免集中过期 |

---

## 为什么需要两张表（t_link + t_link_goto）

`t_link` 表按 `gid` 做了分库分表，查询时必须携带 `gid` 作为分片键，否则会全分片扫描，性能极差。

但用户访问短链接时只有 `fullShortUrl`，没有 `gid`，所以需要 `t_link_goto` 作为索引表，先查出 `gid`，再精准定位到 `t_link` 的分片。

```
用户只有 fullShortUrl
  ↓
t_link_goto（不分片）→ 查出 gid
  ↓
t_link（按 gid 分片）→ 精准查询
```

---

## 总结

| 步骤 | 操作 | Redis Key | 目的 |
|------|------|-----------|------|
| 1 | 查跳转缓存 | `GOTO_SHORT_LINK_KEY` | 最快路径，直接跳转 |
| 2 | 查 Bloom Filter | — | 过滤绝对不存在的请求 |
| 3 | 查空值缓存 | `GOTO_IS_NULL_SHORT_LINK_KEY` | 过滤已知不存在的请求 |
| 4 | 加分布式锁 | `LOCK_GOTO_SHORT_LINK_KEY` | 防止缓存击穿 |
| 5 | 双重检查缓存 | 同上两个 | 避免锁等待期间重复查 DB |
| 6 | 查 t_link_goto | — | 获取 gid，定位分片 |
| 7 | 查 t_link | — | 获取原始 URL，验证有效性 |
| 8 | 回写跳转缓存 | `GOTO_SHORT_LINK_KEY` | 下次直接命中缓存 |
| 9 | UV 统计 | `SHORT_LINK_STATS_UV_KEY` | 判断是否新访客 |
| 10 | UIP 统计 | `SHORT_LINK_STATS_UIP_KEY` | 判断是否新 IP |
| 11 | 发统计消息 | `SHORT_LINK_STATS_STREAM_TOPIC_KEY` | 异步写入统计数据 |
| 12 | 跳转 + 释放锁 | — | 完成跳转 |
