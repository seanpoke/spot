package ltd.sean.visit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.sean.visit.common.BizException;
import ltd.sean.visit.config.AppProperties;
import ltd.sean.visit.dto.ContactItem;
import ltd.sean.visit.dto.ImageRef;
import ltd.sean.visit.dto.PackageItem;
import ltd.sean.visit.dto.SpotChild;
import ltd.sean.visit.dto.SpotDetail;
import ltd.sean.visit.dto.SpotListItem;
import ltd.sean.visit.dto.SpotMapResult;
import ltd.sean.visit.dto.SpotMarkerRow;
import ltd.sean.visit.dto.SpotPage;
import ltd.sean.visit.dto.SpotPageQuery;
import ltd.sean.visit.dto.SpotRegion;
import ltd.sean.visit.dto.SpotSaveRequest;
import ltd.sean.visit.dto.SpotTagRow;
import ltd.sean.visit.dto.SpotVisitStats;
import ltd.sean.visit.dto.TagBrief;
import ltd.sean.visit.entity.Spot;
import ltd.sean.visit.entity.SpotContact;
import ltd.sean.visit.entity.SpotTag;
import ltd.sean.visit.entity.Tag;
import ltd.sean.visit.mapper.SpotContactMapper;
import ltd.sean.visit.mapper.SpotMapper;
import ltd.sean.visit.mapper.SpotTagMapper;
import ltd.sean.visit.mapper.TagMapper;
import ltd.sean.visit.mapper.VisitMapper;
import ltd.sean.visit.service.PhotoService;
import ltd.sean.visit.util.RankSuggest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 打卡点读写。
 *
 * <p>几条硬约定：
 * <ul>
 *   <li>列表<b>只查轻量列</b>，描述/价目/联系方式一概不取（AGENTS.md 第 12 条）</li>
 *   <li>标签与到访统计都是<b>批量一次取回</b>再按 spotId 归组，禁止循环查库</li>
 *   <li>图片字段只存相对路径，出参统一过 {@link AppProperties#toPublicUrl(String)}</li>
 *   <li>表单是整体提交，null = 清空（Entity 上已用 updateStrategy=ALWAYS 保证生效）</li>
 *   <li>标签整组覆盖：先删后插，顺序即 {@code spot_tag.sort_order}</li>
 * </ul>
 */
@Slf4j
@Service
public class SpotService {

    /** 允许的取值，与 db/schema.sql 的 ENUM 一一对应 */
    public static final Set<String> KINDS = Set.of("place", "person");
    public static final Set<String> STATUSES = Set.of("unvisited", "visited", "wishlist", "blacklist");

    private static final int MAX_TAGS = 8;
    private static final int RANK_MAX = 6;
    /**
     * 联系方式类型的白名单。
     * 从固定列改成枚举值以后，加一种新联系方式（小红书之类）只改这里 + 前端标签，不动 DDL。
     * （展示顺序在前端，后端只负责校验，所以用 Set；前端顺序见 config/contacts.ts）
     */
    public static final Set<String> CONTACT_TYPES = Set.of("phone", "wechat", "qq", "telegram", "youyu");
    /** 一个打卡点最多几条联系方式：个人自用，20 条已经很宽松，主要防前端死循环 */
    private static final int MAX_CONTACTS = 20;
    /** 与 spot_contact.value 的 VARCHAR(120) 对齐 */
    private static final int MAX_CONTACT_LENGTH = 120;
    /** 电话只收这些字符（+ 国家码、- 分隔、空格、括号），且至少 5 位数字 */
    private static final java.util.regex.Pattern PHONE_PATTERN =
            java.util.regex.Pattern.compile("^[+\\d\\s()-]{5,30}$");
    /** 列表页每页上限：再大就失去分页的意义，与分页插件的 maxLimit 同量级 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 列表查询的轻量列，列表页分页与地图页全量必须一致（AGENTS.md 第 12 条） */
    private static final String[] LIST_COLUMNS = {
            "id", "kind", "name", "avatar", "rank_level", "status", "lat", "lng", "city",
            "province", "district", "price_min", "price_max"
    };
    /** 地图标记列：标签/价格/省市区一概不要（见 SpotMarkerRow 注释） */
    private static final String[] MARKER_COLUMNS = {
            "id", "kind", "name", "avatar", "status", "rank_level", "lat", "lng"
    };
    /** 视野查询默认/最大条数：超过就是「点太多」，由前端提示放大 */
    private static final int DEFAULT_MARKER_LIMIT = 300;
    private static final int MAX_MARKER_LIMIT = 500;
    /** 价目价格上限（元）：正整数，别让 6 位以上的数字撑破 INT 列 */
    private static final BigDecimal MAX_PRICE = new BigDecimal("999999");
    /** 经纬度合法范围（视野查询校验用） */
    private static final BigDecimal LNG_MIN = new BigDecimal("-180");
    private static final BigDecimal LNG_MAX = new BigDecimal("180");
    private static final BigDecimal LAT_MIN = new BigDecimal("-90");
    private static final BigDecimal LAT_MAX = new BigDecimal("90");

    /** 价目里的最低 / 最高价，null = 一条价都没填 */
    private record PriceRange(Integer min, Integer max) {
    }

    private final SpotMapper spotMapper;
    private final SpotTagMapper spotTagMapper;
    private final SpotContactMapper spotContactMapper;
    private final VisitMapper visitMapper;
    private final TagMapper tagMapper;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final PhotoService photoService;

    public SpotService(SpotMapper spotMapper,
                       SpotTagMapper spotTagMapper,
                       SpotContactMapper spotContactMapper,
                       VisitMapper visitMapper,
                       TagMapper tagMapper,
                       AppProperties appProperties,
                       ObjectMapper objectMapper,
                       PhotoService photoService) {
        this.spotMapper = spotMapper;
        this.spotTagMapper = spotTagMapper;
        this.spotContactMapper = spotContactMapper;
        this.visitMapper = visitMapper;
        this.tagMapper = tagMapper;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.photoService = photoService;
    }

    /**
     * 轻量列表：**地图页的抽屉列表**（按定位所在城市）与编辑页的「所属场所」候选在用。
     *
     * <p>地图标记不再走这里（改为视野查询 {@link #mapMarkers}），
     * 所以这个方法保持「不分页」：城市的点数是有界的，前端还要自己做距离排序与 10 条一续。
     *
     * @param city 只列这个城市的点（地图抽屉用）；空则不过滤
     */
    public List<SpotListItem> list(String status, String kind, String tagId, String city, String keyword) {
        QueryWrapper<Spot> query = new QueryWrapper<>();
        // 只取轻量列：description / packages / 联系方式不参与列表。
        // 省/区与 price_min/max 是给列表页做本地筛选用的（都是短字段）
        query.select(LIST_COLUMNS);
        if (StringUtils.hasText(status)) {
            query.eq("status", requireIn(status, STATUSES, "status"));
        }
        if (StringUtils.hasText(kind)) {
            query.eq("kind", requireIn(kind, KINDS, "kind"));
        }
        if (StringUtils.hasText(tagId)) {
            // {0} 是预编译占位符，不要用字符串拼接
            query.apply("id IN (SELECT spot_id FROM spot_tag WHERE tag_id = {0})", tagId);
        }
        applyCity(query, city);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            query.and(w -> w.like("name", kw).or().like("address", kw));
        }
        query.orderByDesc("rank_level").orderByAsc("name");

        return toListItems(spotMapper.selectList(query));
    }

    /**
     * 地图标记：只查**当前视野矩形**内的点（bbox）。
     *
     * <p>为什么要 bbox：地图一次要被几百个标记吃住，全表拉回来再让高德裁掉视野外的点，
     * 传输、标签/统计的批量查询、前端标记 DOM 全都白做。视野外的点画了也看不见。
     *
     * <p>截断策略：按段位降序取前 {@code limit} 条，多查一条判断是否截断，
     * 结果里的 {@code truncated} 由前端显示成「已显示前 N 个，放大看全部」——
     * 截断必须显性告诉用户，否则就像丢数据。
     *
     * @param minLng 西边界经度
     * @param minLat 南边界纬度
     * @param maxLng 东边界经度
     * @param maxLat 北边界纬度
     * @param limit  条数上限，≤0 用默认值，最大 {@link #MAX_MARKER_LIMIT}
     */
    public SpotMapResult mapMarkers(BigDecimal minLng, BigDecimal minLat,
                                    BigDecimal maxLng, BigDecimal maxLat, int limit) {
        requireBbox(minLng, minLat, maxLng, maxLat);
        int size = limit <= 0 ? DEFAULT_MARKER_LIMIT : Math.min(limit, MAX_MARKER_LIMIT);

        QueryWrapper<Spot> query = new QueryWrapper<>();
        query.select(MARKER_COLUMNS)
                .between("lat", minLat, maxLat)
                .between("lng", minLng, maxLng)
                // 与列表同一套排序；id 兜底保证同段位同名的点不会来回抖
                .orderByDesc("rank_level").orderByAsc("id");
        // 多取一条：拿到第 size+1 条就说明被截断了，不必再跑一次 count
        query.last("LIMIT " + (size + 1));

        List<Spot> rows = spotMapper.selectList(query);
        boolean truncated = rows.size() > size;
        List<Spot> page = truncated ? rows.subList(0, size) : rows;

        List<String> ids = page.stream().map(Spot::getId).toList();
        Map<String, SpotVisitStats> stats = ids.isEmpty() ? Map.of() : statsBySpot(ids);
        Map<String, PhotoService.FirstImage> firstImages = ids.isEmpty()
                ? Map.of()
                : photoService.firstImageByOwner(PhotoService.OWNER_SPOT, idsWithoutAvatar(page));

        List<SpotMarkerRow> items = page.stream()
                .map(spot -> new SpotMarkerRow(
                        spot.getId(),
                        spot.getKind(),
                        spot.getName(),
                        avatarOf(spot, firstImages.get(spot.getId())),
                        spot.getStatus(),
                        spot.getRankLevel(),
                        spot.getLat(),
                        spot.getLng(),
                        stats.containsKey(spot.getId()) ? stats.get(spot.getId()).getAvgRating() : null))
                .toList();
        return new SpotMapResult(items, truncated, size);
    }

    /**
     * 列表页分页查询（服务端筛选 + 分页）。
     *
     * <p>为什么不能继续「拉全量本地筛」：列表页的条件（省市区 / 状态 / 段位 / 标签 / 价格 / 关键字）
     * 全在本地过一遍，就得每次把全表的轻量字段连同标签、到访统计、兜底头像一起搬下来，
     * 数据一多光序列化和图片查询就慢。分页之后每页只查当前页的行，标签与统计也只是
     * 这一页的 IN 查询。
     *
     * <p>注意排序必须<b>稳定且唯一</b>：段位 + 名称会大量重复，少了 {@code id} 这个 tiebreaker，
     * 翻页时同一条可能在两页出现、也可能被跳过。
     */
    public SpotPage page(SpotPageQuery query) {
        int pageNo = Math.max(query.page(), 1);
        int pageSize = query.size() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(query.size(), MAX_PAGE_SIZE);

        QueryWrapper<Spot> wrapper = new QueryWrapper<>();
        wrapper.select(LIST_COLUMNS);

        List<String> statuses = normalizeStatuses(query.statuses());
        if (!statuses.isEmpty()) {
            wrapper.in("status", statuses);
        }
        List<Integer> ranks = normalizeRanks(query.rankLevels());
        if (!ranks.isEmpty()) {
            wrapper.in("rank_level", ranks);
        }
        List<String> tagIds = nonBlank(query.tagIds());
        if (!tagIds.isEmpty()) {
            // 标签多选按「任一命中」：等价于 EXISTS (... tag_id IN (...))，这里用 OR 拼等价条件，
            // {0} 是预编译占位符，逐个绑定，不做字符串拼接
            wrapper.and(w -> {
                for (int i = 0; i < tagIds.size(); i++) {
                    if (i > 0) {
                        w.or();
                    }
                    w.apply("id IN (SELECT spot_id FROM spot_tag WHERE tag_id = {0})", tagIds.get(i));
                }
            });
        }

        applyText(wrapper, "province", query.province());
        applyText(wrapper, "city", query.city());
        applyText(wrapper, "district", query.district());

        // 价格按「点位价位跨度」与查询区间是否重叠判断（与原列表页本地逻辑一致）：
        // 一个价都没填的点，只要加了价格条件就不出现
        if (query.priceMin() != null || query.priceMax() != null) {
            wrapper.isNotNull("price_min").isNotNull("price_max");
            if (query.priceMin() != null) {
                wrapper.ge("price_max", query.priceMin());
            }
            if (query.priceMax() != null) {
                wrapper.le("price_min", query.priceMax());
            }
        }

        if (StringUtils.hasText(query.keyword())) {
            String kw = query.keyword().trim();
            wrapper.and(w -> w.like("name", kw).or().like("address", kw));
        }

        wrapper.orderByDesc("rank_level").orderByAsc("name").orderByAsc("id");

        Page<Spot> result = spotMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        long total = result.getTotal();
        return new SpotPage(
                toListItems(result.getRecords()),
                total,
                pageNo,
                pageSize,
                (long) pageNo * pageSize < total);
    }

    /**
     * 省 / 市 / 区去重组合，供列表页筛选下拉。
     *
     * <p>分页之后前端拿不到全量数据，选项不能再从已加载的行里现取，
     * 否则选项会随「加载了几页」变化。这里一次返回全部去重组合（百级、纯短文本）。
     */
    public List<SpotRegion> regions() {
        QueryWrapper<Spot> wrapper = new QueryWrapper<>();
        // 三个字段全空的行对下拉没有意义，直接在 SQL 里排掉：
        // 全 NULL 的行 selectMaps 会映射成 null 元素（MyBatis 对「一行没有任何值」的处理），
        // 与其在 Java 里判空，不如根本不查回来
        // 这里没有别的条件，三个 OR 直接平铺即可，不要再套 and(...)（会多出一对括号与前导 AND）
        wrapper.select("DISTINCT province, city, district")
                .isNotNull("province").or().isNotNull("city").or().isNotNull("district")
                .orderByAsc("province").orderByAsc("city").orderByAsc("district");
        List<SpotRegion> regions = new ArrayList<>();
        for (Map<String, Object> row : spotMapper.selectMaps(wrapper)) {
            if (row == null) {
                continue;
            }
            String province = text(row.get("province"));
            String city = text(row.get("city"));
            String district = text(row.get("district"));
            if (province == null && city == null && district == null) {
                continue;
            }
            regions.add(new SpotRegion(province, city, district));
        }
        return regions;
    }

    public SpotDetail detail(String id) {
        Spot spot = requireSpot(id);
        List<String> one = List.of(id);

        List<TagBrief> tags = tagsBySpot(one).getOrDefault(id, List.of());
        SpotVisitStats stats = statsBySpot(one).get(id);

        String parentName = null;
        if (StringUtils.hasText(spot.getParentSpotId())) {
            Spot parent = spotMapper.selectById(spot.getParentSpotId());
            parentName = parent == null ? null : parent.getName();
        }

        List<Spot> childRows = spotMapper.selectList(new QueryWrapper<Spot>()
                .select("id", "name", "avatar", "rank_level")
                .eq("parent_spot_id", id)
                .orderByDesc("rank_level")
                .orderByAsc("name"));
        // 挂靠的人物也一样：没设头像就用它自己第一张照片，一次 IN 查完
        Map<String, PhotoService.FirstImage> childFirst =
                photoService.firstImageByOwner(PhotoService.OWNER_SPOT, idsWithoutAvatar(childRows));
        List<SpotChild> children = childRows.stream()
                .map(child -> new SpotChild(child.getId(), child.getName(),
                        avatarOf(child, childFirst.get(child.getId())), child.getRankLevel()))
                .toList();

        List<ImageRef> photos = photoService.listByOwner(PhotoService.OWNER_SPOT, spot.getId());
        // 联系方式一条一行，sort_order 就是用户添加顺序；只有详情页需要，列表不取
        List<ContactItem> contacts = spotContactMapper
                .selectList(new QueryWrapper<SpotContact>().eq("spot_id", spot.getId())
                        .orderByAsc("sort_order").orderByAsc("created_at"))
                .stream()
                .map(row -> new ContactItem(row.getType(), row.getValue()))
                .toList();
        // 没单独设头像/封面就用第一张照片顶上，省得用户为了「列表里有图」再传一遍
        PhotoService.FirstImage firstImage = photos.isEmpty() ? null : photoService.firstImageOf(photos.get(0));
        String ownAvatar = appProperties.toPublicUrl(spot.getAvatar());
        String ownCover = appProperties.toPublicUrl(spot.getCover());
        // 兜底值要带标记回前端：编辑页与「采纳建议段位」都是整体提交，
        // 把兜底值当成用户设定回存，第一张照片就被钉死成头像/封面了（之后删了照片还在）
        boolean avatarFromPhotos = ownAvatar == null && firstImage != null;
        boolean coverFromPhotos = ownCover == null && firstImage != null;

        long visitCount = stats == null || stats.getVisitCount() == null ? 0L : stats.getVisitCount();
        BigDecimal avgRating = stats == null ? null : stats.getAvgRating();

        return new SpotDetail(
                spot.getId(),
                spot.getKind(),
                spot.getName(),
                avatarFromPhotos ? firstImage.avatar() : ownAvatar,
                coverFromPhotos ? firstImage.cover() : ownCover,
                avatarFromPhotos,
                coverFromPhotos,
                spot.getDescription(),
                spot.getLat(),
                spot.getLng(),
                spot.getAddress(),
                spot.getCity(),
                spot.getProvince(),
                spot.getDistrict(),
                contacts,
                photos,
                readPackages(spot.getPackages()),
                spot.getRankLevel(),
                spot.getRankManual(),
                RankSuggest.suggest(visitCount, avgRating),
                spot.getParentSpotId(),
                parentName,
                children,
                spot.getStatus(),
                tags,
                visitCount,
                avgRating,
                stats == null ? null : stats.getLastVisitAt(),
                spot.getCreatedAt(),
                spot.getUpdatedAt());
    }

    @Transactional
    public String create(SpotSaveRequest request) {
        Spot spot = new Spot();
        apply(spot, request, null);
        spotMapper.insert(spot);
        replaceTags(spot.getId(), request.tagIds());
        replaceContacts(spot.getId(), request.contacts());
        photoService.replacePhotos(PhotoService.OWNER_SPOT, spot.getId(), request.photos());
        return spot.getId();
    }

    @Transactional
    public void update(String id, SpotSaveRequest request) {
        Spot spot = requireSpot(id);

        // place -> person 会让已挂靠的人物变成「挂在人物下面」，数据立刻不自洽，先拦住
        String targetKind = StringUtils.hasText(request.kind()) ? request.kind().trim() : "place";
        if ("person".equals(targetKind) && "place".equals(spot.getKind())) {
            Long children = spotMapper.selectCount(new QueryWrapper<Spot>().eq("parent_spot_id", id));
            if (children != null && children > 0) {
                throw new BizException(400, "该场所下还挂着 " + children + " 个人物，请先把他们改挂到别处");
            }
        }

        apply(spot, request, id);
        spotMapper.updateById(spot);
        replaceTags(id, request.tagIds());
        replaceContacts(id, request.contacts());
        photoService.replacePhotos(PhotoService.OWNER_SPOT, id, request.photos());
    }

    /** 物理删除：spot_tag 与 visit 由外键 ON DELETE CASCADE 连带清掉，照片行在事务里手动清 */
    @Transactional
    public void delete(String id) {
        requireSpot(id);
        photoService.deleteByOwner(PhotoService.OWNER_SPOT, id);
        spotMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 内部实现

    private void apply(Spot spot, SpotSaveRequest request, String selfId) {
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new BizException(400, "名称不能为空");
        }
        spot.setName(name);
        spot.setKind(requireIn(request.kind(), KINDS, "kind", "place"));
        // 没传状态时按「未打卡」：新建一个点不等于「想去」，更不等于「去过」
        spot.setStatus(requireIn(request.status(), STATUSES, "status", "unvisited"));

        int rank = request.rankLevel() == null ? 0 : request.rankLevel();
        if (rank < 0 || rank > RANK_MAX) {
            throw new BizException(400, "段位必须在 0~6 之间");
        }
        spot.setRankLevel(rank);
        spot.setRankManual(Boolean.TRUE.equals(request.rankManual()));

        // 入参可能是 toPublicUrl 拼好的完整地址，落库前必须还原成相对路径
        spot.setAvatar(appProperties.toStoredPath(blankToNull(request.avatar())));
        spot.setCover(appProperties.toStoredPath(blankToNull(request.cover())));
        spot.setDescription(blankToNull(request.description()));
        spot.setLat(inRange(request.lat(), -90, 90, "纬度"));
        spot.setLng(inRange(request.lng(), -180, 180, "经度"));
        spot.setAddress(blankToNull(request.address()));
        spot.setCity(blankToNull(request.city()));
        spot.setProvince(blankToNull(request.province()));
        spot.setDistrict(blankToNull(request.district()));
        // 联系方式不在这里写：它是一张子表，跟标签一样「整组覆盖」，见 replaceContacts
        // 价目：只收正整数价格；顺手把最低/最高价算出来存成一列，列表页按价格筛就是比大小
        List<PackageItem> packages = normalizePackages(request.packages());
        spot.setPackages(writePackages(packages));
        PriceRange range = priceRange(packages);
        spot.setPriceMin(range.min());
        spot.setPriceMax(range.max());

        spot.setParentSpotId(resolveParent(request.parentSpotId(), spot.getKind(), selfId));
    }

    /**
     * 所属场所只有人物能填，且必须指向一个存在的 place。
     * 「场所自己也能挂场所」会把详情页的返回链路绕成环，这里直接拒掉。
     */
    private String resolveParent(String rawParentId, String kind, String selfId) {
        String parentId = blankToNull(rawParentId);
        if (parentId == null) {
            return null;
        }
        if ("place".equals(kind)) {
            throw new BizException(400, "场所不能设置所属场所");
        }
        if (parentId.equals(selfId)) {
            throw new BizException(400, "不能把自己设为所属场所");
        }
        Spot parent = spotMapper.selectById(parentId);
        if (parent == null) {
            throw new BizException(400, "所属场所不存在");
        }
        if (!"place".equals(parent.getKind())) {
            throw new BizException(400, "只能挂靠到场所，不能挂靠到人物");
        }
        return parentId;
    }

    private void replaceTags(String spotId, List<String> rawTagIds) {
        List<String> tagIds = rawTagIds == null
                ? List.of()
                : rawTagIds.stream().filter(StringUtils::hasText).distinct().toList();

        if (tagIds.size() > MAX_TAGS) {
            throw new BizException(400, "每个打卡点最多 " + MAX_TAGS + " 个标签");
        }
        if (!tagIds.isEmpty()) {
            Long existing = tagMapper.selectCount(new QueryWrapper<Tag>().in("id", tagIds));
            if (existing == null || existing != tagIds.size()) {
                throw new BizException(400, "有标签不存在，请刷新后重试");
            }
        }

        // 整组覆盖：先删后插，一个事务内完成
        spotTagMapper.delete(new QueryWrapper<SpotTag>().eq("spot_id", spotId));
        for (int index = 0; index < tagIds.size(); index++) {
            SpotTag relation = new SpotTag();
            relation.setSpotId(spotId);
            relation.setTagId(tagIds.get(index));
            relation.setSortOrder(index);
            spotTagMapper.insert(relation);
        }
    }

    /**
     * 联系方式整组覆盖：先删后插。
     *
     * <p>和标签同一套语义 —— 编辑页是「加一行 / 删一行」，任何局部更新都会让
     * 「删掉最后一个电话」静默失效；整体覆盖最省心，也天然保证 sort_order 有序。
     *
     * <p>空行直接丢（不报错）：用户点了 + 又没填就保存，不该被一个空输入框拦住。
     */
    private void replaceContacts(String spotId, List<ContactItem> raw) {
        List<ContactItem> contacts = normalizeContacts(raw);
        spotContactMapper.delete(new QueryWrapper<SpotContact>().eq("spot_id", spotId));
        for (int index = 0; index < contacts.size(); index++) {
            SpotContact row = new SpotContact();
            row.setSpotId(spotId);
            row.setType(contacts.get(index).type());
            row.setValue(contacts.get(index).value());
            row.setSortOrder(index);
            spotContactMapper.insert(row);
        }
    }

    /**
     * 联系方式校验 + 规整：
     * 类型必须在白名单里、值去掉首尾空白、同类型同值去重、电话只收数字与常用分隔符。
     *
     * <p>顺序保持前端传来的顺序 —— 那就是用户添加的顺序，也是详情页的展示顺序。
     */
    private List<ContactItem> normalizeContacts(List<ContactItem> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ContactItem> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ContactItem item : raw) {
            if (item == null) {
                continue;
            }
            String type = requireIn(item.type(), CONTACT_TYPES, "联系方式类型");
            String value = blankToNull(item.value());
            if (value == null) {
                continue;
            }
            if (value.length() > MAX_CONTACT_LENGTH) {
                throw new BizException(400, "联系方式太长（最多 " + MAX_CONTACT_LENGTH + " 个字符）");
            }
            if ("phone".equals(type)) {
                int digits = 0;
                for (char ch : value.toCharArray()) {
                    if (Character.isDigit(ch)) {
                        digits++;
                    }
                }
                if (!PHONE_PATTERN.matcher(value).matches() || digits < 5) {
                    throw new BizException(400, "电话号码只能包含数字、+、-、空格和括号，且至少 5 位数字");
                }
            }
            if (!seen.add(type + "|" + value)) {
                continue;
            }
            result.add(new ContactItem(type, value));
        }
        if (result.size() > MAX_CONTACTS) {
            throw new BizException(400, "联系方式最多 " + MAX_CONTACTS + " 条");
        }
        return result;
    }

    private List<SpotListItem> toListItems(List<Spot> spots) {
        if (spots.isEmpty()) {
            return List.of();
        }
        List<String> ids = spots.stream().map(Spot::getId).toList();
        Map<String, List<TagBrief>> tags = tagsBySpot(ids);
        Map<String, SpotVisitStats> stats = statsBySpot(ids);
        // 没设头像的点用第一张照片顶上：只查缺头像的那些，一次 IN 拿回
        Map<String, PhotoService.FirstImage> firstImages =
                photoService.firstImageByOwner(PhotoService.OWNER_SPOT, idsWithoutAvatar(spots));

        return spots.stream()
                .map(spot -> new SpotListItem(
                        spot.getId(),
                        spot.getKind(),
                        spot.getName(),
                        avatarOf(spot, firstImages.get(spot.getId())),
                        spot.getRankLevel(),
                        spot.getStatus(),
                        spot.getLat(),
                        spot.getLng(),
                        spot.getCity(),
                        spot.getProvince(),
                        spot.getDistrict(),
                        spot.getPriceMin(),
                        spot.getPriceMax(),
                        stats.containsKey(spot.getId()) ? stats.get(spot.getId()).getAvgRating() : null,
                        tags.getOrDefault(spot.getId(), List.of())))
                .toList();
    }

    /** 头像：自己设过就用自己设的，否则用该点第一张照片的 96×96 派生图 */
    private String avatarOf(Spot spot, PhotoService.FirstImage firstImage) {
        String own = appProperties.toPublicUrl(spot.getAvatar());
        return own != null ? own : (firstImage == null ? null : firstImage.avatar());
    }

    /** 只有没设头像的点才需要去查照片，省掉一次没必要的 IN */
    private List<String> idsWithoutAvatar(Collection<Spot> spots) {
        return spots.stream()
                .filter(spot -> !StringUtils.hasText(spot.getAvatar()))
                .map(Spot::getId)
                .toList();
    }

    /** 一次 IN 查询取回全部标签，再按 spotId 归组（顺序由 SQL 的 ORDER BY 保证） */
    private Map<String, List<TagBrief>> tagsBySpot(Collection<String> spotIds) {
        Map<String, List<TagBrief>> grouped = new LinkedHashMap<>();
        for (SpotTagRow row : spotTagMapper.selectTagRowsBySpotIds(spotIds)) {
            grouped.computeIfAbsent(row.getSpotId(), key -> new ArrayList<>())
                    .add(new TagBrief(row.getTagId(), row.getTagName(), row.getTagColor()));
        }
        return grouped;
    }

    /** 一次 GROUP BY 取回全部统计；没有到访记录的点不在 map 里（不是 0 分） */
    private Map<String, SpotVisitStats> statsBySpot(Collection<String> spotIds) {
        Map<String, SpotVisitStats> bySpot = new HashMap<>();
        for (SpotVisitStats stats : visitMapper.selectStatsBySpotIds(spotIds)) {
            bySpot.put(stats.getSpotId(), stats);
        }
        return bySpot;
    }

    /** 状态多选：逐个校验，非法值直接 400（不要静默丢掉，前端拼错就查不出东西还找不到原因） */
    private List<String> normalizeStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .map(status -> requireIn(status, STATUSES, "status"))
                .toList();
    }

    /** 段位多选：0~6，越界 400 */
    private List<Integer> normalizeRanks(List<Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Integer> ranks = new ArrayList<>();
        for (Integer rank : raw) {
            if (rank == null) {
                continue;
            }
            if (rank < 0 || rank > RANK_MAX) {
                throw new BizException(400, "段位必须在 0~" + RANK_MAX + " 之间");
            }
            if (!ranks.contains(rank)) {
                ranks.add(rank);
            }
        }
        return ranks;
    }

    private List<String> nonBlank(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
    }

    private void applyText(QueryWrapper<Spot> wrapper, String column, String value) {
        if (StringUtils.hasText(value)) {
            wrapper.eq(column, value.trim());
        }
    }

    /**
     * 城市匹配：库里 city 是人填 + 高德反查混进来的，「武汉」和「武汉市」都会出现，
     * 所以不能用等值。三段 OR 覆盖三种写法：
     *   1) 完全相等
     *   2) 库里以入参开头（库「武汉市」、入参「武汉」）
     *   3) 入参以库里开头（库「武汉」、入参「武汉市」）
     */
    private void applyCity(QueryWrapper<Spot> query, String city) {
        if (!StringUtils.hasText(city)) {
            return;
        }
        String raw = city.trim();
        String normalized = normalizeCityName(raw);
        query.and(w -> w.eq("city", raw)
                .or().likeRight("city", normalized)
                .or().apply("{0} LIKE CONCAT(city, '%')", raw));
    }

    /** 去掉行政区划后缀：「武汉市」→「武汉」（前端 MapHomeView.normalizeCity 同一套规则） */
    private String normalizeCityName(String value) {
        return value.replaceAll("(省|市|自治区|特别行政区|地区|盟)$", "").trim();
    }

    /**
     * 视野矩形校验：缺参、min ≥ max、超出经纬度范围一律 400。
     *
     * <p>不做「跨 180° 经线」的处理：本项目只看境内，跨线会把简单查询变成两段，
     * 真需要时再说，别提前抽象。
     */
    private void requireBbox(BigDecimal minLng, BigDecimal minLat, BigDecimal maxLng, BigDecimal maxLat) {
        if (minLng == null || minLat == null || maxLng == null || maxLat == null) {
            throw new BizException(400, "缺少视野范围（minLng / minLat / maxLng / maxLat）");
        }
        if (minLng.compareTo(maxLng) >= 0 || minLat.compareTo(maxLat) >= 0) {
            throw new BizException(400, "视野范围不合法：min 必须小于 max");
        }
        if (minLng.compareTo(LNG_MIN) < 0 || maxLng.compareTo(LNG_MAX) > 0
                || minLat.compareTo(LAT_MIN) < 0 || maxLat.compareTo(LAT_MAX) > 0) {
            throw new BizException(400, "视野范围超出合法坐标");
        }
    }

    /** selectMaps 的列值统一转字符串：MySQL 回来的可能是 byte[]，也别信它一定是 String */
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        return blankToNull(String.valueOf(value));
    }

    private Spot requireSpot(String id) {
        Spot spot = spotMapper.selectById(id);
        if (spot == null) {
            throw BizException.notFound("打卡点不存在");
        }
        return spot;
    }

    private List<PackageItem> readPackages(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PackageItem>>() {
            });
        } catch (JsonProcessingException e) {
            // 存量脏数据不该让详情页 500，记下来按「没填」处理
            log.warn("spot.packages 解析失败，按空处理：{}", json);
            return List.of();
        }
    }

    private String writePackages(List<PackageItem> packages) {
        if (packages == null || packages.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(packages);
        } catch (JsonProcessingException e) {
            throw new BizException(400, "价目表格式不正确");
        }
    }

    /**
     * 价目只接受正整数价格（元）。
     *
     * <p>价格在 JSON 里可以写成 88.5，那样 price_min/price_max 就没法当整数列比较，
     * 所以在入口统一收口：空行丢掉、名字必须有、价格 > 0 且不能带小数。
     */
    private List<PackageItem> normalizePackages(List<PackageItem> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<PackageItem> cleaned = new ArrayList<>(raw.size());
        for (PackageItem item : raw) {
            if (item == null) {
                continue;
            }
            String name = item.name() == null ? "" : item.name().trim();
            BigDecimal price = item.price();
            if (name.isEmpty() && price == null) {
                // 整行都空：前端删到只剩空行时不必报错
                continue;
            }
            if (name.isEmpty()) {
                throw new BizException(400, "价目名称不能为空");
            }
            if (price != null) {
                if (price.signum() <= 0) {
                    throw new BizException(400, "价格必须是正整数（元）");
                }
                if (price.stripTrailingZeros().scale() > 0) {
                    throw new BizException(400, "价格必须是正整数（元），不要填小数");
                }
                if (price.compareTo(MAX_PRICE) > 0) {
                    throw new BizException(400, "价格超出范围，最大 " + MAX_PRICE.toPlainString() + " 元");
                }
                price = price.setScale(0, RoundingMode.UNNECESSARY);
            }
            cleaned.add(new PackageItem(name, price));
        }
        return cleaned;
    }

    /** packages 里的最低 / 最高价；一条价都没填时两个都是 null */
    private PriceRange priceRange(List<PackageItem> packages) {
        Integer min = null;
        Integer max = null;
        for (PackageItem item : packages) {
            if (item.price() == null) {
                continue;
            }
            int value = item.price().intValue();
            min = min == null ? value : Math.min(min, value);
            max = max == null ? value : Math.max(max, value);
        }
        return new PriceRange(min, max);
    }

    private String requireIn(String value, Set<String> allowed, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!allowed.contains(normalized)) {
            throw new BizException(400, field + " 取值不合法");
        }
        return normalized;
    }

    private String requireIn(String value, Set<String> allowed, String field, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return requireIn(value, allowed, field);
    }

    private BigDecimal inRange(BigDecimal value, int min, int max, String label) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new BizException(400, label + "超出合法范围");
        }
        return value;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
