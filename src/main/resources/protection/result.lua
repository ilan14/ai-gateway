local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
local outcome = tonumber(ARGV[2])
local window = tonumber(ARGV[3])
local cooldown = tonumber(ARGV[7])
local transition = 0
-- 半开结果只允许当前租约所有者更新，忽略过期请求的迟到结果。
if ARGV[8] == '1' then
 if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
 if outcome == 0 then redis.call('DEL', KEYS[1], KEYS[7], KEYS[8]); transition = 2 end
elseif tonumber(redis.call('HGET', KEYS[1], 'until') or '0') > 0 then return 0 end
if outcome < 0 then return 0 end
redis.call('ZREMRANGEBYSCORE', KEYS[7], '-inf', now - window)
redis.call('ZREMRANGEBYSCORE', KEYS[8], '-inf', now - window)
redis.call('ZADD', KEYS[7], now, ARGV[1])
local consecutive = 0
if outcome > 0 then
 redis.call('ZADD', KEYS[8], now, ARGV[1])
 if tonumber(redis.call('HGET', KEYS[1], 'last_failure') or '0') <= now - window then redis.call('HSET', KEYS[1], 'consecutive', 0) end
 redis.call('HSET', KEYS[1], 'last_failure', now)
 consecutive = redis.call('HINCRBY', KEYS[1], 'consecutive', 1)
else redis.call('HSET', KEYS[1], 'consecutive', 0) end
local total = redis.call('ZCARD', KEYS[7])
local failures = redis.call('ZCARD', KEYS[8])
if outcome == 2 or (ARGV[8] == '1' and outcome > 0)
 or (consecutive >= tonumber(ARGV[6]) and outcome > 0)
 or (total >= tonumber(ARGV[4]) and failures * 100 >= total * tonumber(ARGV[5])) then
 transition = 1
 redis.call('HSET', KEYS[1], 'until', now + cooldown, 'reason', outcome == 2 and 'rate_limit' or 'failure', 'changed', now)
end
redis.call('PEXPIRE', KEYS[7], window * 2)
redis.call('PEXPIRE', KEYS[8], window * 2)
-- 状态不能提前过期，否则会跳过半开探测。
return transition
