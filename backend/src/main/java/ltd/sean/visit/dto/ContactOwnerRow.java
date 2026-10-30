package ltd.sean.visit.dto;

import lombok.Data;

/**
 * Mapper 查询结果行：一条联系方式 + 它属于哪个打卡点（名字）。
 *
 * <p>查重的候选集用它：一次把同类型的全库联系方式捞回来，在 Service 里按归一化后的值配对。
 * 不用 record 的原因同 {@link SpotTagRow}（MyBatis 自动映射走 setter 更稳）。
 */
@Data
public class ContactOwnerRow {

    private String spotId;

    private String spotName;

    private String type;

    private String value;
}
