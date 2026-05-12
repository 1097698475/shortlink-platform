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

package com.lin1473.shortlink.project.seed;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * Temporary demo-data seeder. Run with:
 * mvn -pl project test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.lin1473.shortlink.project.seed.SeedDemoData
 */
public final class SeedDemoData {

    private static final String SHARDINGSPHERE_URL = "jdbc:shardingsphere:classpath:shardingsphere-seed.yaml";
    private static final String MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/link"
            + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String MYSQL_USERNAME = "root";
    private static final String MYSQL_PASSWORD = "123456";
    private static final String SEED_PREFIX = "seed_";
    private static final String DOMAIN = "localhost:8000";
    private static final String SHORT_LINK_CACHE_KEY = "short-link:goto:%s";
    private static final String BLOOM_FILTER_NAME = "shortUriCreateCachePenetrationBloomFilter";
    private static final long DEFAULT_CACHE_VALID_TIME = 2626560000L;
    private static final Random RANDOM = new Random(20260512L);

    private SeedDemoData() {
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver");
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection shardingConnection = DriverManager.getConnection(SHARDINGSPHERE_URL);
             Connection mysqlConnection = DriverManager.getConnection(MYSQL_URL, MYSQL_USERNAME, MYSQL_PASSWORD)) {
            shardingConnection.setAutoCommit(false);
            mysqlConnection.setAutoCommit(false);
            try {
                cleanup(mysqlConnection);
                mysqlConnection.commit();
                SeedSummary summary = seed(shardingConnection, mysqlConnection);
                shardingConnection.commit();
                mysqlConnection.commit();
                syncRedisAndBloomFilter(summary.seedLinks);
                System.out.printf(
                        "Seed completed: users=%d, groups=%d, links=%d, accessStats=%d, accessLogs=%d, redis=%d, bloom=%d%n",
                        summary.users,
                        summary.groups,
                        summary.links,
                        summary.accessStats,
                        summary.accessLogs,
                        summary.seedLinks.size(),
                        summary.seedLinks.size());
            } catch (Exception ex) {
                shardingConnection.rollback();
                mysqlConnection.rollback();
                throw ex;
            }
        }
    }

    private static void cleanup(Connection connection) throws SQLException {
        executeUpdate(connection, "DELETE FROM t_link_access_logs WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
        executeUpdate(connection, "DELETE FROM t_link_access_stats WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
        executeUpdate(connection, "DELETE FROM t_link_browser_stats WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
        executeUpdate(connection, "DELETE FROM t_link_device_stats WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
        executeUpdate(connection, "DELETE FROM t_link_locale_stats WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
        executeUpdate(connection, "DELETE FROM t_link_network_stats WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
        executeUpdate(connection, "DELETE FROM t_link_os_stats WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
        executeUpdate(connection, "DELETE FROM t_link_stats_today WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
        executeUpdate(connection, "DELETE FROM t_link_access_logs WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
        executeUpdate(connection, "DELETE FROM t_link_access_stats WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
        executeUpdate(connection, "DELETE FROM t_link_browser_stats WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
        executeUpdate(connection, "DELETE FROM t_link_device_stats WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
        executeUpdate(connection, "DELETE FROM t_link_locale_stats WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
        executeUpdate(connection, "DELETE FROM t_link_network_stats WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
        executeUpdate(connection, "DELETE FROM t_link_os_stats WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
        executeUpdate(connection, "DELETE FROM t_link_stats_today WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
        executeUpdate(connection, "DELETE FROM t_group_unique WHERE gid LIKE ?", SEED_PREFIX + "%");
        for (int i = 0; i < 16; i++) {
            executeUpdate(connection, "DELETE FROM t_link_goto_" + i + " WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
            executeUpdate(connection, "DELETE FROM t_link_" + i + " WHERE full_short_url LIKE ?", SEED_PREFIX + "%");
            executeUpdate(connection, "DELETE FROM t_link_goto_" + i + " WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
            executeUpdate(connection, "DELETE FROM t_link_" + i + " WHERE full_short_url LIKE ?", DOMAIN + "/sd%");
            executeUpdate(connection, "DELETE FROM t_group_" + i + " WHERE username LIKE ?", SEED_PREFIX + "%");
            executeUpdate(connection, "DELETE FROM t_user_" + i + " WHERE username LIKE ?", SEED_PREFIX + "%");
        }
    }

    private static SeedSummary seed(Connection shardingConnection, Connection mysqlConnection) throws SQLException {
        SeedSummary summary = new SeedSummary();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        LocalDate today = LocalDate.now();
        List<String> allFullShortUrls = new ArrayList<>();
        for (int userIndex = 1; userIndex <= 5; userIndex++) {
            String username = SEED_PREFIX + "user" + userIndex;
            insertUser(shardingConnection, username, userIndex, now);
            summary.users++;
            int groupCount = 5 + RANDOM.nextInt(4);
            for (int groupIndex = 1; groupIndex <= groupCount; groupIndex++) {
                String gid = SEED_PREFIX + "u" + userIndex + "_g" + groupIndex;
                insertGroup(shardingConnection, gid, username, "种子分组-" + userIndex + "-" + groupIndex, groupIndex, now);
                insertGroupUnique(mysqlConnection, gid);
                summary.groups++;
                int linkCount = 3 + RANDOM.nextInt(3);
                for (int linkIndex = 1; linkIndex <= linkCount; linkIndex++) {
                    String shortUri = "sd" + userIndex + groupIndex + linkIndex + randomBase62(3);
                    String fullShortUrl = DOMAIN + "/" + shortUri;
                    String originUrl = "https://example.com/seed/users/" + userIndex
                            + "/groups/" + groupIndex + "/links/" + linkIndex;
                    int totalPv = 80 + RANDOM.nextInt(420);
                    int totalUv = 20 + RANDOM.nextInt(120);
                    int totalUip = 15 + RANDOM.nextInt(90);
                    insertLink(shardingConnection, gid, shortUri, fullShortUrl, originUrl, totalPv, totalUv, totalUip, now);
                    insertLinkGoto(shardingConnection, gid, fullShortUrl);
                    seedStats(mysqlConnection, fullShortUrl, today, totalPv, totalUv, totalUip, now, summary);
                    summary.seedLinks.add(new SeedLink(fullShortUrl, originUrl));
                    allFullShortUrls.add(fullShortUrl);
                    summary.links++;
                }
            }
        }
        System.out.println("Sample fullShortUrl: " + (allFullShortUrls.isEmpty() ? "none" : allFullShortUrls.get(0)));
        return summary;
    }

    private static void syncRedisAndBloomFilter(List<SeedLink> seedLinks) {
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setPassword("123456");
        RedissonClient redissonClient = Redisson.create(config);
        try {
            RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_NAME);
            bloomFilter.tryInit(100000000L, 0.001);
            for (SeedLink each : seedLinks) {
                RBucket<String> bucket = redissonClient.getBucket(String.format(SHORT_LINK_CACHE_KEY, each.fullShortUrl));
                bucket.set(each.originUrl, Duration.ofMillis(DEFAULT_CACHE_VALID_TIME));
                bloomFilter.add(each.fullShortUrl);
            }
        } finally {
            redissonClient.shutdown();
        }
    }

    private static void insertUser(Connection connection, String username, int index, Timestamp now) throws SQLException {
        executeUpdate(connection, """
                INSERT INTO t_user
                (username, password, real_name, phone, mail, deletion_time, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                username,
                "123456",
                "种子用户" + index,
                "1380000000" + index,
                username + "@example.com",
                0L,
                now,
                now);
    }

    private static void insertGroup(
            Connection connection, String gid, String username, String name, int sortOrder, Timestamp now)
            throws SQLException {
        executeUpdate(connection, """
                INSERT INTO t_group
                (gid, name, username, sort_order, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """,
                gid,
                name,
                username,
                sortOrder,
                now,
                now);
    }

    private static void insertGroupUnique(Connection connection, String gid) throws SQLException {
        executeUpdate(connection, "INSERT INTO t_group_unique (gid) VALUES (?)", gid);
    }

    private static void insertLink(
            Connection connection,
            String gid,
            String shortUri,
            String fullShortUrl,
            String originUrl,
            int totalPv,
            int totalUv,
            int totalUip,
            Timestamp now)
            throws SQLException {
        executeUpdate(connection, """
                INSERT INTO t_link
                (domain, short_uri, full_short_url, origin_url, click_num, gid, favicon, enable_status,
                 created_type, valid_date_type, valid_date, `describe`, total_pv, total_uv, total_uip,
                 create_time, update_time, del_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, 0, NULL, ?, ?, ?, ?, ?, ?, 0, 0)
                """,
                DOMAIN,
                shortUri,
                fullShortUrl,
                originUrl,
                totalPv,
                gid,
                "https://example.com/favicon.ico",
                "种子短链接-" + shortUri,
                totalPv,
                totalUv,
                totalUip,
                now,
                now);
    }

    private static void insertLinkGoto(Connection connection, String gid, String fullShortUrl) throws SQLException {
        executeUpdate(connection, "INSERT INTO t_link_goto (gid, full_short_url) VALUES (?, ?)", gid, fullShortUrl);
    }

    private static void seedStats(
            Connection connection,
            String fullShortUrl,
            LocalDate today,
            int totalPv,
            int totalUv,
            int totalUip,
            Timestamp now,
            SeedSummary summary)
            throws SQLException {
        int[] hours = {9, 12, 15, 20};
        for (int i = 0; i < hours.length; i++) {
            LocalDate date = today.minusDays(i % 3);
            int pv = Math.max(1, totalPv / hours.length + RANDOM.nextInt(20));
            int uv = Math.max(1, Math.min(pv, totalUv / hours.length + RANDOM.nextInt(10)));
            int uip = Math.max(1, Math.min(uv, totalUip / hours.length + RANDOM.nextInt(8)));
            insertAccessStats(connection, fullShortUrl, date, pv, uv, uip, hours[i], now);
            summary.accessStats++;
        }
        insertStatsToday(connection, fullShortUrl, today, totalPv / 3, totalUv / 3, totalUip / 3, now);
        insertDimensionalStats(connection, fullShortUrl, today, now);
        for (int i = 0; i < 8; i++) {
            insertAccessLog(connection, fullShortUrl, i, now);
            summary.accessLogs++;
        }
    }

    private static void insertAccessStats(
            Connection connection, String fullShortUrl, LocalDate date, int pv, int uv, int uip, int hour, Timestamp now)
            throws SQLException {
        executeUpdate(connection, """
                INSERT INTO t_link_access_stats
                (full_short_url, date, pv, uv, uip, hour, weekday, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                fullShortUrl,
                Date.valueOf(date),
                pv,
                uv,
                uip,
                hour,
                date.getDayOfWeek().getValue(),
                now,
                now);
    }

    private static void insertStatsToday(
            Connection connection, String fullShortUrl, LocalDate date, int pv, int uv, int uip, Timestamp now)
            throws SQLException {
        executeUpdate(connection, """
                INSERT INTO t_link_stats_today
                (full_short_url, date, today_pv, today_uv, today_uip, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """,
                fullShortUrl,
                Date.valueOf(date),
                pv,
                uv,
                uip,
                now,
                now);
    }

    private static void insertDimensionalStats(Connection connection, String fullShortUrl, LocalDate date, Timestamp now)
            throws SQLException {
        Date sqlDate = Date.valueOf(date);
        executeUpdate(connection, """
                INSERT INTO t_link_browser_stats
                (full_short_url, date, cnt, browser, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, 0)
                """,
                fullShortUrl, sqlDate, 35 + RANDOM.nextInt(60), "Chrome", now, now,
                fullShortUrl, sqlDate, 10 + RANDOM.nextInt(30), "Safari", now, now);
        executeUpdate(connection, """
                INSERT INTO t_link_device_stats
                (full_short_url, date, cnt, device, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, 0)
                """,
                fullShortUrl, sqlDate, 40 + RANDOM.nextInt(60), "PC", now, now,
                fullShortUrl, sqlDate, 20 + RANDOM.nextInt(40), "Mobile", now, now);
        executeUpdate(connection, """
                INSERT INTO t_link_locale_stats
                (full_short_url, date, cnt, province, city, adcode, country, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                fullShortUrl, sqlDate, 30 + RANDOM.nextInt(40), "浙江省", "杭州市", "330100", "CN", now, now,
                fullShortUrl, sqlDate, 20 + RANDOM.nextInt(30), "上海市", "上海市", "310000", "CN", now, now);
        executeUpdate(connection, """
                INSERT INTO t_link_network_stats
                (full_short_url, date, cnt, network, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, 0)
                """,
                fullShortUrl, sqlDate, 50 + RANDOM.nextInt(50), "WIFI", now, now,
                fullShortUrl, sqlDate, 15 + RANDOM.nextInt(35), "5G", now, now);
        executeUpdate(connection, """
                INSERT INTO t_link_os_stats
                (full_short_url, date, cnt, os, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, 0)
                """,
                fullShortUrl, sqlDate, 35 + RANDOM.nextInt(50), "macOS", now, now,
                fullShortUrl, sqlDate, 25 + RANDOM.nextInt(40), "iOS", now, now);
    }

    private static void insertAccessLog(Connection connection, String fullShortUrl, int index, Timestamp now)
            throws SQLException {
        String[] browsers = {"Chrome", "Safari", "Edge"};
        String[] oss = {"macOS", "iOS", "Windows"};
        String[] networks = {"WIFI", "5G", "4G"};
        String[] devices = {"PC", "Mobile"};
        executeUpdate(connection, """
                INSERT INTO t_link_access_logs
                (full_short_url, `user`, ip, browser, os, network, device, locale, create_time, update_time, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                fullShortUrl,
                "seed-uv-" + index,
                "192.168." + RANDOM.nextInt(20) + "." + (10 + RANDOM.nextInt(200)),
                browsers[index % browsers.length],
                oss[index % oss.length],
                networks[index % networks.length],
                devices[index % devices.length],
                index % 2 == 0 ? "浙江省杭州市" : "上海市",
                Timestamp.valueOf(LocalDateTime.of(LocalDate.now(), LocalTime.of(8 + index, RANDOM.nextInt(60)))),
                now);
    }

    private static void executeUpdate(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                preparedStatement.setObject(i + 1, args[i]);
            }
            preparedStatement.executeUpdate();
        }
    }

    private static String randomBase62(int length) {
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return builder.toString();
    }

    private static final class SeedSummary {
        private int users;
        private int groups;
        private int links;
        private int accessStats;
        private int accessLogs;
        private final List<SeedLink> seedLinks = new ArrayList<>();
    }

    private record SeedLink(String fullShortUrl, String originUrl) {
    }
}
