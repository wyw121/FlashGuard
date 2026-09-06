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

local function decode_record(raw)
    local ok, record = pcall(cjson.decode, raw)
    if not ok or type(record) ~= 'table' then
        return nil
    end
    if type(record.reservationId) ~= 'string'
        or type(record.activityId) ~= 'string'
        or type(record.userId) ~= 'string'
        or type(record.requestId) ~= 'string'
        or record.status ~= 'PENDING'
        or type(record.createdAt) ~= 'string'
        or type(record.expireAt) ~= 'string'
        or not canonical_integer(record.createdAt)
        or not canonical_integer(record.expireAt)
        or tonumber(record.createdAt) > tonumber(record.expireAt) then
        return nil
    end
    return record
end

local function validate_association(record)
    if record.activityId ~= ARGV[1] then
        return false, 'request activity mismatch'
    end
    if redis.call('HGET', KEYS[3], record.userId) ~= record.requestId then
        return false, 'request user mapping mismatch'
    end
    local pending_score = redis.call('ZSCORE', KEYS[4], record.requestId)
    if pending_score == false or tonumber(pending_score) ~= tonumber(record.expireAt) then
        return false, 'request pending mapping mismatch'
    end
    return true, nil
end

if ARGV[6] ~= 'RESERVE' and ARGV[6] ~= 'FIND' then
    return state_error('invalid operation')
end

local stock_type = key_type(KEYS[1])
local requests_type = key_type(KEYS[2])
local users_type = key_type(KEYS[3])
local pending_type = key_type(KEYS[4])
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
local initial_number = tonumber(initial)
local available_number = tonumber(available)
if initial_number > 2147483647 or available_number > initial_number
    or tonumber(start_at) >= tonumber(end_at) then
    return state_error('inconsistent stock fields')
end
local used = initial_number - available_number
if redis.call('HLEN', KEYS[2]) ~= used
    or redis.call('HLEN', KEYS[3]) ~= used
    or redis.call('ZCARD', KEYS[4]) ~= used then
    return state_error('reservation cardinality mismatch')
end

local existing_raw = redis.call('HGET', KEYS[2], ARGV[3])
if existing_raw ~= false then
    local existing = decode_record(existing_raw)
    if existing == nil or existing.requestId ~= ARGV[3] then
        return state_error('invalid request record')
    end
    local association_ok, association_error = validate_association(existing)
    if not association_ok then
        return state_error(association_error)
    end
    if ARGV[6] == 'FIND' then
        return {'FOUND', existing_raw}
    end
    if existing.userId ~= ARGV[2] then
        return {'REQUEST_CONFLICT'}
    end
    return {'REPLAY', existing_raw}
end

if redis.call('ZSCORE', KEYS[4], ARGV[3]) ~= false then
    return state_error('orphaned pending request')
end

if ARGV[6] == 'FIND' then
    return {'NOT_FOUND'}
end

local user_request = redis.call('HGET', KEYS[3], ARGV[2])
if user_request ~= false then
    local user_raw = redis.call('HGET', KEYS[2], user_request)
    if user_raw == false then
        return state_error('orphaned user mapping')
    end
    local user_record = decode_record(user_raw)
    if user_record == nil or user_record.userId ~= ARGV[2]
        or user_record.requestId ~= user_request then
        return state_error('invalid user request record')
    end
    local association_ok, association_error = validate_association(user_record)
    if not association_ok then
        return state_error(association_error)
    end
    return {'DUPLICATE'}
end

local redis_time = redis.call('TIME')
local now = tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)
if now < tonumber(start_at) then
    return {'NOT_STARTED'}
end
if now >= tonumber(end_at) then
    return {'ENDED'}
end
if available_number == 0 then
    return {'SOLD_OUT'}
end
if not canonical_integer(ARGV[5]) or tonumber(ARGV[5]) <= 0 then
    return state_error('invalid reservation timeout')
end

local expire_at = now + tonumber(ARGV[5])
if expire_at >= 9007199254740992 then
    return state_error('reservation expiry exceeds exact numeric range')
end
local now_string = string.format('%.0f', now)
local expire_string = string.format('%.0f', expire_at)
local record = cjson.encode({
    reservationId = ARGV[4],
    activityId = ARGV[1],
    userId = ARGV[2],
    requestId = ARGV[3],
    status = 'PENDING',
    createdAt = now_string,
    expireAt = expire_string
})

redis.call('HSET', KEYS[2], ARGV[3], record)
redis.call('HSET', KEYS[3], ARGV[2], ARGV[3])
redis.call('ZADD', KEYS[4], expire_at, ARGV[3])
redis.call('HSET', KEYS[1], 'availableStock', tostring(available_number - 1))
return {'SUCCESS', record}
