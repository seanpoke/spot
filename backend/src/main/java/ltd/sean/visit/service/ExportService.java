package ltd.sean.visit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.sean.visit.common.BizException;
import ltd.sean.visit.dto.ExportedFile;
import ltd.sean.visit.dto.SpotTagRow;
import ltd.sean.visit.dto.SpotVisitStats;
import ltd.sean.visit.entity.Spot;
import ltd.sean.visit.entity.SpotContact;
import ltd.sean.visit.entity.SpotTag;
import ltd.sean.visit.entity.Tag;
import ltd.sean.visit.entity.Visit;
import ltd.sean.visit.mapper.SpotContactMapper;
import ltd.sean.visit.mapper.SpotMapper;
import ltd.sean.visit.mapper.SpotTagMapper;
import ltd.sean.visit.mapper.TagMapper;
import ltd.sean.visit.mapper.VisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 全量数据导出（PRD 功能表 P0「JSON / CSV 全量导出」）。
 *
 * <p>两条硬约定：
 * <ul>
 *   <li><b>导出的图片字段是相对路径</b>（{@code /uploads/...}），不是 CDN 完整地址。
 *       备份的意义是「十年后还能用」，写死域名的备份换一次 CDN 就废了（AGENTS.md 第 1 条）。</li>
 *   <li><b>CSV 带 UTF-8 BOM</b>：Excel 打开不带 BOM 的中文 CSV 会是乱码，
 *       而这份导出最大的用途就是拖进 Excel 看。</li>
 * </ul>
 *
 * <p>导出的是<b>数据库里的数据</b>；图片文件本身不在里面（PRD 7.11 用服务器侧打包 uploads）。
 */
@Slf4j
@Service
public class ExportService {

    /** Excel 认这个前缀才按 UTF-8 解码 */
    private static final String BOM = "\uFEFF";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final String CSV_TYPE = "text/csv; charset=UTF-8";
    private static final String JSON_TYPE = "application/json; charset=UTF-8";

    private final SpotMapper spotMapper;
    private final VisitMapper visitMapper;
    private final TagMapper tagMapper;
    private final SpotTagMapper spotTagMapper;
    private final SpotContactMapper spotContactMapper;
    private final ObjectMapper objectMapper;

