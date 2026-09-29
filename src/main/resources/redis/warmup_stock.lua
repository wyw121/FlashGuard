local function key_type(key)
    return redis.call('TYPE', key)['ok']
end

local function canonical_integer(value)
    return value ~= false
        and string.match(value, '^%d+$') ~= nil
        and (value == '0' or string.sub(value, 1, 1) ~= '0')
end

local stock_type = key_type(KEYS[1])
local requests_type = key_type(KEYS[2])
local users_type = key_type(KEYS[3])
local pending_type = key_type(KEYS[4])

if stock_type == 'none' then
    if requests_type ~= 'none' or users_type ~= 'none' or pending_type ~= 'none' then
        return {'STATE_ERROR', 'reservation keys exist without stock metadata'}
    end
    if ARGV[1] ~= '1' or not canonical_integer(ARGV[2])
        or not canonical_integer(ARGV[3]) or not canonical_integer(ARGV[4]) then
        return {'STATE_ERROR', 'invalid warmup arguments'}
    end
    local stock = tonumber(ARGV[2])
    local start_at = tonumber(ARGV[3])
    local end_at = tonumber(ARGV[4])
    if stock > 2147483647 or start_at >= end_at then
        return {'STATE_ERROR', 'invalid stock or activity window'}
    end

    redis.call('HSET', KEYS[1],
        'schemaVersion', ARGV[1],
        'initialized', '1',
        'initialStock', ARGV[2],
        'availableStock', ARGV[2],
        'startAt', ARGV[3],
        'endAt', ARGV[4])
    return {'INITIALIZED'}
end

if stock_type ~= 'hash'
    or (requests_type ~= 'none' and requests_type ~= 'hash')
    or (users_type ~= 'none' and users_type ~= 'hash')
    or (pending_type ~= 'none' and pending_type ~= 'zset') then
    return {'STATE_ERROR', 'wrong key type'}
end

local schema = redis.call('HGET', KEYS[1], 'schemaVersion')
local initialized = redis.call('HGET', KEYS[1], 'initialized')
local initial = redis.call('HGET', KEYS[1], 'initialStock')
local available = redis.call('HGET', KEYS[1], 'availableStock')
local start_at = redis.call('HGET', KEYS[1], 'startAt')
local end_at = redis.call('HGET', KEYS[1], 'endAt')
if schema ~= '1' or initialized ~= '1' then
    return {'STATE_ERROR', 'invalid stock metadata'}
end
if not canonical_integer(initial) or not canonical_integer(available)
    or not canonical_integer(start_at) or not canonical_integer(end_at) then
    return {'STATE_ERROR', 'invalid stock fields'}
end
local initial_number = tonumber(initial)
local available_number = tonumber(available)
if initial_number > 2147483647 or available_number > initial_number
    or tonumber(start_at) >= tonumber(end_at) then
    return {'STATE_ERROR', 'inconsistent stock fields'}
end
local used = initial_number - available_number
if redis.call('HLEN', KEYS[2]) ~= used
    or redis.call('HLEN', KEYS[3]) ~= used
    or redis.call('ZCARD', KEYS[4]) ~= used then
    return {'STATE_ERROR', 'reservation cardinality mismatch'}
end

return {'ALREADY_INITIALIZED'}
