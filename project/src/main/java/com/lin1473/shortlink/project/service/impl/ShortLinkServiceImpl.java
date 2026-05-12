/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lin1473.shortlink.project.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.text.StrBuilder;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lin1473.shortlink.project.common.convention.exception.ClientException;
import com.lin1473.shortlink.project.common.convention.exception.ServiceException;
import com.lin1473.shortlink.project.common.enums.VailDateTypeEnum;
import com.lin1473.shortlink.project.config.GotoDomainWhiteListConfiguration;
import com.lin1473.shortlink.project.dao.entity.ShortLinkDO;
import com.lin1473.shortlink.project.dao.entity.ShortLinkGotoDO;
import com.lin1473.shortlink.project.dao.mapper.ShortLinkGotoMapper;
import com.lin1473.shortlink.project.dao.mapper.ShortLinkMapper;
import com.lin1473.shortlink.project.dto.biz.ShortLinkStatsRecordDTO;
import com.lin1473.shortlink.project.dto.req.ShortLinkBatchCreateReqDTO;
import com.lin1473.shortlink.project.dto.req.ShortLinkCreateReqDTO;
import com.lin1473.shortlink.project.dto.req.ShortLinkPageReqDTO;
import com.lin1473.shortlink.project.dto.req.ShortLinkUpdateReqDTO;
import com.lin1473.shortlink.project.dto.resp.ShortLinkBaseInfoRespDTO;
import com.lin1473.shortlink.project.dto.resp.ShortLinkBatchCreateRespDTO;
import com.lin1473.shortlink.project.dto.resp.ShortLinkCreateRespDTO;
import com.lin1473.shortlink.project.dto.resp.ShortLinkGroupCountQueryRespDTO;
import com.lin1473.shortlink.project.dto.resp.ShortLinkPageRespDTO;
import com.lin1473.shortlink.project.mq.producer.ShortLinkStatsSaveProducer;
import com.lin1473.shortlink.project.service.ShortLinkService;
import com.lin1473.shortlink.project.toolkit.HashUtil;
import com.lin1473.shortlink.project.toolkit.LinkUtil;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// [新增] Caffeine 本地缓存（注意：Java 包名 benmanes 无连字符，Maven groupId 才有连字符）
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
// [新增] PostConstruct 用于初始化本地缓存和 Lua 脚本
import jakarta.annotation.PostConstruct;
// [新增] ClassPathResource 用于加载 Lua 脚本文件
import org.springframework.core.io.ClassPathResource;
// [新增] DefaultRedisScript 用于执行 Lua 脚本
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
// [新增] ThreadLocalRandom 用于 TTL 随机抖动，防止缓存雪崩
import java.util.concurrent.ThreadLocalRandom;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.GOTO_IS_NULL_SHORT_LINK_KEY;
import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.GOTO_SHORT_LINK_KEY;
import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.LOCK_GID_UPDATE_KEY;
// import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.SHORT_LINK_CREATE_LOCK_KEY;
import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.SHORT_LINK_STATS_UIP_KEY;
import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.SHORT_LINK_STATS_UV_KEY;

