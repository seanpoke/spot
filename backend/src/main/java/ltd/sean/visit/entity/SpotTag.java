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
 *
 * <p><b>关系表不再带自己的排序</b>：标签在哪个点上都按 {@code tag.sort_order}
 * （全库优先级，在「我的 → 编辑标签」里长按拖拽维护）展示。以前这里有一个
 * {@code sort_order} 表示「这个标签在这个点上的次序」，等于同一件事有两种口径，
 * 结果就是同一批标签在详情页、列表页、编辑页里顺序不一致 —— 现在统一以 tag 表为准。
 */
@Data
@TableName("spot_tag")
public class SpotTag {

    @TableId(value = "spot_id", type = IdType.INPUT)
    private String spotId;

    private String tagId;

    private LocalDateTime createdAt;
}
