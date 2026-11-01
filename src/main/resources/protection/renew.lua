local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
for _, i in ipairs({3, 4, 9}) do
 local expiry = tonumber(redis.call('ZSCORE', KEYS[i], ARGV[1]) or '0')
 if expiry <= now then return 0 end
end
for _, i in ipairs({3, 4, 9}) do
 redis.call('ZADD', KEYS[i], 'XX', now + tonumber(ARGV[2]), ARGV[1])
 redis.call('PEXPIRE', KEYS[i], tonumber(ARGV[2]) * 2)
end
if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('PEXPIRE', KEYS[2], ARGV[2]) end
return 1
