# 短链接分组模块详解

## 模块概述

分组模块用于管理用户的短链接分组。每个用户可以创建多个分组，每个分组可以包含多个短链接。分组用于组织和管理短链接，支持创建、查询、修改、删除、排序等操作。

---

## 数据库设计

### 1. t_group 表（分组表）

```sql
CREATE TABLE t_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    gid VARCHAR(255) NOT NULL,           -- 分组标识（唯一）
    name VARCHAR(255) NOT NULL,          -- 分组名称
    username VARCHAR(255) NOT NULL,      -- 创建分组的用户名
    sort_order INT DEFAULT 0,            -- 分组排序
    del_flag INT DEFAULT 0,              -- 删除标志（0: 未删除，1: 已删除）
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by VARCHAR(255),
    update_by VARCHAR(255)
);
```

### 2. t_group_unique 表（分组唯一性表）

```sql
CREATE TABLE t_group_unique (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    gid VARCHAR(255) UNIQUE NOT NULL     -- 分组标识（唯一约束）
);
```

**为什么需要两个表？**
- `t_group`: 存储分组的业务数据
- `t_group_unique`: 保证 gid 的全局唯一性（用于并发控制）

---

## 接口列表

### 1. 创建分组

**接口：** `POST /api/short-link/admin/v1/group`

