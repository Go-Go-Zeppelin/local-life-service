-- 数据库已确认关闭订单后调用。标记不设 TTL，保证数据库确认失败后长时间重试也不会重复回补。
if redis.call('exists', KEYS[3]) == 1 then return 2 end
local state = redis.call('hget', KEYS[1], 'state')
if state == 'RELEASED' then return 0 end
-- 兼容旧版本已回补过的 CLOSED 记录。
if state ~= 'CLOSED' then redis.call('incrby', KEYS[2], 1) end
redis.call('hset', KEYS[1], 'state', 'CLOSED', 'voucherId', ARGV[1], 'userId', ARGV[2])
redis.call('sadd', 'seckill:order:' .. ARGV[1], ARGV[2])
redis.call('expire', KEYS[1], 86400)
redis.call('set', KEYS[3], '1')
redis.call('zrem', 'seckill:reservation:pending', ARGV[3])
return 1
