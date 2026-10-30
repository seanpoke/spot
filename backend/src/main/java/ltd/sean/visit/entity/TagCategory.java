package ltd.sean.visit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标签分类（类型）字典，对应表 {@code tag_category}。
 *
 * <p>为什么单独建表而不是写死在前端：分类要能在「我的 → 编辑标签」里新增和删除，
 * 写死的枚举做不到。{@code code} 是主键，{@code tag.category} 存的就是它；
 * {@code label} 只是给人看的名字，可以随时改（改 label 不影响任何标签）。
 *
 * <p>{@code other}（其他）是兜底分类，删不掉：标签的分类为空或指向已删分类时
 * 一律按它展示，所以它必须一直存在。
 */
@Data
@TableName("tag_category")
public class TagCategory {

    /** 分类 code：小写字母开头 + 字母数字下划线，最长 16；新建时后端生成，不暴露给用户 */
    @TableId(type = IdType.INPUT)
    private String code;

    /** 显示名，如「人物」「场所」，全库唯一 */
    private String label;

    /** 顺序：1 起连续，小的排前面。决定编辑页分组顺序与标签管理页的类型排布 */
    private Integer sortOrder;

    private LocalDateTime createdAt;
}
