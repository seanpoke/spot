package ltd.sean.visit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ltd.sean.visit.entity.Spot;

/**
 * 打卡点数据访问。
 *
 * <p>单表 CRUD 用 BaseMapper 自带方法即可；带 JOIN / GROUP BY 的查询写到
 * {@code resources/mapper/SpotMapper.xml}，方法签名放这里。不要在注解里塞长 SQL。
 */
public interface SpotMapper extends BaseMapper<Spot> {
}
