-- 所有键使用相同 hash tag，兼容 Redis Cluster；Redis 时间避免实例时钟偏差。
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
local id = ARGV[1]
local lease = tonumber(ARGV[2])
local opened = tonumber(redis.call('HGET', KEYS[1], 'until') or '0')
if opened > now then return 503 end
local probe = opened > 0
if probe and redis.call('EXISTS', KEYS[2]) == 1 then return 503 end
for _, i in ipairs({3, 4, 9}) do
 redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now)
 local cap = tonumber(ARGV[i == 9 and 7 or i == 10 and 8 or i])
 if cap > 0 and redis.call('ZCARD', KEYS[i]) >= cap then return 429 end
end
for _, i in ipairs({5, 6, 10}) do
 redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now - 60000)
 local cap = tonumber(ARGV[i == 9 and 7 or i == 10 and 8 or i])
 if cap > 0 and redis.call('ZCARD', KEYS[i]) >= cap then return 429 end
end
if probe then redis.call('SET', KEYS[2], id, 'PX', lease) end
for _, i in ipairs({3, 4, 9}) do
 redis.call('ZADD', KEYS[i], now + lease, id)
 redis.call('PEXPIRE', KEYS[i], lease * 2)
end
for _, i in ipairs({5, 6, 10}) do
 redis.call('ZADD', KEYS[i], now, id)
 redis.call('PEXPIRE', KEYS[i], 120000)
end
return probe and 1 or 0
