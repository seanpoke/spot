package ltd.sean.visit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打卡点与标签的关系，对应表 {@code spot_tag}（联合主键 spot_id + tag_id）。
 *
 * <p><b>注意</b>：MyBatis-Plus 不支持联合主键，这里把 {@code spotId} 标成 INPUT 主键，
 * 只用于按主键定位。涉及按 (spot_id, tag_id) 的更新/删除，必须用
 * {@code QueryWrapper} 显式带上两个条件，不要用 {@code updateById} / {@code deleteById}。
 *
 * <p>保存标签的推荐做法（与 schema.sql 末尾示例一致）：一个事务里先
 * {@code delete by spot_id} 再批量插入，整组覆盖。
 */
@Data
@TableName("spot_tag")
public class SpotTag {

    @TableId(value = "spot_id", type = IdType.INPUT)
    private String spotId;

    private String tagId;

    /** 该标签在此打卡点上的展示顺序，小在前（决定列表截断时谁先露脸） */
    private Integer sortOrder;

    private LocalDateTime createdAt;
}
