-- Atomic owner token + TTL acquisition. KEYS[1]=lock, ARGV[1]=token, ARGV[2]=ttl ms.
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
    return 1
end
return 0