**请求体：**
```json
{
  "name": "我的分组"
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

**实现方法：** `GroupServiceImpl.saveGroup(String groupName)`

---

### 2. 查询分组列表

**接口：** `GET /api/short-link/admin/v1/group`

**返回：**
```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "gid": "abc123",
      "name": "我的分组",
      "sortOrder": 0,
      "shortLinkCount": 5
    },
    {
      "gid": "def456",
      "name": "工作分组",
      "sortOrder": 1,
      "shortLinkCount": 3
    }
  ]
}
```

**实现方法：** `GroupServiceImpl.listGroup()`

---

### 3. 修改分组名称

**接口：** `PUT /api/short-link/admin/v1/group`

**请求体：**
```json
{
  "gid": "abc123",
  "name": "新的分组名称"
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

**实现方法：** `GroupServiceImpl.updateGroup(ShortLinkGroupUpdateReqDTO requestParam)`

---

### 4. 删除分组

**接口：** `DELETE /api/short-link/admin/v1/group`

**参数：**
- `gid` (Query): 分组标识

**返回：**
```json
{
  "code": 0,
  "message": "success",
  "data": null
}
```

**实现方法：** `GroupServiceImpl.deleteGroup(String gid)`

**注意：** 删除是逻辑删除（设置 del_flag = 1），不是物理删除

---

### 5. 排序分组

**接口：** `POST /api/short-link/admin/v1/group/sort`

**请求体：**
```json
[
  {
    "gid": "abc123",
    "sortOrder": 0
  },
  {
    "gid": "def456",
    "sortOrder": 1
  }
]
```

**返回：**
```json
{
  "code": 0,
  "message": "success",
  "data": null
}
```

**实现方法：** `GroupServiceImpl.sortGroup(List<ShortLinkGroupSortReqDTO> requestParam)`

---

## 核心功能实现

### 1. 创建分组（saveGroup）

**流程图：**
```
用户请求创建分组
  ↓
获取分布式锁（防止并发）
  ↓
检查分组数量是否超过限制（默认 20 个）
  ↓
生成唯一的 gid（最多重试 10 次）
  ├─ 检查 Bloom Filter
  ├─ 生成随机 gid
  └─ 插入 t_group_unique 表
  ↓
插入 t_group 表
  ↓
更新 Bloom Filter
  ↓
释放分布式锁
```

**代码解析：**

```java
@Override
public void saveGroup(String username, String groupName) {
    // 1. 获取分布式锁
    RLock lock = redissonClient.getLock(String.format(LOCK_GROUP_CREATE_KEY, username));
    lock.lock();
    try {
        // 2. 检查分组数量
        LambdaQueryWrapper<GroupDO> queryWrapper = Wrappers.lambdaQuery(GroupDO.class)
                .eq(GroupDO::getUsername, username)
                .eq(GroupDO::getDelFlag, 0);
        List<GroupDO> groupDOList = baseMapper.selectList(queryWrapper);
        if (CollUtil.isNotEmpty(groupDOList) && groupDOList.size() == groupMaxNum) {
            throw new ClientException(String.format("已超出最大分组数：%d", groupMaxNum));
        }

        // 3. 生成唯一的 gid（重试机制）
        int retryCount = 0;
        int maxRetries = 10;
        String gid = null;
        while (retryCount < maxRetries) {
            gid = saveGroupUniqueReturnGid();  // 生成 gid
            if (StrUtil.isNotEmpty(gid)) {
                // 4. 插入分组
                GroupDO groupDO = GroupDO.builder()
                        .gid(gid)
                        .sortOrder(0)
                        .username(username)
                        .name(groupName)
                        .build();
                baseMapper.insert(groupDO);

                // 5. 更新 Bloom Filter
                gidRegisterCachePenetrationBloomFilter.add(gid);
                break;
            }
            retryCount++;
        }

        // 6. 检查是否生成成功
        if (StrUtil.isEmpty(gid)) {
            throw new ServiceException("生成分组标识频繁");
        }
    } finally {
        lock.unlock();
    }
}
```

**关键点：**
- **分布式锁**：防止同一用户并发创建分组
- **分组数量限制**：每个用户最多 20 个分组
- **重试机制**：生成 gid 失败时重试 10 次
- **Bloom Filter**：快速判断 gid 是否存在

**生成 gid 的逻辑：**

```java
private String saveGroupUniqueReturnGid() {
    // 1. 生成随机 gid
    String gid = RandomGenerator.generateRandom();

    // 2. 检查 Bloom Filter（快速判断）
    if (gidRegisterCachePenetrationBloomFilter.contains(gid)) {
        return null;  // gid 已存在，返回 null
    }

    // 3. 插入 t_group_unique 表
    GroupUniqueDO groupUniqueDO = GroupUniqueDO.builder()
            .gid(gid)
            .build();
    try {
        groupUniqueMapper.insert(groupUniqueDO);
    } catch (DuplicateKeyException e) {
        return null;  // gid 重复，返回 null
    }

    // 4. 插入成功，返回 gid
    return gid;
}
```

**为什么需要 t_group_unique 表？**
- 保证 gid 的全局唯一性
- 即使 Bloom Filter 有误差，数据库唯一性约束也能保证数据一致性
- 支持并发创建分组

---

### 2. 查询分组列表（listGroup）

**流程图：**
```
用户请求查询分组
  ↓
从数据库查询当前用户的所有分组
  ↓
调用 Project 服务获取每个分组的短链接数量
  ↓
合并数据
  ↓
返回分组列表
```

**代码解析：**

```java
@Override
public List<ShortLinkGroupRespDTO> listGroup() {
    // 1. 查询当前用户的所有分组
    LambdaQueryWrapper<GroupDO> queryWrapper = Wrappers.lambdaQuery(GroupDO.class)
            .eq(GroupDO::getDelFlag, 0)
            .eq(GroupDO::getUsername, UserContext.getUsername())
            .orderByDesc(GroupDO::getSortOrder, GroupDO::getUpdateTime);
    List<GroupDO> groupDOList = baseMapper.selectList(queryWrapper);

    // 2. 调用 Project 服务获取每个分组的短链接数量
    Result<List<ShortLinkGroupCountQueryRespDTO>> listResult =
        shortLinkActualRemoteService.listGroupShortLinkCount(
            groupDOList.stream().map(GroupDO::getGid).toList()
        );

    // 3. 转换为响应 DTO
    List<ShortLinkGroupRespDTO> shortLinkGroupRespDTOList =
        BeanUtil.copyToList(groupDOList, ShortLinkGroupRespDTO.class);

    // 4. 合并短链接数量
    shortLinkGroupRespDTOList.forEach(each -> {
        Optional<ShortLinkGroupCountQueryRespDTO> first = listResult.getData().stream()
                .filter(item -> Objects.equals(item.getGid(), each.getGid()))
                .findFirst();
        first.ifPresent(item -> each.setShortLinkCount(first.get().getShortLinkCount()));
    });

    return shortLinkGroupRespDTOList;
}
```

**关键点：**
- **排序**：按 sortOrder 降序，再按 updateTime 降序
- **远程调用**：通过 FeignClient 调用 Project 服务获取短链接数量
- **数据合并**：将分组信息和短链接数量合并

---

### 3. 修改分组名称（updateGroup）

**代码解析：**

```java
@Override
public void updateGroup(ShortLinkGroupUpdateReqDTO requestParam) {
    // 构建更新条件
    LambdaUpdateWrapper<GroupDO> updateWrapper = Wrappers.lambdaUpdate(GroupDO.class)
            .eq(GroupDO::getUsername, UserContext.getUsername())  // 只能修改自己的分组
            .eq(GroupDO::getGid, requestParam.getGid())
            .eq(GroupDO::getDelFlag, 0);  // 只能修改未删除的分组

    // 构建更新数据
    GroupDO groupDO = new GroupDO();
    groupDO.setName(requestParam.getName());

    // 执行更新
    baseMapper.update(groupDO, updateWrapper);
}
```

**关键点：**
- **权限检查**：只能修改当前用户的分组（通过 UserContext.getUsername()）
- **逻辑删除**：只能修改未删除的分组（del_flag = 0）

---

### 4. 删除分组（deleteGroup）

**代码解析：**

```java
@Override
public void deleteGroup(String gid) {
    // 构建更新条件
    LambdaUpdateWrapper<GroupDO> updateWrapper = Wrappers.lambdaUpdate(GroupDO.class)
            .eq(GroupDO::getUsername, UserContext.getUsername())  // 只能删除自己的分组
            .eq(GroupDO::getGid, gid)
            .eq(GroupDO::getDelFlag, 0);  // 只能删除未删除的分组

    // 构建更新数据（逻辑删除）
    GroupDO groupDO = new GroupDO();
    groupDO.setDelFlag(1);  // 设置删除标志

    // 执行更新
    baseMapper.update(groupDO, updateWrapper);
}
```

**关键点：**
- **逻辑删除**：不是物理删除，而是设置 del_flag = 1
- **权限检查**：只能删除当前用户的分组
- **优势**：可以恢复删除的分组，保留历史数据

---

### 5. 排序分组（sortGroup）

**代码解析：**

```java
@Override
public void sortGroup(List<ShortLinkGroupSortReqDTO> requestParam) {
    // 遍历每个分组，更新排序
    requestParam.forEach(each -> {
        GroupDO groupDO = GroupDO.builder()
                .sortOrder(each.getSortOrder())
                .build();

        LambdaUpdateWrapper<GroupDO> updateWrapper = Wrappers.lambdaUpdate(GroupDO.class)
                .eq(GroupDO::getUsername, UserContext.getUsername())  // 只能排序自己的分组
                .eq(GroupDO::getGid, each.getGid())
                .eq(GroupDO::getDelFlag, 0);  // 只能排序未删除的分组

        baseMapper.update(groupDO, updateWrapper);
    });
}
```

**关键点：**
- **批量更新**：一次请求可以更新多个分组的排序
- **权限检查**：只能排序当前用户的分组

---

## 高并发处理

### 1. 分布式锁

**用途：** 防止同一用户并发创建分组

```java
RLock lock = redissonClient.getLock(String.format(LOCK_GROUP_CREATE_KEY, username));
lock.lock();
try {
    // 创建分组逻辑
} finally {
    lock.unlock();
}
```

**Redis Key：**
```
short-link:lock_group-create:{username}
```

**场景：**
```
用户 A 同时发送 2 个创建分组请求
  ↓
请求1 获取锁 ✓
  ↓
请求2 等待锁
  ↓
请求1 创建分组完成，释放锁
  ↓
请求2 获取锁，创建分组
```

### 2. Bloom Filter

**用途：** 快速判断 gid 是否存在

```java
if (gidRegisterCachePenetrationBloomFilter.contains(gid)) {
    return null;  // gid 已存在
}
```

**配置：**
```java
@Bean
public RBloomFilter<String> gidRegisterCachePenetrationBloomFilter(RedissonClient redissonClient) {
    RBloomFilter<String> cachePenetrationBloomFilter =
        redissonClient.getBloomFilter("gidRegisterCachePenetrationBloomFilter");
    // 容量 2 亿，误差率 0.1%
    cachePenetrationBloomFilter.tryInit(200000000L, 0.001);
    return cachePenetrationBloomFilter;
}
```

### 3. 数据库唯一性约束

**用途：** 最后一道防线，保证 gid 的全局唯一性

```sql
CREATE UNIQUE INDEX idx_gid ON t_group_unique(gid);
```

**三层防护：**
```
Bloom Filter（快速判断）
  ↓
分布式锁（防止并发）
  ↓
数据库唯一性约束（最后防线）
```

---

## 配置参数

### 分组数量限制

```yaml
short-link:
  group:
    max-num: 20  # 每个用户最多 20 个分组
```

**在代码中使用：**
```java
@Value("${short-link.group.max-num}")
private Integer groupMaxNum;
```

---

## 总结表

| 功能 | 方法 | 关键技术 | 说明 |
|------|------|---------|------|
| **创建分组** | saveGroup() | 分布式锁、Bloom Filter、重试机制 | 生成唯一 gid，最多重试 10 次 |
| **查询分组** | listGroup() | 远程调用、数据合并 | 调用 Project 服务获取短链接数量 |
| **修改分组** | updateGroup() | 权限检查 | 只能修改当前用户的分组 |
| **删除分组** | deleteGroup() | 逻辑删除 | 设置 del_flag = 1 |
| **排序分组** | sortGroup() | 批量更新 | 一次请求更新多个分组的排序 |

---

## 数据流示例

### 创建分组流程

```
前端请求
  ↓
POST /api/short-link/admin/v1/group
Body: { "name": "我的分组" }
  ↓
GroupController.save()
  ↓
GroupServiceImpl.saveGroup(groupName)
  ├─ 获取分布式锁
  ├─ 检查分组数量（< 20）
  ├─ 生成唯一 gid
  │  ├─ 检查 Bloom Filter
  │  ├─ 插入 t_group_unique
  │  └─ 更新 Bloom Filter
  ├─ 插入 t_group
  └─ 释放分布式锁
  ↓
返回成功
```

### 查询分组流程

```
前端请求
  ↓
GET /api/short-link/admin/v1/group
  ↓
GroupController.listGroup()
  ↓
GroupServiceImpl.listGroup()
  ├─ 查询 t_group（当前用户）
  ├─ 调用 Project 服务获取短链接数量
  │  └─ FeignClient 远程调用
  └─ 合并数据
  ↓
返回分组列表
```
