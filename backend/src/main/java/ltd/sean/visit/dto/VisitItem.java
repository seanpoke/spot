package ltd.sean.visit.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 一条到访记录（详情页「到访记录」区块与后续时间线共用）。
 *
 * <p>带上 {@code spotId} 是为了单条查询（编辑页）能直达所属打卡点，
 * 列表接口里它是冗余的，但比让前端为了拿它再请求一次划算。
 *
 * <p>{@code photos} 只在<b>单条详情</b>（GET /api/visits/{id}）里填充，
 * 列表 / 时间线接口一律不取（带宽小，详情才需要看大图）。{@code null} 表示没照片。
 *
 * @param scores 已解析成对象的分项评分，没评时是空数组而不是 null
 * @param photos 这条到访的照片（owner_type='visit'），详情接口才返回
 */
public record VisitItem(
        String id,
        String spotId,
        LocalDate visitedAt,
        Integer rating,
        List<ScoreItem> scores,
        BigDecimal cost,
        String companions,
        String note,
        List<ImageRef> photos
) {
}
