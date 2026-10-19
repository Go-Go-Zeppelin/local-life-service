-- 发送回调可能晚于消费完成；原子检查，避免把终态请求重新加入待投递队列。
if redis.call('hget', KEYS[1], 'state') == 'RESERVED' then
    redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])
    return 1
end
return 0
