package ltd.sean.visit.util;

import java.math.BigDecimal;

/**
 * 建议段位（PRD 5.1）。
 *
 * <p>只在「有到访记录」时提供一个省事的起点，<b>不构成定级门槛</b>：
 * 0 次到访返回 null 表示不给建议值，但用户照样可以手动定到王者 ——
 * 段位是「我对这个点的推荐等级」，可以来自听说、朋友推荐，不一定非要自己去过。
 *
 * <p>规则（顺序即优先级，改阈值先改 PRD 5.1 那张表）：
 * <pre>
 *   到访 0 次                      → null（不给建议）
 *   均分 ≥ 4.7 且 到访 ≥ 3         → 6 王者（避免只去一次就封顶）
 *   均分 ≥ 4.5                     → 5 星耀
 *   均分 ≥ 4.0                     → 4 钻石
 *   均分 ≥ 3.5                     → 3 铂金
 *   均分 ≥ 3.0                     → 2 黄金
 *   均分 ≥ 2.0                     → 1 白银
 *   否则                           → 0 青铜
 * </pre>
 */
public final class RankSuggest {

    /** 王者额外要求的最少到访次数 */
    private static final long KING_MIN_VISITS = 3;

    private RankSuggest() {
    }

    public static Integer suggest(long visitCount, BigDecimal avgRating) {
        if (visitCount <= 0 || avgRating == null) {
            return null;
        }
        double avg = avgRating.doubleValue();
        if (avg >= 4.7 && visitCount >= KING_MIN_VISITS) {
            return 6;
        }
        if (avg >= 4.5) {
            return 5;
        }
        if (avg >= 4.0) {
            return 4;
        }
        if (avg >= 3.5) {
            return 3;
        }
        if (avg >= 3.0) {
            return 2;
        }
        if (avg >= 2.0) {
            return 1;
        }
        return 0;
    }
}
