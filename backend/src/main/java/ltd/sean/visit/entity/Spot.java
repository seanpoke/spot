package ltd.sean.visit.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打卡点，对应表 {@code spot}（定义见 db/schema.sql）。
 *
 * <p>字段顺序与表一致，方便对照。JSON 列（packages）在这里保留为原始字符串：
 * 后端不关心 JSON 内部结构，DTO 层用 Jackson 解析成具体对象再返回前端，
 * 这样加字段不用改 Entity。
 *
 * <p>坐标是 GCJ-02（高德坐标系），不要与 WGS-84 混用。
 *
 * <p><b>可空字段全部标了 {@code updateStrategy = ALWAYS}</b>：MyBatis-Plus 默认
 * 忽略 null 字段，导致「把联系方式清空」「把坐标去掉」这类操作在更新时静默失效 ——
 * 表单是整体提交（PUT），null 就是「清空」的明确意图，必须写进库。
 */
@Data
@TableName("spot")
public class Spot {

    /** UUID，由 MyBatis-Plus 生成（ASSIGN_UUID 不带横线，schema 是 CHAR(36)，两者兼容） */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** place=场所，person=人物 */
    private String kind;

    private String name;

    /** 头像相对路径 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatar;

    /** 封面相对路径 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cover;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal lat;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal lng;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String address;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String city;

    /** 省 / 直辖市 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String province;

    /** 区 / 县 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String district;

    /** 价目表 JSON 字符串：[{"name":"头部按摩","price":88}]（price 只允许正整数或 null） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String packages;

    /**
     * 价目里的最低 / 最高价（null = 没有填过价），保存时由 packages 算出来。
     *
     * <p>为什么冗余存这两列：价格在 packages 这个 JSON 里，MySQL 没法直接走索引做范围查询；
     * 列表页按「价格范围」筛时需要 `price_min <= 上限 AND price_max >= 下限` 这种判断，
     * 有了这两列就是一个普通数字比较，将来挪到服务端筛选也不用改 JSON。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer priceMin;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer priceMax;

    /** 段位 0 青铜 … 6 王者 */
    private Integer rankLevel;

    /** 1=手动定过段位，系统不再给建议值 */
    private Boolean rankManual;

    /** 所属场所 ID，人物挂靠用 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String parentSpotId;

    /** unvisited（未打卡）/ visited（已打卡）/ wishlist（想打卡）/ blacklist（避雷） */
    private String status;

    /**
     * 时间戳一律交给数据库：created_at 有 DEFAULT CURRENT_TIMESTAMP，
     * updated_at 有 ON UPDATE CURRENT_TIMESTAMP。
     *
     * <p>必须写 NEVER。updateById 会把这行读出来的旧 updated_at 原样写进 SET，
     * MySQL 认为「该列被显式赋值」，ON UPDATE 就不再触发 ——
     * 表现为改了名字 updated_at 一动不动。
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
