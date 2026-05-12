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

package com.lin1473.shortlink.project.mq.consumer;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.Week;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lin1473.shortlink.project.common.convention.exception.ServiceException;
import com.lin1473.shortlink.project.dao.entity.LinkAccessLogsDO;
import com.lin1473.shortlink.project.dao.entity.LinkAccessStatsDO;
import com.lin1473.shortlink.project.dao.entity.LinkBrowserStatsDO;
import com.lin1473.shortlink.project.dao.entity.LinkDeviceStatsDO;
import com.lin1473.shortlink.project.dao.entity.LinkLocaleStatsDO;
import com.lin1473.shortlink.project.dao.entity.LinkNetworkStatsDO;
import com.lin1473.shortlink.project.dao.entity.LinkOsStatsDO;
import com.lin1473.shortlink.project.dao.entity.LinkStatsTodayDO;
import com.lin1473.shortlink.project.dao.entity.ShortLinkGotoDO;
import com.lin1473.shortlink.project.dao.mapper.LinkAccessLogsMapper;
import com.lin1473.shortlink.project.dao.mapper.LinkAccessStatsMapper;
import com.lin1473.shortlink.project.dao.mapper.LinkBrowserStatsMapper;
import com.lin1473.shortlink.project.dao.mapper.LinkDeviceStatsMapper;
import com.lin1473.shortlink.project.dao.mapper.LinkLocaleStatsMapper;
import com.lin1473.shortlink.project.dao.mapper.LinkNetworkStatsMapper;
import com.lin1473.shortlink.project.dao.mapper.LinkOsStatsMapper;
import com.lin1473.shortlink.project.dao.mapper.LinkStatsTodayMapper;
import com.lin1473.shortlink.project.dao.mapper.ShortLinkGotoMapper;
import com.lin1473.shortlink.project.dao.mapper.ShortLinkMapper;
import com.lin1473.shortlink.project.dto.biz.ShortLinkStatsRecordDTO;
import com.lin1473.shortlink.project.mq.idempotent.MessageQueueIdempotentHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// [新增] RocketMQ 注解和接口，替换原 Redis Stream 监听
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// [删除] import org.springframework.data.redis.connection.stream.MapRecord;
// [删除] import org.springframework.data.redis.connection.stream.RecordId;
// [删除] import org.springframework.data.redis.core.StringRedisTemplate;
// [删除] import org.springframework.data.redis.stream.StreamListener;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.LOCK_GID_UPDATE_KEY;
import static com.lin1473.shortlink.project.common.constant.ShortLinkConstant.AMAP_REMOTE_URL;

