package ltd.sean.visit.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标签字典，对应表 {@code tag}。名称全库唯一（uk_tag_name），
 * 同名标签复用同一行，不要在打卡点上存标签字符串副本。
 */
@Data
@TableName("tag")
public class Tag {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String name;

    /** 标签颜色 #RRGGBB，可空：没选过颜色的老标签为空，前端回退到默认灰底 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String color;

    /** 全库默认次序，小在前；与 spot_tag.sort_order（某点上的次序）是两回事 */
    private Integer sortOrder;

    private LocalDateTime createdAt;
}
