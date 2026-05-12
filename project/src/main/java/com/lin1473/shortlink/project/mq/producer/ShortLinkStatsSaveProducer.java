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

package com.lin1473.shortlink.project.mq.producer;

// [新增] RocketMQ 相关 import，替换原 Redis Stream 依赖
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

// [删除] import org.springframework.data.redis.core.StringRedisTemplate;
// [删除] import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.SHORT_LINK_STATS_STREAM_TOPIC_KEY;

import java.util.Map;
import java.util.UUID;

/**
 * 短链接监控状态保存消息队列生产者
 */
// [新增] @Slf4j 用于 RocketMQ 发送结果日志
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortLinkStatsSaveProducer {

    // [新增] 注入 RocketMQTemplate，替换原 StringRedisTemplate
    private final RocketMQTemplate rocketMQTemplate;

    // [删除] private final StringRedisTemplate stringRedisTemplate;

    // [新增] 从配置文件读取 Topic
    @Value("${rocketmq.producer.topic}")
    private String statsSaveTopic;

    /**
     * 发送短链接统计消息
     */
    public void send(Map<String, String> producerMap) {
        // [新增] 生成唯一 keys，供消费者幂等判断
        String keys = UUID.randomUUID().toString();
        producerMap.put("keys", keys);
        Message<Map<String, String>> message = MessageBuilder
                .withPayload(producerMap)
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .build();
        // [新增] 改为异步发送，不阻塞短链接跳转主线程
        // [删除] sendResult = rocketMQTemplate.syncSend(statsSaveTopic, message, 2000L);
        // [删除] 原 Redis Stream：stringRedisTemplate.opsForStream().add(SHORT_LINK_STATS_STREAM_TOPIC_KEY, producerMap);
        rocketMQTemplate.asyncSend(statsSaveTopic, message, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.info("[消息访问统计监控] 消息发送成功，消息ID：{}，消息Keys：{}", sendResult.getMsgId(), keys);
            }

            @Override
            public void onException(Throwable ex) {
                log.error("[消息访问统计监控] 消息发送失败，消息Keys：{}，消息体：{}", keys, JSON.toJSONString(producerMap), ex);
            }
        });
    }
}