    public ExportService(SpotMapper spotMapper, VisitMapper visitMapper, TagMapper tagMapper,
                         SpotTagMapper spotTagMapper, SpotContactMapper spotContactMapper,
                         ObjectMapper objectMapper) {
        this.spotMapper = spotMapper;
        this.visitMapper = visitMapper;
        this.tagMapper = tagMapper;
        this.spotTagMapper = spotTagMapper;
        this.spotContactMapper = spotContactMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 全量 JSON 备份。结构刻意扁平：每张表一个数组，恢复时逐表灌回去即可。
     *
     * <p>v2：联系方式从 spot 的四个固定列拆成了 spot_contact 表，
     * 所以 spots 里不再有 contact* 字段，多一个 spotContacts 数组。
     */
    public ExportedFile json() {
        List<Spot> spots = spotMapper.selectList(null);
        List<Visit> visits = visitMapper.selectList(new QueryWrapper<Visit>()
                .orderByDesc("visited_at")
                .orderByDesc("created_at"));
        List<Tag> tags = tagMapper.selectList(new QueryWrapper<Tag>().orderByAsc("sort_order").orderByAsc("name"));
        List<SpotTag> spotTags = spotTagMapper.selectList(null);
        List<SpotContact> spotContacts = spotContactMapper.selectList(
                new QueryWrapper<SpotContact>().orderByAsc("spot_id").orderByAsc("sort_order"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "spot-backup-v2");
        root.put("exportedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.put("note", "图片字段是相对路径（/uploads/...），图片文件本身不在导出内");

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("spots", spots.size());
        counts.put("visits", visits.size());
        counts.put("tags", tags.size());
        counts.put("spotContacts", spotContacts.size());
        root.put("counts", counts);

        root.put("spots", spots);
        root.put("visits", visits);
        root.put("tags", tags);
        root.put("spotTags", spotTags);
        root.put("spotContacts", spotContacts);

        try {
            byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            return new ExportedFile("spot-backup-" + LocalDateTime.now().format(STAMP) + ".json", JSON_TYPE, content);
        } catch (JsonProcessingException e) {
            log.error("导出 JSON 失败", e);
            throw new BizException(500, "导出失败，请稍后再试");
        }
    }

    /** 打卡点 CSV：一行一个点，标签用 ` | ` 分隔（避免和逗号分隔符打架） */
    public ExportedFile spotsCsv() {
        List<Spot> spots = spotMapper.selectList(new QueryWrapper<Spot>()
                .orderByDesc("rank_level")
                .orderByAsc("name"));
        List<String> spotIds = spots.stream().map(Spot::getId).toList();

        Map<String, List<String>> tagsBySpot = spotIds.isEmpty()
                ? Map.of()
                : spotTagMapper.selectTagRowsBySpotIds(spotIds).stream()
                        .collect(Collectors.groupingBy(SpotTagRow::getSpotId,
                                Collectors.mapping(SpotTagRow::getTagName, Collectors.toList())));
        Map<String, SpotVisitStats> statsBySpot = spotIds.isEmpty()
                ? Map.of()
                : visitMapper.selectStatsBySpotIds(spotIds).stream()
                        .collect(Collectors.toMap(SpotVisitStats::getSpotId, Function.identity(), (a, b) -> a));

        StringBuilder csv = new StringBuilder(BOM);
        csv.append("id,name,kind,status,rank_level,rank_manual,lat,lng,city,address,tags,visit_count,avg_rating,created_at\n");
        for (Spot spot : spots) {
            SpotVisitStats stats = statsBySpot.get(spot.getId());
            List<String> row = List.of(
                    nz(spot.getId()),
                    nz(spot.getName()),
                    nz(spot.getKind()),
                    nz(spot.getStatus()),
                    nz(spot.getRankLevel()),
                    nz(spot.getRankManual()),
                    nz(spot.getLat()),
                    nz(spot.getLng()),
                    nz(spot.getCity()),
                    nz(spot.getAddress()),
                    String.join(" | ", tagsBySpot.getOrDefault(spot.getId(), List.of())),
                    nz(stats == null ? null : stats.getVisitCount()),
                    avgText(stats == null ? null : stats.getAvgRating()),
                    nz(spot.getCreatedAt()));
            appendRow(csv, row);
        }
        return new ExportedFile("spot-spots-" + LocalDate.now() + ".csv", CSV_TYPE, bytes(csv));
    }

    /** 到访 CSV：带上打卡点名，便于在 Excel 里直接看懂 */
    public ExportedFile visitsCsv() {
        List<Visit> visits = visitMapper.selectList(new QueryWrapper<Visit>()
                .orderByDesc("visited_at")
                .orderByDesc("created_at"));
        Map<String, String> nameById = spotMapper.selectList(new QueryWrapper<Spot>().select("id", "name"))
                .stream()
                .collect(Collectors.toMap(Spot::getId, spot -> nz(spot.getName()), (a, b) -> a));

        StringBuilder csv = new StringBuilder(BOM);
        csv.append("id,spot_id,spot_name,visited_at,rating,scores,cost,companions,note,created_at\n");
        for (Visit visit : visits) {
            List<String> row = new ArrayList<>();
            row.add(nz(visit.getId()));
            row.add(nz(visit.getSpotId()));
            row.add(nameById.getOrDefault(visit.getSpotId(), ""));
            row.add(nz(visit.getVisitedAt()));
            row.add(nz(visit.getRating()));
            // scores 是原始 JSON 串，原样带走，恢复时不丢结构
            row.add(nz(visit.getScores()));
            row.add(nz(visit.getCost()));
            row.add(nz(visit.getCompanions()));
            row.add(nz(visit.getNote()));
            row.add(nz(visit.getCreatedAt()));
            appendRow(csv, row);
        }
        return new ExportedFile("spot-visits-" + LocalDate.now() + ".csv", CSV_TYPE, bytes(csv));
    }

    // ---------------------------------------------------------------- 内部实现

    private void appendRow(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(cell(cells.get(i)));
        }
        csv.append('\n');
    }

    /**
     * CSV 单元格转义：含逗号、引号、换行时用双引号包起来，内部引号翻倍。
     * 不转义的直接后果是「一句话感受」里带个逗号就把整行错位。
     */
    private String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * 平均星级写成一位小数：SQL 的 {@code AVG()} 是 DECIMAL(…,4)，
     * 直接导会得到「5.0000」，在 Excel 里还得手动格式化。
     */
    private String avgText(BigDecimal value) {
        return value == null ? "" : value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private byte[] bytes(StringBuilder csv) {
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String nz(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }
}
