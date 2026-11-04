package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @since 2021-12-22
 */
public interface BlogMapper extends BaseMapper<Blog> {

    @Select("SELECT * FROM tb_blog WHERE id = #{id} FOR UPDATE")
    Blog selectForUpdate(@Param("id") Long id);

}
