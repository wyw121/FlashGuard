local function key_type(key)
    return redis.call('TYPE', key)['ok']
end

local function canonical_integer(value)
    return value ~= false
        and string.match(value, '^%d+$') ~= nil
        and (value == '0' or string.sub(value, 1, 1) ~= '0')
end

local function state_error(message)
    return {'STATE_ERROR', message}
end

if ARGV[1] ~= 'RELEASE' then
    return state_error('invalid operation')
end

local activity_id = ARGV[2]
local request_id = ARGV[3]
if not canonical_integer(activity_id) or request_id == nil or request_id == false or request_id == '' then
    return state_error('invalid arguments')
end

local stock_type = key_type(KEYS[1])
local requests_type = key_type(KEYS[2])
local users_type = key_type(KEYS[3])
local pending_type = key_type(KEYS[4])
local released_type = key_type(KEYS[5])
if released_type ~= 'none' and released_type ~= 'hash' then
    return state_error('wrong released key type')
end
if redis.call('HEXISTS', KEYS[5], request_id) == 1 then
    if ARGV[4] ~= nil and ARGV[4] ~= '' and redis.call('HGET', KEYS[5], request_id) ~= ARGV[4] then
        return state_error('released reservation identity mismatch')
    end
    return {'ALREADY_RELEASED'}
end
if stock_type == 'none' then
    if requests_type == 'none' and users_type == 'none' and pending_type == 'none' then
        return {'NOT_READY'}
    end
    return state_error('reservation keys exist without stock metadata')
end
if stock_type ~= 'hash'
    or (requests_type ~= 'none' and requests_type ~= 'hash')
    or (users_type ~= 'none' and users_type ~= 'hash')
    or (pending_type ~= 'none' and pending_type ~= 'zset') then
    return state_error('wrong key type')
end

if redis.call('HGET', KEYS[1], 'schemaVersion') ~= '1'
    or redis.call('HGET', KEYS[1], 'initialized') ~= '1' then
    return state_error('invalid stock metadata')
end

local initial = redis.call('HGET', KEYS[1], 'initialStock')
local available = redis.call('HGET', KEYS[1], 'availableStock')
local start_at = redis.call('HGET', KEYS[1], 'startAt')
local end_at = redis.call('HGET', KEYS[1], 'endAt')
if not canonical_integer(initial) or not canonical_integer(available)
    or not canonical_integer(start_at) or not canonical_integer(end_at) then
    return state_error('invalid stock fields')
end
if tonumber(initial) > 2147483647 or tonumber(available) > tonumber(initial)
    or tonumber(start_at) >= tonumber(end_at) then
    return state_error('inconsistent stock fields')
end

local request_raw = redis.call('HGET', KEYS[2], request_id)
if request_raw == false then
    return {'NOT_FOUND'}
end

local ok, request = pcall(cjson.decode, request_raw)
if not ok or type(request) ~= 'table' then
    return state_error('invalid reservation payload')
end

if request.status ~= 'PENDING' or request.requestId ~= request_id
    or type(request.reservationId) ~= 'string'
    or type(request.userId) ~= 'string' or redis.call('HGET', KEYS[3], request.userId) ~= request_id then
    return state_error('orphaned user mapping')
end
if tostring(request.activityId) ~= activity_id then
    return state_error('activity mismatch')
end

if ARGV[4] ~= nil and ARGV[4] ~= '' then
    if request.reservationId ~= ARGV[4] or request.userId ~= ARGV[5] then
        return state_error('expected reservation identity mismatch')
    end
end

local next_available = tonumber(available) + 1
if next_available > tonumber(initial) then
    return state_error('stock overflow on release')
end
local used = tonumber(initial) - tonumber(available)
if redis.call('HLEN', KEYS[2]) ~= used or redis.call('HLEN', KEYS[3]) ~= used
    or redis.call('ZCARD', KEYS[4]) ~= used
    or redis.call('ZSCORE', KEYS[4], request_id) == false then
    return state_error('reservation cardinality mismatch')
end

redis.call('HSET', KEYS[5], request_id, request.reservationId)
redis.call('HDEL', KEYS[2], request_id)
redis.call('HDEL', KEYS[3], request.userId)
redis.call('ZREM', KEYS[4], request_id)
redis.call('HSET', KEYS[1], 'availableStock', tostring(next_available))
return {'RELEASED'}
