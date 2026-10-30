package ltd.sean.visit.dto;

import lombok.Data;

/**
 * Mapper 查询结果行：一条「某打卡点挂了某标签」的记录。
 *
 * <p>只在这里出现 —— Service 会按 spotId 归组并转成 {@link TagBrief} 再返回前端。
 * 之所以不用 record：MyBatis 对 record 的自动映射依赖编译参数，换成普通 setter 更稳。
 */
@Data
public class SpotTagRow {

    private String spotId;

    private String tagId;

    private String tagName;

    private String tagColor;
}
