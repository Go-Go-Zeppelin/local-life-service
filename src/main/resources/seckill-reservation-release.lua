-- ARGV: allowProcessing, orderId, failureReason, keepPurchaseMarker
local state = redis.call('hget', KEYS[1], 'state')
if state == 'RELEASED' then return 2 end
if state == 'COMPLETED' or state == 'CLOSED' then return 3 end
if state ~= 'RESERVED' and not (state == 'PROCESSING' and ARGV[1] == '1') then
    return 0
end
local voucherId = redis.call('hget', KEYS[1], 'voucherId')
local userId = redis.call('hget', KEYS[1], 'userId')
if not voucherId or not userId then return 0 end
redis.call('incrby', KEYS[2] .. voucherId, 1)
-- 同一用户已有另一笔数据库订单时保留购买标记，避免反复预占后又被唯一索引拒绝。
if ARGV[4] ~= '1' then redis.call('srem', 'seckill:order:' .. voucherId, userId) end
redis.call('hset', KEYS[1], 'state', 'RELEASED', 'failureReason', ARGV[3])
redis.call('zrem', 'seckill:reservation:pending', ARGV[2])
redis.call('expire', KEYS[1], 86400)
return 1
