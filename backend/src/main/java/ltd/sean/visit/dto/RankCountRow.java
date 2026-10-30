package ltd.sean.visit.dto;

import lombok.Data;

/**
 * Mapper 查询结果行：某个段位下有几个打卡点（段位分布图用）。
 *
 * <p>只会返回库里真实存在的段位；Service 负责补齐 0~6 七条，
 * 否则前端要自己补空档，容易漏掉整段。
 */
@Data
public class RankCountRow {

    private Integer rankLevel;

    private Long spotCount;
}
