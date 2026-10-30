package ltd.sean.visit.dto;

import lombok.Data;

/**
 * Mapper 查询结果行：标签 + 被多少个打卡点使用。
 *
 * <p>用于标签库排序与「清理没人用的标签」。不用 record 的原因同 {@link SpotTagRow}。
 */
@Data
public class TagUsageRow {

    private String id;

    private String name;

    private String color;

    /** 分类 code，编辑页按它分组展示 */
    private String category;

    private Integer sortOrder;

    /** 被引用的次数，0 表示闲置 */
    private Long useCount;
}
