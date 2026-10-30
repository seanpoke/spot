package ltd.sean.visit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ltd.sean.visit.entity.Photo;
import org.apache.ibatis.annotations.Mapper;

/** 统一照片表。简单增删查走 MyBatis-Plus，复杂的按归属归组在 Service 里用 QueryWrapper。 */
@Mapper
public interface PhotoMapper extends BaseMapper<Photo> {
}
