package ltd.sean.visit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ltd.sean.visit.entity.SpotContact;
import org.apache.ibatis.annotations.Mapper;

/**
 * 联系方式表 Mapper。
 *
 * <p>读写都很简单（按 spot_id 取、整组先删后插），不需要自定义 SQL：
 * 排序在 {@code sortOrder} 上，靠 QueryWrapper 的 orderByAsc 即可。
 */
@Mapper
public interface SpotContactMapper extends BaseMapper<SpotContact> {
}
