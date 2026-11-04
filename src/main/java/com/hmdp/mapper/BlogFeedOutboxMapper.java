package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.BlogFeedOutbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

public interface BlogFeedOutboxMapper extends BaseMapper<BlogFeedOutbox> {
    @Select("SELECT * FROM tb_blog_feed_outbox WHERE " +
            "(status = 0 AND next_retry_time <= NOW(3)) OR (status = 1 AND lease_until <= NOW(3)) " +
            "ORDER BY id LIMIT #{limit}")
    List<BlogFeedOutbox> selectDue(@Param("limit") int limit);

    @Update("UPDATE tb_blog_feed_outbox SET status = 1, claim_token = #{token}, " +
            "lease_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW(3)), attempts = attempts + 1 " +
            "WHERE id = #{id} AND ((status = 0 AND next_retry_time <= NOW(3)) " +
            "OR (status = 1 AND lease_until <= NOW(3)))")
    int claim(@Param("id") Long id, @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE tb_blog_feed_outbox SET lease_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW(3)) " +
            "WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_until > NOW(3)")
    int renew(@Param("id") Long id, @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE tb_blog_feed_outbox SET status = 2, claim_token = NULL, lease_until = NULL, last_error = NULL " +
            "WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_until > NOW(3)")
    int complete(@Param("id") Long id, @Param("token") String token);

    @Update("UPDATE tb_blog_feed_outbox SET status = 0, claim_token = NULL, lease_until = NULL, " +
            "next_retry_time = TIMESTAMPADD(SECOND, #{delaySeconds}, NOW(3)), last_error = #{error} " +
            "WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_until > NOW(3)")
    int retry(@Param("id") Long id, @Param("token") String token,
              @Param("delaySeconds") int delaySeconds, @Param("error") String error);
}
