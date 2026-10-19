-- 只允许与预占记录一致的消息领取任务。
if redis.call('hget', KEYS[1], 'voucherId') ~= ARGV[2]
        or redis.call('hget', KEYS[1], 'userId') ~= ARGV[3] then return 0 end
local state = redis.call('hget', KEYS[1], 'state')
if state == 'RESERVED' then
    redis.call('hset', KEYS[1], 'state', 'PROCESSING')
    redis.call('zrem', 'seckill:reservation:pending', ARGV[1])
    return 1
end
if state == 'PROCESSING' then return 2 end
if state == 'COMPLETED' or state == 'CLOSED' then return 3 end
if state == 'RELEASED' then return 4 end
return 0
