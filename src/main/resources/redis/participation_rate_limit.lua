-- Both keys share {participation}: user limits apply across all activities.
-- Redis time avoids inter-instance clock skew. All checks precede both writes.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local activityWindow = tonumber(ARGV[2])
local userWindow = tonumber(ARGV[4])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - activityWindow)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - userWindow)
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then return 1 end
if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) then return 2 end
redis.call('ZADD', KEYS[1], now, ARGV[5])
redis.call('ZADD', KEYS[2], now, ARGV[5])
redis.call('PEXPIRE', KEYS[1], activityWindow)
redis.call('PEXPIRE', KEYS[2], userWindow)
return 0
