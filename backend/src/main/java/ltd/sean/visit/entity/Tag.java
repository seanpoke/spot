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

    /**
     * 分类 code，对应 {@code tag_category.code}（种子为 {@code person / place /
     * service / vibe / other}，之后可由用户在标签管理页自己加）。
     *
     * <p>列表接口只回 code，显示名由前端拿分类表换算；落库前空值统一成
     * {@code other}，且会校验分类是否存在（{@link ltd.sean.visit.service.TagService}）。
     */
    private String category;

    /**
     * 优先级：1 开始连续，小的排前面。
     *
     * <p>这是标签顺序的<b>唯一来源</b> —— 详情页、列表页、编辑页平铺都按它排。
     * 「我的 → 编辑标签」里长按拖拽后全量重排（{@code PUT /api/tags/sort}）。
     */
    private Integer sortOrder;

    private LocalDateTime createdAt;
}
