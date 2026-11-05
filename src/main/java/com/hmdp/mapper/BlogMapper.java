package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.dto.blog.BlogDraftCommand;
import com.hmdp.entity.Blog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 笔记查询与状态转换的条件更新。 */
public interface BlogMapper extends BaseMapper<Blog> {

    @Select("SELECT * FROM tb_blog WHERE id = #{id} FOR UPDATE")
    Blog selectForUpdate(@Param("id") Long id);

    @Update("UPDATE tb_blog SET shop_id = #{content.shopId}, title = #{content.title}, " +
            "images = #{content.images}, content = #{content.content}, status = 0, version = version + 1 " +
            "WHERE id = #{blog.id} AND user_id = #{blog.userId} AND status = #{blog.status} " +
            "AND version = #{blog.version} AND review_round = #{blog.reviewRound}")
    int editIfUnchanged(@Param("blog") Blog blog, @Param("content") BlogDraftCommand content);

    @Update("UPDATE tb_blog SET status = 1, version = version + 1, review_round = review_round + 1, " +
            "reviewer_id = NULL, review_time = NULL, reject_reason = NULL " +
            "WHERE id = #{blog.id} AND user_id = #{blog.userId} AND status = #{blog.status} " +
            "AND version = #{blog.version} AND review_round = #{blog.reviewRound}")
    int submitIfUnchanged(@Param("blog") Blog blog);

    @Update("UPDATE tb_blog SET status = 2, version = version + 1, reviewer_id = #{reviewerId}, " +
            "review_time = #{time}, publish_time = #{time}, reject_reason = NULL " +
            "WHERE id = #{blog.id} AND status = 1 AND version = #{blog.version} " +
            "AND review_round = #{blog.reviewRound}")
    int approveIfUnchanged(@Param("blog") Blog blog, @Param("reviewerId") Long reviewerId,
                           @Param("time") LocalDateTime time);

    @Update("UPDATE tb_blog SET status = 3, version = version + 1, reviewer_id = #{reviewerId}, " +
            "review_time = #{time}, reject_reason = #{reason} " +
            "WHERE id = #{blog.id} AND status = 1 AND version = #{blog.version} " +
            "AND review_round = #{blog.reviewRound}")
    int rejectIfUnchanged(@Param("blog") Blog blog, @Param("reviewerId") Long reviewerId,
                          @Param("time") LocalDateTime time, @Param("reason") String reason);
}
