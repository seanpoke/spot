package ltd.sean.visit.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * Mapper 查询结果行：去得最多的打卡点（统计页「去得最多」）。
 *
 * <p>{@code avatar} 是库里的相对路径，Service 会换成可访问地址后再出参（AGENTS.md 第 1 条）。
 */
@Data
public class TopSpotRow {

    private String id;

    private String name;

    private String avatar;

    private Long visitCount;

    private LocalDate lastVisitAt;
}
