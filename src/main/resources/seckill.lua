-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]
-- 1.4.当前毫秒时间，用作可靠待投递队列的调度分数
local nowMillis = ARGV[4]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId
-- 2.3.预占记录，避免 Redis 已扣库存但消息失败时无法定位订单
local reservationKey = 'seckill:reservation:' .. orderId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
local stock = tonumber(redis.call('get', stockKey))
if(not stock or stock <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.2.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复下单，返回2
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6.记录预占状态。Kafka 消息成功落库后会改为 COMPLETED。
redis.call('hset', reservationKey,
        'voucherId', voucherId,
        'userId', userId,
        'state', 'RESERVED',
        'createdAt', nowMillis)
-- 与库存预扣处于同一个 Lua 原子操作；应用在发送 Kafka 前崩溃也不会丢失建单任务。
redis.call('zadd', 'seckill:reservation:pending', nowMillis, orderId)
return 0
