-- Delete only our own lock; never remove a lock acquired after this handle's TTL expired.
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
