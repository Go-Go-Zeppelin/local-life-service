package com.hmdp;

import com.hmdp.consumer.CacheInvalidationConsumer;
import com.hmdp.service.cache.CacheInvalidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.concurrent.SettableListenableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheInvalidationTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final CacheInvalidationService service = new CacheInvalidationService(redis, kafka);
    private final String key = "cache:shop:1";

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void invalidatesOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        service.deleteAfterCommit(key);
        verifyNoInteractions(redis, kafka);
        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
        verify(redis).delete(key);
        verifyNoInteractions(kafka);
    }

    @Test
    void rollbackDoesNotInvalidateOrPublish() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        service.deleteAfterCommit(key);
        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(redis, kafka);
    }

    @Test
    void missingKeyAlreadySatisfiesInvalidation() {
        when(redis.delete(key)).thenReturn(false);
        service.deleteAfterCommit(key);
        verify(redis).delete(key);
        verifyNoInteractions(kafka);
    }

    @Test
    void redisFailurePublishesKey() {
        when(redis.delete(key)).thenThrow(new RedisConnectionFailureException("offline"));
        SettableListenableFuture<SendResult<String, Object>> sent = new SettableListenableFuture<>();
        sent.set(null);
        when(kafka.send(CacheInvalidationService.TOPIC, key, key)).thenReturn(sent);
        service.deleteAfterCommit(key);
        verify(kafka).send(CacheInvalidationService.TOPIC, key, key);
    }

    @Test
    void brokerFailureIsNotSilentlyIgnored() {
        when(redis.delete(key)).thenThrow(new RedisConnectionFailureException("offline"));
        SettableListenableFuture<SendResult<String, Object>> sent = new SettableListenableFuture<>();
        sent.setException(new IllegalStateException("broker offline"));
        when(kafka.send(CacheInvalidationService.TOPIC, key, key)).thenReturn(sent);
        assertThrows(IllegalStateException.class, () -> service.deleteAfterCommit(key));
    }

    @Test
    void consumerPropagatesFailureAndCanRetryIdempotently() {
        CacheInvalidationConsumer consumer = new CacheInvalidationConsumer(redis);
        when(redis.delete(key)).thenThrow(new RedisConnectionFailureException("offline"))
                .thenReturn(true).thenReturn(false);
        assertThrows(RedisConnectionFailureException.class, () -> consumer.delete(key));
        assertDoesNotThrow(() -> consumer.delete(key));
        assertDoesNotThrow(() -> consumer.delete(key));
        verify(redis, times(3)).delete(key);
    }
}
