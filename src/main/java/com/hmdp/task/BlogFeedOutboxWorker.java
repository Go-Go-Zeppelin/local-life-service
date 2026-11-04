package com.hmdp.task;

import com.hmdp.entity.BlogFeedOutbox;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.BlogFeedOutboxMapper;
import com.hmdp.mapper.FollowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/** 每次领取均有独立凭证，Redis 写入固定 score，任务失败后允许安全重放。 */
@Slf4j
@Component
public class BlogFeedOutboxWorker {
    private static final int LEASE_SECONDS = 60;
    private static final int FOLLOWER_BATCH = 200;
    @Resource private BlogFeedOutboxMapper outboxMapper;
    @Resource private FollowMapper followMapper;
    @Resource private StringRedisTemplate redis;

    @Value("${hmdp.blog.feed-worker-enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${hmdp.blog.feed-interval-ms:1000}",
            initialDelayString = "${hmdp.blog.feed-initial-delay-ms:10000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            processDue(20);
        } catch (Exception e) {
            log.error("笔记 Feed 发布任务扫描失败", e);
        }
    }

    public int processDue(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("任务批次须为 1 至 500");
        }
        int completed = 0;
        for (BlogFeedOutbox candidate : outboxMapper.selectDue(limit)) {
            String token = UUID.randomUUID().toString();
            if (outboxMapper.claim(candidate.getId(), token, LEASE_SECONDS) != 1) {
                continue;
            }
            try {
                BlogFeedOutbox event = outboxMapper.selectById(candidate.getId());
                if (deliver(event, token) && outboxMapper.complete(event.getId(), token) == 1) {
                    completed++;
                }
            } catch (Exception e) {
                String error = e.toString();
                int delay = Math.min(300, 1 << Math.min(candidate.getAttempts() + 1, 8));
                outboxMapper.retry(candidate.getId(), token, delay, error.substring(0, Math.min(error.length(), 500)));
                log.error("笔记 Feed 推送失败，将重试: eventId={}, blogId={}", candidate.getId(), candidate.getBlogId(), e);
            }
        }
        return completed;
    }

    private boolean deliver(BlogFeedOutbox event, String token) {
        // 每次尝试以当前关注关系为准，并固定 ID 上界，避免持续新增粉丝令扫描无法结束。
        long upperBound = followMapper.selectFollowerUpperBound(event.getAuthorId());
        long afterId = 0;
        while (true) {
            if (outboxMapper.renew(event.getId(), token, LEASE_SECONDS) != 1) {
                return false;
            }
            long nextRenewal = System.nanoTime() + TimeUnit.SECONDS.toNanos(LEASE_SECONDS / 3);
            List<Follow> followers = followMapper.selectFollowerBatch(event.getAuthorId(), afterId, upperBound, FOLLOWER_BATCH);
            if (followers.isEmpty()) {
                return true;
            }
            for (Follow follower : followers) {
                if (System.nanoTime() >= nextRenewal) {
                    if (outboxMapper.renew(event.getId(), token, LEASE_SECONDS) != 1) {
                        return false;
                    }
                    nextRenewal = System.nanoTime() + TimeUnit.SECONDS.toNanos(LEASE_SECONDS / 3);
                }
                redis.opsForZSet().add(FEED_KEY + follower.getUserId(),
                        event.getBlogId().toString(), event.getPublishTimeMillis().doubleValue());
                afterId = follower.getId();
            }
        }
    }
}
