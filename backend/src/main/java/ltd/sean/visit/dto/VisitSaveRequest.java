package ltd.sean.visit.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 新建 / 更新一次到访的入参。
 *
 * <p>与打卡点表单一样是<b>整体提交</b>：想清掉花费就传 null，不是「没传即不改」。
 *
 * <p>{@code photos} 接收这次到访的照片（最多 9 张，{@code ImageRef} 列表），
 * 落库时整体覆盖 photo 表里 owner_type='visit' 的对应行。传空数组即清空。
 *
 * @param visitedAt 到访日期，只到天；必填，前端默认今天
 * @param rating    整体星级 1~5，段位建议与详情页均分都从它算
 * @param scores    分项评分，null 或空数组表示没评
 * @param cost      本次花费（元），可空
 * @param companions 同行人，自由文本
 * @param note      一句话感受
 * @param photos    这条到访的照片（存 photo 表，owner_type='visit'）
 * @param rankLevel 这次给什么段位，0~6。<b>传了就算手动定级</b>：
 *                  会回写 {@code spot.rank_level} 并把 {@code rank_manual} 置 1；
 *                  传 null 则不覆盖用户已锁定的段位，未锁定时按新均分更新建议值
 */
public record VisitSaveRequest(
        @NotNull(message = "到访日期不能为空") LocalDate visitedAt,
        @NotNull(message = "整体评分不能为空")
        @Min(value = 1, message = "整体评分最低 1 星")
        @Max(value = 5, message = "整体评分最高 5 星") Integer rating,
        @Size(max = 10, message = "分项评分最多 10 项") List<ScoreItem> scores,
        BigDecimal cost,
        @Size(max = 120, message = "同行人最多 120 个字符") String companions,
        @Size(max = 500, message = "感受最多 500 个字") String note,
        @Size(max = 9, message = "一条到访最多 9 张照片") List<ImageRef> photos,
        Integer rankLevel
) {
}
