-- Fixed-window login throttle. KEYS[1]=bucket, ARGV[1]=limit, ARGV[2]=window ms.
local attempts = redis.call('INCR', KEYS[1])
if attempts == 1 then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
if attempts > tonumber(ARGV[1]) then
    local ttl = redis.call('PTTL', KEYS[1])
    if ttl < 0 then
        ttl = tonumber(ARGV[2])
        redis.call('PEXPIRE', KEYS[1], ttl)
    end
    if ttl < 1 then
        ttl = 1
    end
    return -ttl
end
return 1
