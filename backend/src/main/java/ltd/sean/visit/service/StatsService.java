package ltd.sean.visit.service;

import ltd.sean.visit.common.BizException;
import ltd.sean.visit.config.AppProperties;
import ltd.sean.visit.dto.MonthCountRow;
import ltd.sean.visit.dto.RankCountRow;
import ltd.sean.visit.dto.StatsResponse;
import ltd.sean.visit.dto.StatsTotals;
import ltd.sean.visit.dto.TopSpotRow;
import ltd.sean.visit.mapper.StatsMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 统计看板（`/stats`）的组装。
 *
 * <p>两条约定：
 * <ul>
 *   <li><b>段位名不在这里</b>：后端只知道 {@code rank_level} 是 0~6（schema 定死的），
 *       名称与配色在前端 {@code config/ranks.ts}（AGENTS.md 第 7 条）。这里只补空档。</li>
 *   <li><b>补齐空档</b>：没人去过的月份、没人定过的段位，在这里补 0，
 *       别让前端为「这个月一次都没去」写特例。</li>
 * </ul>
 */
@Service
public class StatsService {

    /** 段位 0~6，与 {@code spot.rank_level} 的取值范围一致 */
    private static final int RANK_LEVEL_MAX = 6;
    private static final int MONTH_MAX = 12;
    private static final int TOP_LIMIT = 3;
    private static final int YEAR_MIN = 1970;
    private static final int YEAR_MAX = 2100;

    private final StatsMapper statsMapper;
    private final AppProperties appProperties;

    public StatsService(StatsMapper statsMapper, AppProperties appProperties) {
        this.statsMapper = statsMapper;
        this.appProperties = appProperties;
    }

    /**
     * @param year 每月到访的年份，null 取服务器当前年
     */
    public StatsResponse overview(Integer year) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        if (targetYear < YEAR_MIN || targetYear > YEAR_MAX) {
            throw new BizException(400, "年份超出范围");
        }

        StatsTotals totals = statsMapper.selectTotals();
        return new StatsResponse(
                nz(totals == null ? null : totals.getSpotCount()),
                statsMapper.selectVisitCount(),
                nz(totals == null ? null : totals.getCityCount()),
                targetYear,
                rankBuckets(),
                monthBuckets(targetYear),
                topSpots());
    }

    private List<StatsResponse.RankBucket> rankBuckets() {
        Map<Integer, Long> counts = statsMapper.selectRankCounts().stream()
                .filter(row -> row.getRankLevel() != null)
                .collect(Collectors.toMap(RankCountRow::getRankLevel, row -> nz(row.getSpotCount()), Long::sum));
        return IntStream.rangeClosed(0, RANK_LEVEL_MAX)
                .mapToObj(level -> new StatsResponse.RankBucket(level, counts.getOrDefault(level, 0L)))
                .toList();
    }

    private List<StatsResponse.MonthBucket> monthBuckets(int year) {
        Map<Integer, Long> counts = statsMapper.selectMonthly(year).stream()
                .filter(row -> row.getMonthNo() != null)
                .collect(Collectors.toMap(MonthCountRow::getMonthNo, row -> nz(row.getVisitCount()), Long::sum));
        return IntStream.rangeClosed(1, MONTH_MAX)
                .mapToObj(month -> new StatsResponse.MonthBucket(month, counts.getOrDefault(month, 0L)))
                .toList();
    }

    private List<StatsResponse.TopSpotItem> topSpots() {
        return statsMapper.selectTopSpots(TOP_LIMIT).stream()
                .map(this::toTopSpot)
                .toList();
    }

    private StatsResponse.TopSpotItem toTopSpot(TopSpotRow row) {
        return new StatsResponse.TopSpotItem(
                row.getId(),
                row.getName(),
                appProperties.toPublicUrl(row.getAvatar()),
                nz(row.getVisitCount()),
                row.getLastVisitAt());
    }

    /** SQL 的 COUNT 不会是 null，但别名列在极端情况下可能缺失，兜一层 0 */
    private long nz(Long value) {
        return value == null ? 0L : value;
    }
}
