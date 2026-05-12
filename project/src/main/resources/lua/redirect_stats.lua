-- [新增] 短链接跳转统计 Lua 脚本
-- 将原来 2 次独立 Redis SADD 调用合并为 1 次 RTT，同时：
--   1. 改用 HyperLogLog（PFADD）替代 Set（SADD），内存占用降低约 100 倍
--   2. 在同一脚本内完成 TTL 设置，避免 UV/UIP key 无限增长
--
-- KEYS[1]: short-link:stats:uv:{fullShortUrl}
-- KEYS[2]: short-link:stats:uip:{fullShortUrl}
-- ARGV[1]: UV 值（用户 Cookie 中的 UUID）
-- ARGV[2]: 用户真实 IP 地址
-- 返回值: {uvAdded, uipAdded}，1 表示新增（首次），0 表示已存在

local uvAdded = redis.call('PFADD', KEYS[1], ARGV[1])
local uipAdded = redis.call('PFADD', KEYS[2], ARGV[2])

-- 仅在 key 无 TTL 时设置（TTL=-1 表示永久），避免每次请求重置过期时间
-- TTL=-2 表示 key 不存在（刚被 PFADD 创建的也算新 key，需要设置 TTL）
if redis.call('TTL', KEYS[1]) == -1 then
    redis.call('EXPIRE', KEYS[1], 2592000)  -- 30 天，与 UV Cookie 有效期对齐
end
if redis.call('TTL', KEYS[2]) == -1 then
    redis.call('EXPIRE', KEYS[2], 2592000)
end

return {uvAdded, uipAdded}
