package com.hmdp.mapper;

import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @since 2021-12-22
 */
public interface FollowMapper extends BaseMapper<Follow> {

    @Select("SELECT COALESCE(MAX(id), 0) FROM tb_follow WHERE follow_user_id = #{authorId}")
    long selectFollowerUpperBound(@Param("authorId") Long authorId);

    @Select("SELECT * FROM tb_follow WHERE follow_user_id = #{authorId} AND id > #{afterId} " +
            "AND id <= #{upperBound} ORDER BY id LIMIT #{limit}")
    List<Follow> selectFollowerBatch(@Param("authorId") Long authorId, @Param("afterId") long afterId,
                                    @Param("upperBound") long upperBound, @Param("limit") int limit);

}
