package ltd.sean.visit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 基础配置（业务字典），对应表 {@code base_config}。
 *
 * <p>一个 {@code code} 是一组配置，比如 {@code priceItem} 是消费项目的预设：
 * key = 项目名（1P / 2P / 夜），value = 默认价格（0 表示不预填），desc = 说明。
 * 加一类配置只是插几行数据，不用动代码。
 *
 * <p><b>三个列名都是 MySQL 关键字</b>（{@code key} / {@code value} / {@code desc}），
 * 必须用 {@code @TableField("`key`")} 这种带反引号的写法，否则生成的 SQL 会语法错误；
 * 手写 QueryWrapper 时字段名也要自己带反引号。
 */
@Data
@TableName("base_config")
public class BaseConfig {

    /** 自增主键；同一 code 下 id 小的排前面，就是展示顺序 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编码，如 priceItem */
    private String code;

    /** 配置项 key，如 1P；同一 code 下唯一 */
    @TableField("`key`")
    private String key;

    /** 配置值，统一按字符串存，数字/布尔由业务解释 */
    @TableField("`value`")
    private String value;

    /** 说明，只给人看 */
    @TableField("`desc`")
    private String desc;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
