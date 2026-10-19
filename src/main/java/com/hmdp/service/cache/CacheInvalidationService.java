package com.hmdp.service.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CacheInvalidationService {
    public static final String TOPIC = "cache-delete-retry";
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, Object> kafka;

    public CacheInvalidationService(StringRedisTemplate redis, KafkaTemplate<String, Object> kafka) {
        this.redis = redis;
        this.kafka = kafka;
    }

    public void deleteAfterCommit(String key) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteOrEnqueue(key);
                }
            });
        } else {
            deleteOrEnqueue(key);
        }
    }

    private void deleteOrEnqueue(String key) {
        try {
            // false 表示 key 已不存在，同样满足失效目标。
            redis.delete(key);
        } catch (RuntimeException deleteFailure) {
            log.warn("缓存删除失败，发送补偿消息: key={}", key, deleteFailure);
            try {
                // 等待 broker 确认，不能将异步发送调用成功当作消息已送达。
                kafka.send(TOPIC, key, key).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw deliveryFailure(key, e);
            } catch (Exception e) {
                throw deliveryFailure(key, e);
            }
        }
    }

    private IllegalStateException deliveryFailure(String key, Exception cause) {
        log.error("数据库已提交，但缓存删除补偿消息未确认送达，需人工补偿: key={}", key, cause);
        return new IllegalStateException("数据库已更新，但缓存失效补偿未确认送达", cause);
    }
}
