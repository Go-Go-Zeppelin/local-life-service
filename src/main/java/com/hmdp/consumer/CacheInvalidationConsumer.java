package com.hmdp.consumer;

import com.hmdp.service.cache.CacheInvalidationService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CacheInvalidationConsumer {
    private final StringRedisTemplate redis;

    public CacheInvalidationConsumer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @KafkaListener(topics = CacheInvalidationService.TOPIC,
            groupId = "${spring.kafka.consumer.group-id}-cache-delete",
            containerFactory = "cacheDeleteKafkaListenerContainerFactory")
    public void delete(String key) {
        // 异常交给容器重试；正常返回后容器才提交 offset。重复删除是幂等的。
        redis.delete(key);
    }
}
