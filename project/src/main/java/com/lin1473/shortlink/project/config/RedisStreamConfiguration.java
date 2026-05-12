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

package com.lin1473.shortlink.project.config;

// [删除] 本类已被 RocketMQ 替换，原 Redis Stream 监听容器相关配置全部注释
// [删除] 消费者注册改为在 ShortLinkStatsSaveConsumer 上直接使用 @RocketMQMessageListener 注解
// [删除] 线程池 asyncStreamConsumer Bean 也随之废弃，RocketMQ 消费者线程由框架管理

// [删除] import com.lin1473.shortlink.project.mq.consumer.ShortLinkStatsSaveConsumer;
// [删除] import lombok.RequiredArgsConstructor;
// [删除] import org.springframework.context.annotation.Bean;
// [删除] import org.springframework.context.annotation.Configuration;
// [删除] import org.springframework.data.redis.connection.RedisConnectionFactory;
// [删除] import org.springframework.data.redis.connection.stream.Consumer;
// [删除] import org.springframework.data.redis.connection.stream.MapRecord;
// [删除] import org.springframework.data.redis.connection.stream.ReadOffset;
// [删除] import org.springframework.data.redis.connection.stream.StreamOffset;
// [删除] import org.springframework.data.redis.stream.StreamMessageListenerContainer;
// [删除] import org.springframework.data.redis.stream.Subscription;
// [删除] import java.time.Duration;
// [删除] import java.util.concurrent.ExecutorService;
// [删除] import java.util.concurrent.SynchronousQueue;
// [删除] import java.util.concurrent.ThreadPoolExecutor;
// [删除] import java.util.concurrent.TimeUnit;
// [删除] import java.util.concurrent.atomic.AtomicInteger;
// [删除] import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.SHORT_LINK_STATS_STREAM_GROUP_KEY;
// [删除] import static com.lin1473.shortlink.project.common.constant.RedisKeyConstant.SHORT_LINK_STATS_STREAM_TOPIC_KEY;

/**
 * [删除] Redis Stream 消息队列配置 —— 已由 RocketMQ 替代，本类不再生效
 *
 * 原功能说明（保留供参考）：
 *   - asyncStreamConsumer()：创建单线程池，用于驱动 StreamMessageListenerContainer 轮询 Redis Stream
 *   - shortLinkStatsSaveConsumerSubscription()：创建 StreamMessageListenerContainer，
 *     以 Consumer Group 方式订阅 short-link:stats-stream，batchSize=10，pollTimeout=3s，autoAcknowledge=true
 *
 * 替换后：
 *   - 消费者改为 @RocketMQMessageListener(topic, consumerGroup)，由 RocketMQ 框架管理线程和 ACK
 *   - 本类保留为空，不注册任何 Bean
 */
// [删除] @Configuration
// [删除] @RequiredArgsConstructor
// [删除] public class RedisStreamConfiguration {
// [删除]     private final RedisConnectionFactory redisConnectionFactory;
// [删除]     private final ShortLinkStatsSaveConsumer shortLinkStatsSaveConsumer;
// [删除]
// [删除]     @Bean
// [删除]     public ExecutorService asyncStreamConsumer() {
// [删除]         AtomicInteger index = new AtomicInteger();
// [删除]         return new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
// [删除]                 new SynchronousQueue<>(),
// [删除]                 runnable -> {
// [删除]                     Thread thread = new Thread(runnable);
// [删除]                     thread.setName("stream_consumer_short-link_stats_" + index.incrementAndGet());
// [删除]                     thread.setDaemon(true);
// [删除]                     return thread;
// [删除]                 },
// [删除]                 new ThreadPoolExecutor.DiscardOldestPolicy());
// [删除]     }
// [删除]
// [删除]     @Bean
// [删除]     public Subscription shortLinkStatsSaveConsumerSubscription(ExecutorService asyncStreamConsumer) {
// [删除]         StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
// [删除]                 StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
// [删除]                         .batchSize(10)
// [删除]                         .executor(asyncStreamConsumer)
// [删除]                         .pollTimeout(Duration.ofSeconds(3))
// [删除]                         .build();
// [删除]         StreamMessageListenerContainer.StreamReadRequest<String> streamReadRequest =
// [删除]                 StreamMessageListenerContainer.StreamReadRequest
// [删除]                         .builder(StreamOffset.create(SHORT_LINK_STATS_STREAM_TOPIC_KEY, ReadOffset.lastConsumed()))
// [删除]                         .cancelOnError(throwable -> false)
// [删除]                         .consumer(Consumer.from(SHORT_LINK_STATS_STREAM_GROUP_KEY, "stats-consumer"))
// [删除]                         .autoAcknowledge(true)
// [删除]                         .build();
// [删除]         StreamMessageListenerContainer<String, MapRecord<String, String, String>> listenerContainer =
// [删除]                 StreamMessageListenerContainer.create(redisConnectionFactory, options);
// [删除]         Subscription subscription = listenerContainer.register(streamReadRequest, shortLinkStatsSaveConsumer);
// [删除]         listenerContainer.start();
// [删除]         return subscription;
// [删除]     }
// [删除] }
public class RedisStreamConfiguration {
    // [删除] 本类已废弃，所有 Bean 已注释，保留文件便于代码回溯
}