/**
 * 短链接监控状态保存消息队列消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
// [新增] RocketMQ 消费者注解，替换原 RedisStreamConfiguration 中的手动注册方式
// [删除] 原通过 RedisStreamConfiguration.shortLinkStatsSaveConsumerSubscription() Bean 注册监听
@RocketMQMessageListener(
        topic = "${rocketmq.producer.topic}",
        consumerGroup = "${rocketmq.consumer.group}"
)
// [新增] 实现 RocketMQListener<Map>，替换原 StreamListener<String, MapRecord<String, String, String>>
// [删除] public class ShortLinkStatsSaveConsumer implements StreamListener<String, MapRecord<String, String, String>> {
public class ShortLinkStatsSaveConsumer implements RocketMQListener<Map<String, String>> {

    private final ShortLinkMapper shortLinkMapper;
    private final ShortLinkGotoMapper shortLinkGotoMapper;
    private final RedissonClient redissonClient;
    private final LinkAccessStatsMapper linkAccessStatsMapper;
    private final LinkLocaleStatsMapper linkLocaleStatsMapper;
    private final LinkOsStatsMapper linkOsStatsMapper;
    private final LinkBrowserStatsMapper linkBrowserStatsMapper;
    private final LinkAccessLogsMapper linkAccessLogsMapper;
    private final LinkDeviceStatsMapper linkDeviceStatsMapper;
    private final LinkNetworkStatsMapper linkNetworkStatsMapper;
    private final LinkStatsTodayMapper linkStatsTodayMapper;
    private final MessageQueueIdempotentHandler messageQueueIdempotentHandler;

    // [删除] 原 Redis Stream 消费者需注入 StringRedisTemplate 用于手动 ACK
    // [删除] private final StringRedisTemplate stringRedisTemplate;

    @Value("${short-link.stats.locale.amap-key}")
    private String statsLocaleAmapKey;

    // [新增] onMessage 方法签名改为 Map<String, String>，与 RocketMQListener 泛型对应
    // [删除] public void onMessage(MapRecord<String, String, String> message) {
    @Override
    public void onMessage(Map<String, String> producerMap) {
        // [新增] 从 producerMap 中取 keys（由 Producer 生成的 UUID），作为幂等标识
        // [删除] String stream = message.getStream();
        // [删除] RecordId id = message.getId();
        // [删除] Map<String, String> producerMap = message.getValue();
        String keys = producerMap.get("keys");
        if (messageQueueIdempotentHandler.isMessageBeingConsumed(keys)) {
            if (messageQueueIdempotentHandler.isAccomplish(keys)) {
                return;
            }
            throw new ServiceException("消息未完成流程，需要消息队列重试");
        }
        try {
            ShortLinkStatsRecordDTO statsRecord = JSON.parseObject(producerMap.get("statsRecord"), ShortLinkStatsRecordDTO.class);
            actualSaveShortLinkStats(statsRecord);
            // [删除] 原 Redis Stream 需手动从 Stream 中删除已消费消息
            // [删除] stringRedisTemplate.opsForStream().delete(Objects.requireNonNull(stream), id.getValue());
        } catch (Throwable ex) {
            messageQueueIdempotentHandler.delMessageProcessed(keys);
            log.error("记录短链接监控消费异常", ex);
            // [新增] 向 RocketMQ 抛出异常，触发消息重投，替代原 Stream 消息不删除即为"重投"的机制
            throw ex;
        }
        messageQueueIdempotentHandler.setAccomplish(keys);
    }

    public void actualSaveShortLinkStats(ShortLinkStatsRecordDTO statsRecord) {
        String fullShortUrl = statsRecord.getFullShortUrl();
        RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(String.format(LOCK_GID_UPDATE_KEY, fullShortUrl));
        RLock rLock = readWriteLock.readLock();
        rLock.lock();
        try {
            LambdaQueryWrapper<ShortLinkGotoDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkGotoDO.class)
                    .eq(ShortLinkGotoDO::getFullShortUrl, fullShortUrl);
            ShortLinkGotoDO shortLinkGotoDO = shortLinkGotoMapper.selectOne(queryWrapper);
            String gid = shortLinkGotoDO.getGid();
            Date currentDate = statsRecord.getCurrentDate();
            int hour = DateUtil.hour(currentDate, true);
            Week week = DateUtil.dayOfWeekEnum(currentDate);
            int weekValue = week.getIso8601Value();
            LinkAccessStatsDO linkAccessStatsDO = LinkAccessStatsDO.builder()
                    .pv(1)
                    .uv(statsRecord.getUvFirstFlag() ? 1 : 0)
                    .uip(statsRecord.getUipFirstFlag() ? 1 : 0)
                    .hour(hour)
                    .weekday(weekValue)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkAccessStatsMapper.shortLinkStats(linkAccessStatsDO);
            Map<String, Object> localeParamMap = new HashMap<>();
            localeParamMap.put("key", statsLocaleAmapKey);
            localeParamMap.put("ip", statsRecord.getRemoteAddr());
            String localeResultStr = HttpUtil.get(AMAP_REMOTE_URL, localeParamMap);
            JSONObject localeResultObj = JSON.parseObject(localeResultStr);
            String infoCode = localeResultObj.getString("infocode");
            String actualProvince = "未知";
            String actualCity = "未知";
            if (StrUtil.isNotBlank(infoCode) && StrUtil.equals(infoCode, "10000")) {
                String province = localeResultObj.getString("province");
                boolean unknownFlag = StrUtil.equals(province, "[]");
                LinkLocaleStatsDO linkLocaleStatsDO = LinkLocaleStatsDO.builder()
                        .province(actualProvince = unknownFlag ? actualProvince : province)
                        .city(actualCity = unknownFlag ? actualCity : localeResultObj.getString("city"))
                        .adcode(unknownFlag ? "未知" : localeResultObj.getString("adcode"))
                        .cnt(1)
                        .fullShortUrl(fullShortUrl)
                        .country("中国")
                        .date(currentDate)
                        .build();
                linkLocaleStatsMapper.shortLinkLocaleState(linkLocaleStatsDO);
            }
            LinkOsStatsDO linkOsStatsDO = LinkOsStatsDO.builder()
                    .os(statsRecord.getOs())
                    .cnt(1)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkOsStatsMapper.shortLinkOsState(linkOsStatsDO);
            LinkBrowserStatsDO linkBrowserStatsDO = LinkBrowserStatsDO.builder()
                    .browser(statsRecord.getBrowser())
                    .cnt(1)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkBrowserStatsMapper.shortLinkBrowserState(linkBrowserStatsDO);
            LinkDeviceStatsDO linkDeviceStatsDO = LinkDeviceStatsDO.builder()
                    .device(statsRecord.getDevice())
                    .cnt(1)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkDeviceStatsMapper.shortLinkDeviceState(linkDeviceStatsDO);
            LinkNetworkStatsDO linkNetworkStatsDO = LinkNetworkStatsDO.builder()
                    .network(statsRecord.getNetwork())
                    .cnt(1)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkNetworkStatsMapper.shortLinkNetworkState(linkNetworkStatsDO);
            LinkAccessLogsDO linkAccessLogsDO = LinkAccessLogsDO.builder()
                    .user(statsRecord.getUv())
                    .ip(statsRecord.getRemoteAddr())
                    .browser(statsRecord.getBrowser())
                    .os(statsRecord.getOs())
                    .network(statsRecord.getNetwork())
                    .device(statsRecord.getDevice())
                    .locale(StrUtil.join("-", "中国", actualProvince, actualCity))
                    .fullShortUrl(fullShortUrl)
                    .build();
            linkAccessLogsMapper.insert(linkAccessLogsDO);
            shortLinkMapper.incrementStats(gid, fullShortUrl, 1, statsRecord.getUvFirstFlag() ? 1 : 0, statsRecord.getUipFirstFlag() ? 1 : 0);
            LinkStatsTodayDO linkStatsTodayDO = LinkStatsTodayDO.builder()
                    .todayPv(1)
                    .todayUv(statsRecord.getUvFirstFlag() ? 1 : 0)
                    .todayUip(statsRecord.getUipFirstFlag() ? 1 : 0)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkStatsTodayMapper.shortLinkTodayState(linkStatsTodayDO);
        } finally {
            rLock.unlock();
        }
    }
}
