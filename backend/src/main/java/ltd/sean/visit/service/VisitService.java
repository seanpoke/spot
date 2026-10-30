package ltd.sean.visit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.sean.visit.common.BizException;
import ltd.sean.visit.config.AppProperties;
import ltd.sean.visit.dto.ScoreItem;
import ltd.sean.visit.dto.SpotVisitStats;
import ltd.sean.visit.dto.TimelineItem;
import ltd.sean.visit.dto.TimelineResponse;
import ltd.sean.visit.dto.TimelineSummary;
import ltd.sean.visit.dto.VisitItem;
import ltd.sean.visit.dto.VisitSaveRequest;
import ltd.sean.visit.entity.Spot;
import ltd.sean.visit.entity.Visit;
import ltd.sean.visit.mapper.SpotMapper;
import ltd.sean.visit.mapper.VisitMapper;
import ltd.sean.visit.service.PhotoService;
import ltd.sean.visit.util.RankSuggest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 到访记录读写。
 *
 * <p>三条硬约定：
 * <ul>
 *   <li>记录只挂在自己那个打卡点下，<b>更新时不允许换点</b>（换点等于搬历史，没有业务意义）</li>
 *   <li>{@code scores} 是 JSON 列，后端只做「最多个数 + 1~5 范围」的兜底校验，
 *       不关心有几项、叫什么（AGENTS.md 第 8 条）</li>
 *   <li>段位回写规则见 {@link #applyRank}：<b>传了段位就算手动定级</b>，
 *       没传只在「没手动锁定过」时按新均分更新建议值</li>
 * </ul>
 */
@Slf4j
@Service
public class VisitService {

    private static final int SCORE_MIN = 1;
    private static final int SCORE_MAX = 5;
    private static final int RANK_MAX = 6;
    /** DECIMAL(10,2) 的上限，超过就是脏数据 */
    private static final BigDecimal COST_MAX = new BigDecimal("99999999.99");

    private final VisitMapper visitMapper;
    private final SpotMapper spotMapper;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final PhotoService photoService;

    public VisitService(VisitMapper visitMapper, SpotMapper spotMapper, ObjectMapper objectMapper,
                        AppProperties appProperties, PhotoService photoService) {
        this.visitMapper = visitMapper;
        this.spotMapper = spotMapper;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.photoService = photoService;
    }

    /** 某个打卡点的全部到访，最近的在前 */
    public List<VisitItem> listBySpot(String spotId) {
        requireSpot(spotId);
        return visitMapper.selectList(new QueryWrapper<Visit>()
                        .eq("spot_id", spotId)
                        .orderByDesc("visited_at")
                        .orderByDesc("created_at"))
                .stream()
                .map(this::toItem)
                .toList();
    }

    /**
     * 狂人日志：全量到访（最新在前）+ 顶部三个数字。
     *
     * <p>两次查询而不是一次 JOIN：先取全部到访，再用一次 {@code IN} 批量取打卡点。
     * 到访表本身不含点名的冗余列，逐条去查就是典型 N+1（AGENTS.md 第 12 条）。
     */
    public TimelineResponse timeline() {
        List<Visit> visits = visitMapper.selectList(new QueryWrapper<Visit>()
                .orderByDesc("visited_at")
                .orderByDesc("created_at"));
        Map<String, Spot> spotMap = loadSpots(visits);
        List<TimelineItem> items = visits.stream()
                .map(visit -> toTimelineItem(visit, spotMap.get(visit.getSpotId())))
                .toList();
        return new TimelineResponse(summarize(visits), items);
    }

    /** 单条（编辑页加载用）。照片只在详情里返回 */
    public VisitItem get(String id) {
        Visit visit = visitMapper.selectById(id);
        if (visit == null) {
            throw BizException.notFound("到访记录不存在");
        }
        return new VisitItem(
                visit.getId(),
                visit.getSpotId(),
                visit.getVisitedAt(),
                visit.getRating(),
                readScores(visit.getScores()),
                visit.getCost(),
                visit.getCompanions(),
                visit.getNote(),
                photoService.listByOwner(PhotoService.OWNER_VISIT, visit.getId()));
    }

    @Transactional
    public String create(String spotId, VisitSaveRequest request) {
        requireSpot(spotId);

        Visit visit = new Visit();
        visit.setSpotId(spotId);
        apply(visit, request);
        visitMapper.insert(visit);
        photoService.replacePhotos(PhotoService.OWNER_VISIT, visit.getId(), request.photos());

        applyRank(spotId, request.rankLevel());
        return visit.getId();
    }

    @Transactional
    public void update(String id, VisitSaveRequest request) {
        Visit visit = visitMapper.selectById(id);
        if (visit == null) {
            throw BizException.notFound("到访记录不存在");
        }

        apply(visit, request);
        visitMapper.updateById(visit);
        photoService.replacePhotos(PhotoService.OWNER_VISIT, id, request.photos());

        applyRank(visit.getSpotId(), request.rankLevel());
    }

    /**
     * 删除记录。删完会重算一次建议段位（仅当该点还没被手动锁定过），
     * 这样「删掉最后一条」后详情页显示的段位不会停在过期的建议值上。
     */
    @Transactional
    public void delete(String id) {
        Visit visit = visitMapper.selectById(id);
        if (visit == null) {
            throw BizException.notFound("到访记录不存在");
        }
        photoService.deleteByOwner(PhotoService.OWNER_VISIT, id);
        visitMapper.deleteById(id);
        applyRank(visit.getSpotId(), null);
    }

    // ---------------------------------------------------------------- 内部实现

    private void apply(Visit visit, VisitSaveRequest request) {
        visit.setVisitedAt(request.visitedAt());
        visit.setRating(request.rating());
        visit.setScores(writeScores(request.scores()));
        visit.setCost(normalizeCost(request.cost()));
        visit.setCompanions(blankToNull(request.companions()));
        visit.setNote(blankToNull(request.note()));
    }

    /**
     * 段位回写。
     *
     * <p>传了 rankLevel：用户这次明确选了段位 → 写入并置 {@code rank_manual = 1}，
     * 之后系统不再自动动它（PRD 5.1「手动为主」）。
     *
     * <p>没传 rankLevel：只在 {@code rank_manual = 0}（还没手动定过）时，
     * 用最新均分算建议值写进 {@code rank_level}；算不出（0 次到访）就不动。
     * 已经手动锁定过的点，系统永远不擅自改段位。
     */
    private void applyRank(String spotId, Integer rankLevel) {
        if (rankLevel != null) {
            if (rankLevel < 0 || rankLevel > RANK_MAX) {
                throw new BizException(400, "段位必须在 0~6 之间");
            }
            updateRank(spotId, rankLevel, true);
            return;
        }

        Spot spot = spotMapper.selectById(spotId);
        if (spot == null || Boolean.TRUE.equals(spot.getRankManual())) {
            return;
        }
        Integer suggested = suggestRank(spotId);
        if (suggested != null) {
            updateRank(spotId, suggested, false);
        }
    }

    private Integer suggestRank(String spotId) {
        List<SpotVisitStats> stats = visitMapper.selectStatsBySpotIds(List.of(spotId));
        if (stats.isEmpty()) {
            return null;
        }
        SpotVisitStats row = stats.get(0);
        long count = row.getVisitCount() == null ? 0L : row.getVisitCount();
        return RankSuggest.suggest(count, row.getAvgRating());
    }

    /** 只写这两列：整行 updateById 会把 packaging 之类无关字段也带上，没必要 */
    private void updateRank(String spotId, int rankLevel, boolean manual) {
        spotMapper.update(null, new LambdaUpdateWrapper<Spot>()
                .eq(Spot::getId, spotId)
                .set(Spot::getRankLevel, rankLevel)
                .set(Spot::getRankManual, manual));
    }

    private Map<String, Spot> loadSpots(List<Visit> visits) {
        Set<String> spotIds = visits.stream()
                .map(Visit::getSpotId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (spotIds.isEmpty()) {
            return Map.of();
        }
        return spotMapper.selectBatchIds(spotIds).stream()
                .collect(Collectors.toMap(Spot::getId, spot -> spot, (a, b) -> a));
    }

    private TimelineItem toTimelineItem(Visit visit, Spot spot) {
        return new TimelineItem(
                visit.getId(),
                visit.getSpotId(),
                spot == null ? null : spot.getName(),
                spot == null ? null : appProperties.toPublicUrl(spot.getAvatar()),
                visit.getVisitedAt(),
                visit.getRating(),
                visit.getCost(),
                visit.getCompanions(),
                visit.getNote());
    }

    /**
     * 顶部三个数字。均分按一位小数四舍五入，与列表页 {@code avgRating} 的口径一致，
     * 否则同一个点是 4.6 还是 4.7 会在两个页面打架。
     */
    private TimelineSummary summarize(List<Visit> visits) {
        YearMonth current = YearMonth.now();
        long monthCount = visits.stream()
                .filter(visit -> visit.getVisitedAt() != null)
                .filter(visit -> YearMonth.from(visit.getVisitedAt()).equals(current))
                .count();

        List<Integer> ratings = visits.stream()
                .map(Visit::getRating)
                .filter(Objects::nonNull)
                .toList();
        BigDecimal avgRating = null;
        if (!ratings.isEmpty()) {
            int sum = ratings.stream().mapToInt(Integer::intValue).sum();
            avgRating = BigDecimal.valueOf(sum)
                    .divide(BigDecimal.valueOf(ratings.size()), 1, RoundingMode.HALF_UP);
        }
        return new TimelineSummary(visits.size(), monthCount, avgRating);
    }

    private VisitItem toItem(Visit visit) {
        // 列表接口不返回照片（带宽小），照片只在单条详情里联表取出
        return new VisitItem(
                visit.getId(),
                visit.getSpotId(),
                visit.getVisitedAt(),
                visit.getRating(),
                readScores(visit.getScores()),
                visit.getCost(),
                visit.getCompanions(),
                visit.getNote(),
                null);
    }

    private String writeScores(List<ScoreItem> scores) {
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        for (ScoreItem item : scores) {
            if (!StringUtils.hasText(item.key())) {
                throw new BizException(400, "分项评分缺少标识");
            }
            Integer value = item.value();
            if (value != null && (value < SCORE_MIN || value > SCORE_MAX)) {
                throw new BizException(400, "分项评分必须在 1~5 之间");
            }
        }
        try {
            return objectMapper.writeValueAsString(scores);
        } catch (JsonProcessingException e) {
            throw new BizException(400, "分项评分格式不正确");
        }
    }

    private List<ScoreItem> readScores(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ScoreItem>>() {
            });
        } catch (JsonProcessingException e) {
            // 存量脏数据不该让详情页 500，记下来按「没评」处理
            log.warn("visit.scores 解析失败，按空处理：{}", json);
            return List.of();
        }
    }

    private BigDecimal normalizeCost(BigDecimal cost) {
        if (cost == null) {
            return null;
        }
        if (cost.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(400, "花费不能是负数");
        }
        if (cost.compareTo(COST_MAX) > 0) {
            throw new BizException(400, "花费大得不像话，请检查一下");
        }
        return cost;
    }

    private void requireSpot(String spotId) {
        Spot spot = spotMapper.selectById(spotId);
        if (spot == null) {
            throw BizException.notFound("打卡点不存在");
        }
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
