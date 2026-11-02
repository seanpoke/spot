package ltd.sean.visit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import ltd.sean.visit.common.BizException;
import ltd.sean.visit.dto.ConfigItem;
import ltd.sean.visit.entity.BaseConfig;
import ltd.sean.visit.mapper.BaseConfigMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基础配置（业务字典）。
 *
 * <p>只读接口：配置由 SQL 维护（{@code db/schema.sql} / {@code db/migrations/}），
 * 应用里没有增删改入口 —— 这些东西是「代码的一部分」，不该在界面上被随手改掉。
 *
 * <p>返回顺序按自增 id 升序，等价于「插入顺序」，所以想调整前端展示顺序，
 * 直接改库里的 id 或重新插入即可，不需要额外的 sort 列。
 */
@Service
public class BaseConfigService {

    /** 消费项目的预设：key 是项目名，value 是默认价格（0 表示不预填） */
    public static final String PRICE_ITEM = "priceItem";

    /** code 只允许 ASCII 标识符：它是内部键，不是给人看的文案 */
    private static final String CODE_PATTERN = "^[A-Za-z][A-Za-z0-9_]{0,31}$";

    private final BaseConfigMapper configMapper;

    public BaseConfigService(BaseConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    public List<ConfigItem> listByCode(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches(CODE_PATTERN)) {
            throw new BizException(400, "配置编码不合法");
        }
        return configMapper.selectList(new QueryWrapper<BaseConfig>()
                        .eq("code", code)
                        .orderByAsc("id"))
                .stream()
                .map(row -> new ConfigItem(row.getKey(), row.getValue(), row.getDesc()))
                .toList();
    }
}