/**
 * 短链接接口实现层
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkServiceImpl extends ServiceImpl<ShortLinkMapper, ShortLinkDO> implements ShortLinkService {

    private final RBloomFilter<String> shortUriCreateCachePenetrationBloomFilter;
    private final ShortLinkGotoMapper shortLinkGotoMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ShortLinkStatsSaveProducer shortLinkStatsSaveProducer;
    private final GotoDomainWhiteListConfiguration gotoDomainWhiteListConfiguration;

    @Value("${short-link.domain.default}")
    private String createShortLinkDefaultDomain;

    // [新增] 获取跳转锁的最大等待时间（ms），需 >= P99(DB查询+写Redis) 耗时
    // 默认 200ms（保守值），上线后根据监控的 P99 持锁耗时调小，无需重新部署
    @Value("${short-link.goto-lock.wait-millis:200}")
    private long gotoLockWaitMillis;

    // [新增] 本地锁数组（Striped Lock）：替换 Redisson 分布式锁用于缓存击穿防护
    // 256 个槽位：同一 fullShortUrl hash 到同一个锁，同节点内串行查 DB；不同节点并行（至多 20 次 DB 查询 vs 分布式锁的 1 次）
    // 优势：本地操作无网络 RTT，末位请求等待时间 = 单节点并发/256 × DB耗时，而非全局串行
    // 数组大小取 2 的幂次，方便用位运算取模（& 255）
    private static final int LOCAL_LOCK_SLOTS = 256;
    private final java.util.concurrent.locks.ReentrantLock[] localLocks = new java.util.concurrent.locks.ReentrantLock[LOCAL_LOCK_SLOTS];

    // [新增] Caffeine L1 本地缓存：key=fullShortUrl，value=originUrl
    // 最多缓存 10000 条热门链接，写入 5 分钟后自动过期，无需 Spring Bean 注入
    private Cache<String, String> shortLinkLocalCache;

    // [新增] Redis Lua 脚本：合并 UV/UIP 的 HyperLogLog 写入 + TTL 设置，1 次 RTT 替代原来 2 次 SADD
    private DefaultRedisScript<List<Long>> redirectStatsScript;

    @PostConstruct
    private void initLocalCacheAndScript() {
        // [新增] 初始化本地锁数组
        for (int i = 0; i < LOCAL_LOCK_SLOTS; i++) {
            localLocks[i] = new java.util.concurrent.locks.ReentrantLock();
        }
        // [新增] 初始化 Caffeine 本地缓存
        shortLinkLocalCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
        // [新增] 从 classpath 加载 Lua 脚本
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        try {
            ClassPathResource resource = new ClassPathResource("lua/redirect_stats.lua");
            try (InputStream is = resource.getInputStream()) {
                String scriptText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                script.setScriptText(scriptText);
            }
        } catch (Exception e) {
            throw new RuntimeException("[新增] 加载 redirect_stats.lua 脚本失败", e);
        }
        @SuppressWarnings("unchecked")
        Class<List<Long>> listLongClass = (Class<List<Long>>) (Class<?>) List.class;
        script.setResultType(listLongClass);
        redirectStatsScript = script;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ShortLinkCreateRespDTO createShortLink(ShortLinkCreateReqDTO requestParam) {
        verificationWhitelist(requestParam.getOriginUrl());
        String shortLinkSuffix = generateSuffix(requestParam);
        String fullShortUrl = StrBuilder.create(createShortLinkDefaultDomain)
                .append("/")
                .append(shortLinkSuffix)
                .toString();
        ShortLinkDO shortLinkDO = ShortLinkDO.builder()
                .domain(createShortLinkDefaultDomain)
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .createdType(requestParam.getCreatedType())
                .validDateType(requestParam.getValidDateType())
                .validDate(requestParam.getValidDate())
                .describe(requestParam.getDescribe())
                .shortUri(shortLinkSuffix)
                .enableStatus(0)
                .totalPv(0)
                .totalUv(0)
                .totalUip(0)
                .delTime(0L)
                .fullShortUrl(fullShortUrl)
                .favicon(getFavicon(requestParam.getOriginUrl()))
                .build();
        ShortLinkGotoDO linkGotoDO = ShortLinkGotoDO.builder()
                .fullShortUrl(fullShortUrl)
                .gid(requestParam.getGid())
                .build();
        try {
            baseMapper.insert(shortLinkDO);
            shortLinkGotoMapper.insert(linkGotoDO);
        } catch (DuplicateKeyException ex) {
            // 首先判断是否存在布隆过滤器，如果不存在直接新增
            if (!shortUriCreateCachePenetrationBloomFilter.contains(fullShortUrl)) {
                shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
            }
            throw new ServiceException(String.format("短链接：%s 生成重复", fullShortUrl));
        }
        // [新增] 基础 TTL 加 0~5 分钟随机抖动，防止同一批创建的短链集中过期引发缓存雪崩
        long cacheTtl = LinkUtil.getLinkCacheValidTime(requestParam.getValidDate())
                + ThreadLocalRandom.current().nextLong(0, 5 * 60 * 1000L);
        stringRedisTemplate.opsForValue().set(
                String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),
                requestParam.getOriginUrl(),
                cacheTtl, TimeUnit.MILLISECONDS
        );
        // [新增] 新建短链接时同步写入 L1 本地缓存，预热热启动，首次访问无需穿透到 Redis
        shortLinkLocalCache.put(fullShortUrl, requestParam.getOriginUrl());
        shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
        return ShortLinkCreateRespDTO.builder()
                .fullShortUrl("http://" + shortLinkDO.getFullShortUrl())
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .build();
    }

    // @Transactional(rollbackFor = Exception.class)
    // @Override
    // public ShortLinkCreateRespDTO createShortLinkByLock(ShortLinkCreateReqDTO requestParam) {
    //     verificationWhitelist(requestParam.getOriginUrl());
    //     String fullShortUrl;
    //     RLock lock = redissonClient.getLock(SHORT_LINK_CREATE_LOCK_KEY);
    //     lock.lock();
    //     try {
    //         String shortLinkSuffix = generateSuffixByLock(requestParam);
    //         fullShortUrl = StrBuilder.create(createShortLinkDefaultDomain)
    //                 .append("/")
    //                 .append(shortLinkSuffix)
    //                 .toString();
    //         ShortLinkDO shortLinkDO = ShortLinkDO.builder()
    //                 .domain(createShortLinkDefaultDomain)
    //                 .originUrl(requestParam.getOriginUrl())
    //                 .gid(requestParam.getGid())
    //                 .createdType(requestParam.getCreatedType())
    //                 .validDateType(requestParam.getValidDateType())
    //                 .validDate(requestParam.getValidDate())
    //                 .describe(requestParam.getDescribe())
    //                 .shortUri(shortLinkSuffix)
    //                 .enableStatus(0)
    //                 .totalPv(0)
    //                 .totalUv(0)
    //                 .totalUip(0)
    //                 .delTime(0L)
    //                 .fullShortUrl(fullShortUrl)
    //                 .favicon(getFavicon(requestParam.getOriginUrl()))
    //                 .build();
    //         ShortLinkGotoDO linkGotoDO = ShortLinkGotoDO.builder()
    //                 .fullShortUrl(fullShortUrl)
    //                 .gid(requestParam.getGid())
    //                 .build();
    //         try {
    //             baseMapper.insert(shortLinkDO);
    //             shortLinkGotoMapper.insert(linkGotoDO);
    //         } catch (DuplicateKeyException ex) {
    //             throw new ServiceException(String.format("短链接：%s 生成重复", fullShortUrl));
    //         }
    //         long cacheTtl = LinkUtil.getLinkCacheValidTime(requestParam.getValidDate())
    //                 + ThreadLocalRandom.current().nextLong(0, 5 * 60 * 1000L);
    //         stringRedisTemplate.opsForValue().set(
    //                 String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),
    //                 requestParam.getOriginUrl(),
    //                 cacheTtl, TimeUnit.MILLISECONDS
    //         );
    //         shortLinkLocalCache.put(fullShortUrl, requestParam.getOriginUrl());
    //         shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
    //     } finally {
    //         lock.unlock();
    //     }
    //     return ShortLinkCreateRespDTO.builder()
    //             .fullShortUrl("http://" + fullShortUrl)
    //             .originUrl(requestParam.getOriginUrl())
    //             .gid(requestParam.getGid())
    //             .build();
    // }

    @Override
    public ShortLinkBatchCreateRespDTO batchCreateShortLink(ShortLinkBatchCreateReqDTO requestParam) {
        List<String> originUrls = requestParam.getOriginUrls();
        List<String> describes = requestParam.getDescribes();
        List<ShortLinkBaseInfoRespDTO> result = new ArrayList<>();
        for (int i = 0; i < originUrls.size(); i++) {
            ShortLinkCreateReqDTO shortLinkCreateReqDTO = BeanUtil.toBean(requestParam, ShortLinkCreateReqDTO.class);
            shortLinkCreateReqDTO.setOriginUrl(originUrls.get(i));
            shortLinkCreateReqDTO.setDescribe(describes.get(i));
            try {
                ShortLinkCreateRespDTO shortLink = createShortLink(shortLinkCreateReqDTO);
                ShortLinkBaseInfoRespDTO linkBaseInfoRespDTO = ShortLinkBaseInfoRespDTO.builder()
                        .fullShortUrl(shortLink.getFullShortUrl())
                        .originUrl(shortLink.getOriginUrl())
                        .describe(describes.get(i))
                        .build();
                result.add(linkBaseInfoRespDTO);
            } catch (Throwable ex) {
                log.error("批量创建短链接失败，原始参数：{}", originUrls.get(i));
            }
        }
        return ShortLinkBatchCreateRespDTO.builder()
                .total(result.size())
                .baseLinkInfos(result)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateShortLink(ShortLinkUpdateReqDTO requestParam) {
        verificationWhitelist(requestParam.getOriginUrl());
        LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
                .eq(ShortLinkDO::getGid, requestParam.getOriginGid())
                .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                .eq(ShortLinkDO::getDelFlag, 0)
                .eq(ShortLinkDO::getEnableStatus, 0);
        ShortLinkDO hasShortLinkDO = baseMapper.selectOne(queryWrapper);
        if (hasShortLinkDO == null) {
            throw new ClientException("短链接记录不存在");
        }
        if (Objects.equals(hasShortLinkDO.getGid(), requestParam.getGid())) {
            LambdaUpdateWrapper<ShortLinkDO> updateWrapper = Wrappers.lambdaUpdate(ShortLinkDO.class)
                    .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                    .eq(ShortLinkDO::getGid, requestParam.getGid())
                    .eq(ShortLinkDO::getDelFlag, 0)
                    .eq(ShortLinkDO::getEnableStatus, 0)
                    .set(Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType()), ShortLinkDO::getValidDate, null);
            ShortLinkDO shortLinkDO = ShortLinkDO.builder()
                    .domain(hasShortLinkDO.getDomain())
                    .shortUri(hasShortLinkDO.getShortUri())
                    .favicon(Objects.equals(requestParam.getOriginUrl(), hasShortLinkDO.getOriginUrl()) ? hasShortLinkDO.getFavicon() : getFavicon(requestParam.getOriginUrl()))
                    .createdType(hasShortLinkDO.getCreatedType())
                    .gid(requestParam.getGid())
                    .originUrl(requestParam.getOriginUrl())
                    .describe(requestParam.getDescribe())
                    .validDateType(requestParam.getValidDateType())
                    .validDate(requestParam.getValidDate())
                    .build();
            baseMapper.update(shortLinkDO, updateWrapper);
        } else {
            RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(String.format(LOCK_GID_UPDATE_KEY, requestParam.getFullShortUrl()));
            RLock rLock = readWriteLock.writeLock();
            rLock.lock();
            try {
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
                        .favicon(Objects.equals(requestParam.getOriginUrl(), hasShortLinkDO.getOriginUrl()) ? hasShortLinkDO.getFavicon() : getFavicon(requestParam.getOriginUrl()))
                        .delTime(0L)
                        .build();
                baseMapper.insert(shortLinkDO);
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
        if (!Objects.equals(hasShortLinkDO.getValidDateType(), requestParam.getValidDateType())
                || !Objects.equals(hasShortLinkDO.getValidDate(), requestParam.getValidDate())
                || !Objects.equals(hasShortLinkDO.getOriginUrl(), requestParam.getOriginUrl())) {
            stringRedisTemplate.delete(String.format(GOTO_SHORT_LINK_KEY, requestParam.getFullShortUrl()));
            // [新增] 短链接信息变更时，同步清除 L1 本地缓存，防止继续返回旧的 originUrl
            shortLinkLocalCache.invalidate(requestParam.getFullShortUrl());
            Date currentDate = new Date();
            if (hasShortLinkDO.getValidDate() != null && hasShortLinkDO.getValidDate().before(currentDate)) {
                if (Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType()) || requestParam.getValidDate().after(currentDate)) {
                    stringRedisTemplate.delete(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, requestParam.getFullShortUrl()));
                }
            }
        }
    }

    @Override
    public IPage<ShortLinkPageRespDTO> pageShortLink(ShortLinkPageReqDTO requestParam) {
        IPage<ShortLinkDO> resultPage = baseMapper.pageLink(requestParam);
        return resultPage.convert(each -> {
            ShortLinkPageRespDTO result = BeanUtil.toBean(each, ShortLinkPageRespDTO.class);
            result.setDomain("http://" + result.getDomain());
            return result;
        });
    }

    @Override
    public List<ShortLinkGroupCountQueryRespDTO> listGroupShortLinkCount(List<String> requestParam) {
        QueryWrapper<ShortLinkDO> queryWrapper = Wrappers.query(new ShortLinkDO())
                .select("gid as gid, count(*) as shortLinkCount")
                .in("gid", requestParam)
                .eq("enable_status", 0)
                .eq("del_flag", 0)
                .eq("del_time", 0L)
                .groupBy("gid");
        List<Map<String, Object>> shortLinkDOList = baseMapper.selectMaps(queryWrapper);
        return BeanUtil.copyToList(shortLinkDOList, ShortLinkGroupCountQueryRespDTO.class);
    }

    @SneakyThrows
    @Override
    public void restoreUrl(String shortUri, ServletRequest request, ServletResponse response) {
        String serverName = request.getServerName();
        String serverPort = Optional.of(request.getServerPort())
                .filter(each -> !Objects.equals(each, 80))
                .map(String::valueOf)
                .map(each -> ":" + each)
                .orElse("");
        String fullShortUrl = serverName + serverPort + "/" + shortUri;
        // [新增] 先查 Caffeine L1 本地缓存，命中则完全跳过 Redis 网络调用
        // [删除] String originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));
        String originalLink = shortLinkLocalCache.getIfPresent(fullShortUrl);
        if (StrUtil.isBlank(originalLink)) {
            // [新增] L1 未命中，查 Redis L2 缓存
            originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));
            if (StrUtil.isNotBlank(originalLink)) {
                // [新增] Redis 命中，回填 L1 缓存，下次请求直接走本地
                shortLinkLocalCache.put(fullShortUrl, originalLink);
            }
        }
        if (StrUtil.isNotBlank(originalLink)) {
            shortLinkStats(buildLinkStatsRecordAndSetUser(fullShortUrl, request, response));
            ((HttpServletResponse) response).sendRedirect(originalLink);
            return;
        }
        boolean contains = shortUriCreateCachePenetrationBloomFilter.contains(fullShortUrl);
        if (!contains) {
            ((HttpServletResponse) response).sendRedirect("/page/notfound");
            return;
        }
        String gotoIsNullShortLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl));
        if (StrUtil.isNotBlank(gotoIsNullShortLink)) {
            ((HttpServletResponse) response).sendRedirect("/page/notfound");
            return;
        }
        // [修改] 用本地锁替换 Redisson 分布式锁，防止缓存击穿
        // 原因：分布式锁将全局 N 个请求串行化，末位请求等待 N×1ms；
        //       本地锁仅在单节点内串行，20 节点部署时等待时间缩短 20 倍，且无 Redis 网络 RTT 开销。
        // 代价：多节点会同时有多个 DB 查询（最多等于节点数），但写入结果完全相同（幂等），不会数据错乱。
        // [删除] RLock lock = redissonClient.getLock(...); → 移除分布式锁
        int lockSlot = (fullShortUrl.hashCode() & Integer.MAX_VALUE) & (LOCAL_LOCK_SLOTS - 1);
        java.util.concurrent.locks.ReentrantLock localLock = localLocks[lockSlot];
        boolean acquired;
        try {
            acquired = localLock.tryLock(gotoLockWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ((HttpServletResponse) response).sendRedirect("/page/notfound");
            return;
        }
        if (!acquired) {
            log.warn("[短链接跳转] 本地锁等待超时（{}ms），fullShortUrl：{}", gotoLockWaitMillis, fullShortUrl);
            ((HttpServletResponse) response).sendRedirect("/page/notfound");
            return;
        }
        long lockAcquireTime = System.currentTimeMillis();
        try {
            originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));
            if (StrUtil.isNotBlank(originalLink)) {
                // [新增] 锁内 double-check 命中，回填 L1 缓存
                shortLinkLocalCache.put(fullShortUrl, originalLink);
                shortLinkStats(buildLinkStatsRecordAndSetUser(fullShortUrl, request, response));
                ((HttpServletResponse) response).sendRedirect(originalLink);
                return;
            }
            gotoIsNullShortLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl));
            if (StrUtil.isNotBlank(gotoIsNullShortLink)) {
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            LambdaQueryWrapper<ShortLinkGotoDO> linkGotoQueryWrapper = Wrappers.lambdaQuery(ShortLinkGotoDO.class)
                    .eq(ShortLinkGotoDO::getFullShortUrl, fullShortUrl);
            ShortLinkGotoDO shortLinkGotoDO = shortLinkGotoMapper.selectOne(linkGotoQueryWrapper);
            if (shortLinkGotoDO == null) {
                stringRedisTemplate.opsForValue().set(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl), "-", 30, TimeUnit.MINUTES);
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
                    .eq(ShortLinkDO::getGid, shortLinkGotoDO.getGid())
                    .eq(ShortLinkDO::getFullShortUrl, fullShortUrl)
                    .eq(ShortLinkDO::getDelFlag, 0)
                    .eq(ShortLinkDO::getEnableStatus, 0);
            ShortLinkDO shortLinkDO = baseMapper.selectOne(queryWrapper);
            if (shortLinkDO == null || (shortLinkDO.getValidDate() != null && shortLinkDO.getValidDate().before(new Date()))) {
                stringRedisTemplate.opsForValue().set(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl), "-", 30, TimeUnit.MINUTES);
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            // [新增] 同样加 0~5 分钟随机抖动，防止缓存雪崩（与 createShortLink 保持一致）
            long cacheTtl = LinkUtil.getLinkCacheValidTime(shortLinkDO.getValidDate())
                    + ThreadLocalRandom.current().nextLong(0, 5 * 60 * 1000L);
            stringRedisTemplate.opsForValue().set(
                    String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),
                    shortLinkDO.getOriginUrl(),
                    cacheTtl, TimeUnit.MILLISECONDS
            );
            // [新增] DB 查询成功，同步写入 L1 本地缓存
            shortLinkLocalCache.put(fullShortUrl, shortLinkDO.getOriginUrl());
            shortLinkStats(buildLinkStatsRecordAndSetUser(fullShortUrl, request, response));
            ((HttpServletResponse) response).sendRedirect(shortLinkDO.getOriginUrl());
        } finally {
            // [新增] 记录持锁耗时，用于监控 P99，指导 short-link.goto-lock.wait-millis 配置值
            long holdMs = System.currentTimeMillis() - lockAcquireTime;
            if (holdMs > gotoLockWaitMillis / 2) {
                log.warn("[短链接跳转] 持锁耗时 {}ms 超过 waitMillis 50%，建议检查 DB 性能或上调 short-link.goto-lock.wait-millis", holdMs);
            } else {
                log.debug("[短链接跳转] 持锁耗时 {}ms，fullShortUrl：{}", holdMs, fullShortUrl);
            }
            localLock.unlock();
        }
    }

    private ShortLinkStatsRecordDTO buildLinkStatsRecordAndSetUser(String fullShortUrl, ServletRequest request, ServletResponse response) {
        AtomicBoolean uvFirstFlag = new AtomicBoolean();
        // [新增] 标记 uv 值是否来自已有 Cookie（需通过 Lua 结果判断是否首次）
        // [删除] 原逻辑在 lambda 内直接调用 SADD 并判断返回值，现统一移到 Lua 脚本执行后处理
        AtomicBoolean uvFromCookie = new AtomicBoolean(false);
        Cookie[] cookies = ((HttpServletRequest) request).getCookies();
        AtomicReference<String> uv = new AtomicReference<>();
        Runnable addResponseCookieTask = () -> {
            uv.set(UUID.fastUUID().toString());
            Cookie uvCookie = new Cookie("uv", uv.get());
            uvCookie.setMaxAge(60 * 60 * 24 * 30);
            uvCookie.setPath(StrUtil.sub(fullShortUrl, fullShortUrl.indexOf("/"), fullShortUrl.length()));
            ((HttpServletResponse) response).addCookie(uvCookie);
            // [新增] 无 cookie 代表全新用户，直接标记为首次访问，无需查 Redis
            // [删除] uvFirstFlag.set(Boolean.TRUE); 位置不变，但下面的 SADD 调用已删除
            // [删除] stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UV_KEY + fullShortUrl, uv.get());
            uvFirstFlag.set(Boolean.TRUE);
        };
        if (ArrayUtil.isNotEmpty(cookies)) {
            Arrays.stream(cookies)
                    .filter(each -> Objects.equals(each.getName(), "uv"))
                    .findFirst()
                    .map(Cookie::getValue)
                    .ifPresentOrElse(each -> {
                        uv.set(each);
                        // [删除] Long uvAdded = stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UV_KEY + fullShortUrl, each);
                        // [删除] uvFirstFlag.set(uvAdded != null && uvAdded > 0L);
                        // [新增] 有 cookie 时暂不判断是否首次，等 Lua 脚本执行后通过返回值判断
                        uvFromCookie.set(true);
                    }, addResponseCookieTask);
        } else {
            addResponseCookieTask.run();
        }
        String remoteAddr = LinkUtil.getActualIp(((HttpServletRequest) request));
        String os = LinkUtil.getOs(((HttpServletRequest) request));
        String browser = LinkUtil.getBrowser(((HttpServletRequest) request));
        String device = LinkUtil.getDevice(((HttpServletRequest) request));
        String network = LinkUtil.getNetwork(((HttpServletRequest) request));
        // [删除] Long uipAdded = stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UIP_KEY + fullShortUrl, remoteAddr);
        // [删除] boolean uipFirstFlag = uipAdded != null && uipAdded > 0L;
        // [新增] 用 Lua 脚本一次 RTT 完成 UV + UIP 的 HyperLogLog 写入和 TTL 设置
        //        原来: 2 次 SADD（无 TTL，Set 无限增长）= 2 次 Redis RTT
        //        现在: 1 次 PFADD+EXPIRE Lua 脚本（HLL 内存降低约 100 倍）= 1 次 Redis RTT
        List<Long> luaResult = stringRedisTemplate.execute(
                redirectStatsScript,
                Arrays.asList(SHORT_LINK_STATS_UV_KEY + fullShortUrl, SHORT_LINK_STATS_UIP_KEY + fullShortUrl),
                uv.get(), remoteAddr
        );
        // [新增] 有 cookie 时通过 Lua 返回值判断是否首次（PFADD 返回 1 表示新增）；
        //        无 cookie 时 uvFirstFlag 已在 addResponseCookieTask 中设为 true，无需覆盖
        if (uvFromCookie.get()) {
            uvFirstFlag.set(luaResult != null && luaResult.get(0) == 1L);
        }
        boolean uipFirstFlag = luaResult != null && luaResult.get(1) == 1L;
        return ShortLinkStatsRecordDTO.builder()
                .fullShortUrl(fullShortUrl)
                .uv(uv.get())
                .uvFirstFlag(uvFirstFlag.get())
                .uipFirstFlag(uipFirstFlag)
                .remoteAddr(remoteAddr)
                .os(os)
                .browser(browser)
                .device(device)
                .network(network)
                .currentDate(new Date())
                .build();
    }

    @Override
    public void shortLinkStats(ShortLinkStatsRecordDTO statsRecord) {
        Map<String, String> producerMap = new HashMap<>();
        producerMap.put("statsRecord", JSON.toJSONString(statsRecord));
        shortLinkStatsSaveProducer.send(producerMap);
    }

    private String generateSuffix(ShortLinkCreateReqDTO requestParam) {
        int customGenerateCount = 0;
        String shorUri;
        while (true) {
            if (customGenerateCount > 10) {
                throw new ServiceException("短链接频繁生成，请稍后再试");
            }
            String originUrl = requestParam.getOriginUrl();
            originUrl += UUID.randomUUID().toString();
            shorUri = HashUtil.hashToBase62(originUrl);
            if (!shortUriCreateCachePenetrationBloomFilter.contains(createShortLinkDefaultDomain + "/" + shorUri)) {
                break;
            }
            customGenerateCount++;
        }
        return shorUri;
    }

    // private String generateSuffixByLock(ShortLinkCreateReqDTO requestParam) {
    //     int customGenerateCount = 0;
    //     String shorUri;
    //     while (true) {
    //         if (customGenerateCount > 10) {
    //             throw new ServiceException("短链接频繁生成，请稍后再试");
    //         }
    //         String originUrl = requestParam.getOriginUrl();
    //         originUrl += UUID.randomUUID().toString();
    //         shorUri = HashUtil.hashToBase62(originUrl);
    //         LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
    //                 .eq(ShortLinkDO::getGid, requestParam.getGid())
    //                 .eq(ShortLinkDO::getFullShortUrl, createShortLinkDefaultDomain + "/" + shorUri)
    //                 .eq(ShortLinkDO::getDelFlag, 0);
    //         ShortLinkDO shortLinkDO = baseMapper.selectOne(queryWrapper);
    //         if (shortLinkDO == null) {
    //             break;
    //         }
    //         customGenerateCount++;
    //     }
    //     return shorUri;
    // }

    @SneakyThrows
    private String getFavicon(String url) {
        URL targetUrl = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
        connection.setRequestMethod("GET");
        connection.connect();
        int responseCode = connection.getResponseCode();
        if (HttpURLConnection.HTTP_OK == responseCode) {
            Document document = Jsoup.connect(url).get();
            Element faviconLink = document.select("link[rel~=(?i)^(shortcut )?icon]").first();
            if (faviconLink != null) {
                return faviconLink.attr("abs:href");
            }
        }
        return null;
    }

    private void verificationWhitelist(String originUrl) {
        Boolean enable = gotoDomainWhiteListConfiguration.getEnable();
        if (enable == null || !enable) {
            return;
        }
        String domain = LinkUtil.extractDomain(originUrl);
        if (StrUtil.isBlank(domain)) {
            throw new ClientException("跳转链接填写错误");
        }
        List<String> details = gotoDomainWhiteListConfiguration.getDetails();
        if (!details.contains(domain)) {
            throw new ClientException("演示环境为避免恶意攻击，请生成以下网站跳转链接：" + gotoDomainWhiteListConfiguration.getNames());
        }
    }
}
