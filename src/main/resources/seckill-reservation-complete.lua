local state = redis.call('hget', KEYS[1], 'state')
if state == 'RESERVED' or state == 'PROCESSING' then
    redis.call('hset', KEYS[1], 'state', 'COMPLETED')
    redis.call('zrem', 'seckill:reservation:pending', ARGV[1])
    redis.call('expire', KEYS[1], 86400)
    return 1
end
if state == 'COMPLETED' or state == 'CLOSED' then return 1 end
return 0
