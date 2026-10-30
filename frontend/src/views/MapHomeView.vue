<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { listSpots, mapSpots, type SpotBounds, type SpotListItem, type SpotStatus } from '@/api/spots'
import { logout, markAnonymous } from '@/api/auth'
import {
  DEFAULT_CENTER,
  amapStyle,
  cityOfComponent,
  loadAmap,
  type AmapGeocoder,
  type AmapMap,
  type AmapOverlay,
} from '@/config/amap'
import MapPicker from '@/components/MapPicker.vue'
import RankBar from '@/components/RankBar.vue'
import { createMeMarker, createSpotMarker } from '@/components/mapMarker'
import { RANKS, rankOf } from '@/config/ranks'
import { SPOT_STATUSES, statusLabel } from '@/config/status'
import { distanceMeters, formatDistance, wgs84ToGcj02, type LatLng } from '@/utils/geo'
import { getPositionWithFallback } from '@/utils/geolocation'
import TabBar from '@/components/TabBar.vue'
import SpotRow from '@/components/SpotRow.vue'

/**
 * 地图主页（PRD 第三章 `/`）。
 *
 * 三层结构，自下而上：
 *   ① 高德全屏地图 + 白胶囊标记（环表状态、条表等级，见 components/mapMarker.ts）
 *   ② 顶部：条数 / 标题 / 搜索 / 更多；下面一排状态筛选胶囊
 *   ③ 底部三档面板 peek(300) / half(492) / full(视口高)，拖拽手柄或点按钮切换
 *
 * 几个刻意的取舍：
 *   - 距离需要参照点。没拿到定位就不显示距离，绝不拿地图中心硬算（那会给出看似精确的错数）。
 *   - **半径快选只是缩放快捷键**：以我为中心缩放到 1/5/10/20/30 km 刚好装满可见区域，
 *     **不参与点位过滤**（筛数据的事归状态胶囊），也**没有选中态** ——
 *     存了状态就会跟用户的自由缩放打架（地图缩过了，胶囊还高亮 1km 等于撒谎）。
 *   - 居中一律用 setCenter，**不做遮挡补偿**：补偿量是按当前 zoom 算的像素，
 *     紧接着改 zoom 会按 2^Δzoom 放大，表现就是「点了之后随机偏到附近去」。
 *   - 搜索是**服务端 LIKE + 本地排序**：名称/地址的模糊匹配给服务端（列表不返回 address），
 *     取回命中集合后按「匹配度 − 距离惩罚」重排，才能做到「越像越靠前、同样像的按离我近」。
 *   - **三份数据源，各管一件事**（别再把"一份全量"喂给三处）：
 *       ① 地图标记 = 当前视野矩形（`/spots/map`，bbox），拖一次地图查一次，被 limit 截断时提示放大；
 *       ② 底部抽屉 = 定位所在城市（`/spots?city=`），城市的点数有界，距离排序与 10 条一续仍在本地；
 *       ③ 搜索建议 = 全局 LIKE（`/spots?q=`），找的是「这个点在哪」，不能被视野或城市挡住。
 *     状态胶囊 / 等级多选仍只在前端过一遍：它们不改「要查哪些行」，改一次就发请求是浪费。
 *   - 只列「本地图页要用」的东西：日志 / 统计 / 我的三个 Tab 还没做，点了给一句明确提示，
 *     不假装能跳（跳过去就是 404，比禁用更糟）。
 */

interface SpotRow {
  spot: SpotListItem
  /** 没有定位权限时为 null，界面就不显示距离 */
  distance: number | null
  /** 关键字匹配度 0~100；没搜时统一 100 */
  match: number
}

/**
 * 半径快选（公里）：按「一眼能扫完」的阶梯给，不要做成滑块。
 *
 * 这五个按钮**没有选中态**：它们是「一键缩放到几公里」的动作，不是筛选项。
 * 用户可以连点，也可以自己双指缩放 —— 一旦存状态，就会跟自由缩放打架
 * （地图已经缩放过了，胶囊还高亮着 1km，等于撒谎）。
 * `DEFAULT_RADIUS` 只用于首屏取景那一次。
 */
const RADIUS_OPTIONS = [1, 5, 10, 20, 30] as const
const DEFAULT_RADIUS = 10

/** collapsed = 完全收起（高度 0，只留一个悬浮的「展开」按钮） */
type PanelState = 'collapsed' | 'peek' | 'half' | 'full'

const router = useRouter()

const PANEL_BASE = { peek: 300, half: 492, full: 690 }
/** 收到 0 才是真收起：只收到 peek(300) 时地图永远被压掉三分之一 */
const PANEL_COLLAPSED = 0
const TABBAR_HEIGHT = 66

const PILLS: readonly { value: 'all' | SpotStatus; label: string }[] = [
  { value: 'all', label: '全部' },
  ...SPOT_STATUSES.map((item) => ({ value: item.value, label: item.label })),
]

const mapEl = ref<HTMLDivElement | null>(null)
const searchEl = ref<HTMLInputElement | null>(null)

/**
 * 三份数据源：
 *   viewSpots   当前视野矩形内的点（bbox）——只喂地图标记
 *   citySpots   定位所在城市的点——只喂底部抽屉
 *   searchSpots 关键字命中的点（全局）——有搜索时接管标记与抽屉
 * 之前是一份全量同时喂三处，等于每拖一次地图、每改一个字都在搬全表。
 */
const viewSpots = ref<SpotListItem[]>([])
const citySpots = ref<SpotListItem[]>([])
const searchSpots = ref<SpotListItem[]>([])
/** 视野查询被 limit 截断：提示「已显示前 N 个，放大看全部」 */
const viewTruncated = ref(false)
const viewLimit = ref(0)
const markerError = ref('')
/** 抽屉列表（城市）的加载态与错误，模板里那套「加载中 / 重试」只跟它有关 */
const loading = ref(true)
const errorMessage = ref('')
const filter = ref<'all' | SpotStatus>('all')
/** 等级筛选：多选，空数组 = 不限 */
const rankFilter = ref<number[]>([])
const keyword = ref('')
const searchOpen = ref(false)
/** 搜索下拉是否展开：选中一条后收起，再输入自动展开 */
const suggestOpen = ref(false)
const moreOpen = ref(false)
const selectedId = ref<string | null>(null)

/** 真实 GPS 定位（GCJ-02）。只有 locate() 会写它 */
const realMe = ref<LatLng | null>(null)
/**
 * 临时坐标：设了之后，距离、城市、排序全按它算 —— 相当于「假装我在这里」。
 * 纯前端状态、不落库不持久化，刷新即失效；「归位」一键清掉回到真实定位。
 */
const tempLoc = ref<LatLng | null>(null)
/** 一切计算用的坐标：临时坐标优先。模板与距离计算只看它，不看 realMe */
const me = computed<LatLng | null>(() => tempLoc.value ?? realMe.value)
/** 有效坐标所在城市（逆地理编码反查）：列表只列这个城市的点 */
const meCity = ref('')
/** 临时坐标选择器（复用编辑页的 MapPicker，交互一致） */
const pickingTemp = ref(false)

/**
 * 定位失败的原因。空串 = 正常。
 * 手机上「没有当前坐标」最常见的元凶是 insecure：
 * 用 http://局域网IP 打开属于非安全上下文，浏览器直接不给定位（跟有没有开权限无关）。
 */
type GeoIssue = '' | 'insecure' | 'denied' | 'unavailable' | 'timeout' | 'unsupported'
const geoIssue = ref<GeoIssue>('')
/** 引导卡是否展开：出问题时自动弹出，用户可手动关掉 */
const geoNoticeOpen = ref(false)

interface GeoHint {
  title: string
  desc: string
  steps: string[]
  retry: boolean
}

/** 按失败原因给不同的引导文案：能自救的给步骤，改不了的就直说 */
const geoHint = computed<GeoHint>(() => {
  switch (geoIssue.value) {
    case 'insecure':
      return {
        title: 'HTTP 访问拿不到定位',
        desc: '浏览器只允许 HTTPS（或 localhost）使用定位。当前是 HTTP，所以地图上不会出现「我的位置」。',
        steps: [
          '改用 HTTPS 地址打开（开发服务器配证书，或用内网穿透工具）',
          '或在本机用 http://localhost:5173 打开',
        ],
        retry: false,
      }
    case 'denied':
      return {
        title: '定位权限被拒绝',
        desc: '请在浏览器里允许本站使用定位，然后点「重新定位」。',
        steps: [
          '点地址栏左侧的锁 / ⓘ → 权限 → 位置 → 允许',
          'iPhone Safari：设置 → Safari 浏览器 → 位置 → 允许',
          '安卓 Chrome：设置 → 网站设置 → 位置信息 → 允许',
        ],
        retry: true,
      }
    case 'unavailable':
      return {
        title: '暂时拿不到定位',
        desc: '站点权限已允许，但系统没返回位置：多半是手机定位总开关没开，或浏览器 App 没有系统定位权限。',
        steps: [
          '打开手机「定位服务 / GPS」总开关',
          '给浏览器 App 系统定位权限（设置 → 应用 → 浏览器 → 权限 → 位置）',
          '到窗边或室外再试',
        ],
        retry: true,
      }
    case 'timeout':
      return {
        title: '定位超时',
        desc: '8 秒内没拿到坐标，多见于室内或首次授权。',
        steps: ['到窗边或室外再试', '确认已允许本站使用定位'],
        retry: true,
      }
    case 'unsupported':
      return {
        title: '浏览器不支持定位',
        desc: '请换用 Chrome / Safari 等支持定位的浏览器打开。',
        steps: [],
        retry: false,
      }
    default:
      return { title: '', desc: '', steps: [], retry: false }
  }
})

const mapLoading = ref(true)
const mapError = ref('')
const mapRef = shallowRef<AmapMap | null>(null)
const markers = shallowRef<AmapOverlay[]>([])

const panelHeight = ref(PANEL_COLLAPSED)
const heights = ref({ ...PANEL_BASE })

/**
 * 标记索引不进响应式：里面是地图对象与 DOM，Vue 代理它们只会出问题。
 * `sig` 是「内容指纹」：视野查询每次都会返回同一批点，内容没变就不重建 DOM，
 * 只增删真正进出的那几个 —— 整体重建会让拖动掉帧，也会丢选中态。
 */
const markerIndex = new Map<string, { marker: AmapOverlay; el: HTMLElement; sig: string }>()
/**
 * 见过的点位缓存：拖动地图后视野外的点会被移出结果，但「当前选中」的那个还要能补回来；
 * 顺带避免同一批点在视野间来回时反复重建。
 */
const spotCache = new Map<string, SpotListItem>()
let meMarker: AmapOverlay | null = null
/** 蓝点的内容 DOM：临时/真实切换时直接改 class，不重建标记 */
let meEl: HTMLElement | null = null
let fitted = false
/** 首屏是否已经按「我 + 默认半径」取过景 */
let located = false
let searchTimer: number | undefined

/** 小写化的搜索词；空串表示没在搜 */
const query = computed(() => keyword.value.trim().toLowerCase())

/**
 * 已经把结果取回来的那个关键字。
 * `spots` 只有在它等于当前关键字时才是「这一轮的结果」——打字过程中 `spots` 还是全量，
 * 那一刻拿去出下拉就会出现「先闪一下全量、再变成匹配结果」。
 */
const loadedQuery = ref('')

/**
 * 关键字匹配度 0~100。分数只用来排序，档位之间留够差距，
 * 免得「地址里碰巧出现一个字」把名字包含的挤下去。
 *
 * 地址匹配不在本地判断：列表接口按 AGENTS.md 第 12 条只返回精简字段（不含 address），
 * 名称/地址的 LIKE 由服务端做；能进到这里的都是服务端认为命中的。
 * 所以本地命中不了名字或标签时，给一个「地址命中」的基准分，而不是 0（0 会被当成没命中）。
 */
function matchScore(spot: SpotListItem, needle: string): number {
  if (!needle) {
    return 100
  }
  const name = spot.name.toLowerCase()
  if (name === needle) {
    return 100
  }
  if (name.startsWith(needle)) {
    return 85
  }
  if (name.includes(needle)) {
    return 70
  }
  const tagHit = (spot.tags ?? []).some((tag) => tag.name.toLowerCase().includes(needle))
  if (tagHit) {
    return 55
  }
  // 服务端是按「名称或地址」LIKE 的，走到这里基本就是地址命中
  return 40
}

/**
 * 排序分 = 匹配度 − 距离惩罚（1km 抵 1.5 分，最多抵 45 分 / 30km）。
 * 这样「像但远」和「像且近」能拉开，又不会让 50km 外的完全同名胜过 200m 的前缀匹配。
 * 没有关键字时 match 全是 100，差值 0，自动退化成「按距离排」。
 */
function rankScore(row: SpotRow): number {
  const km = row.distance === null ? 0 : Math.min(row.distance / 1000, 30)
  return row.match - km * 1.5
}

function compareRows(a: SpotRow, b: SpotRow): number {
  const byRank = rankScore(b) - rankScore(a)
  if (Math.abs(byRank) > 0.001) {
    return byRank
  }
  if (a.distance !== null && b.distance !== null && a.distance !== b.distance) {
    return a.distance - b.distance
  }
  if (a.spot.rankLevel !== b.spot.rankLevel) {
    return b.spot.rankLevel - a.spot.rankLevel
  }
  return a.spot.name.localeCompare(b.spot.name, 'zh-Hans-CN')
}

/** 把打卡点包成带「距离 + 匹配度」的行；距离没有定位时为 null */
function toRows(list: SpotListItem[], needle: string): SpotRow[] {
  const origin = me.value
  return list.map((spot) => {
    const coords = coordsOf(spot)
    return {
      spot,
      distance: origin && coords ? distanceMeters(origin, coords) : null,
      match: matchScore(spot, needle),
    }
  })
}

/**
 * 状态 + 等级过滤（**半径不参与**：左边那排只是「一键缩放到几公里」的快捷方式，
 * 让它藏点位就等于把「看地图」和「筛数据」混成一件事）。
 *
 * 这一层永远在本地做：它不改变「要查哪些行」，只为它发一次请求是纯浪费。
 */
function rowsFor(list: SpotListItem[], needle: string): SpotRow[] {
  const rows = toRows(list, needle).filter((row) => {
    // 当前明确选中的点永远可见：搜索建议可能选中了状态筛选之外的点
    if (row.spot.id === selectedId.value) {
      return true
    }
    if (filter.value !== 'all' && row.spot.status !== filter.value) {
      return false
    }
    return rankFilter.value.length === 0 || rankFilter.value.includes(row.spot.rankLevel)
  })
  rows.sort(compareRows)
  return rows
}

/** 标记数据源：搜索时是全局命中，否则是当前视野 */
const markerSource = computed(() => (query.value ? searchSpots.value : viewSpots.value))

/** 抽屉数据源：搜索时是全局命中（下面再按城市收），否则是服务端按城市取的那批 */
const listSource = computed(() => (query.value ? searchSpots.value : citySpots.value))

/**
 * 地图标记用这份数据。
 *
 * 「当前选中的点永远可见」现在要跨数据源兜底：视野一拖，选中的那个点就不在结果里了，
 * 从缓存里把它补回来，否则选中态会凭空消失。
 */
const markerRows = computed<SpotRow[]>(() => {
  const base = markerSource.value
  const selected = selectedId.value
  if (selected && !base.some((spot) => spot.id === selected)) {
    const cached = spotCache.get(selected)
    if (cached) {
      return rowsFor([...base, cached], query.value)
    }
  }
  return rowsFor(base, query.value)
})

/**
 * 搜索下拉：**不受状态筛选限制**。
 * 找的是「这个点在哪」，不是「附近有什么」——搜索出来的东西被筛掉会像坏了。
 * 顺序仍是匹配度为主、距离为辅。
 */
const suggestions = computed<SpotRow[]>(() => {
  const needle = query.value
  if (!needle || loadedQuery.value !== needle) {
    return []
  }
  const list = toRows(searchSpots.value, needle)
  list.sort(compareRows)
  return list.slice(0, 6)
})

/** 结果还没到：下拉里显示「搜索中」，而不是拿全量数据充数 */
const searching = computed(() => query.value !== '' && loadedQuery.value !== query.value)

const showSuggestPanel = computed(
  () => searchOpen.value && suggestOpen.value && (searching.value || suggestions.value.length > 0),
)

/**
 * 去掉行政区划后缀再比：用户手填的「武汉」和反查回来的「武汉市」要能对上。
 */
function normalizeCity(value?: string | null): string {
  return (value ?? '').replace(/(省|市|自治区|特别行政区|地区|盟)$/g, '').trim()
}

function sameCity(a?: string | null, b?: string | null): boolean {
  const left = normalizeCity(a)
  const right = normalizeCity(b)
  if (!left || !right) {
    return false
  }
  return left === right || left.includes(right) || right.includes(left)
}

/**
 * 抽屉列表的**全集**：状态 / 等级 / 关键字命中的点里，只留「定位所在城市」的。
 *
 * 城市过滤现在有一半在服务端做了（`/spots?city=`），这里仍保留一道：
 *   - 搜索时 `searchSpots` 是全局结果，必须本地再收一次城市；
 *   - 服务端的城市匹配是「武汉 / 武汉市」兼容匹配，本地 `sameCity` 兜住极端写法。
 * 拿不到城市（定位失败 / 反查没回来）就不过滤 —— 宁多勿空，但距离仍按有效坐标算。
 */
const cityRows = computed<SpotRow[]>(() => {
  const rows = rowsFor(listSource.value, query.value)
  const city = meCity.value
  if (!city) {
    return rows
  }
  const hasCityData = rows.some((row) => !!row.spot.city)
  if (!hasCityData) {
    return rows
  }
  return rows.filter((row) => sameCity(row.spot.city, city))
})

/** 列表分页：默认 10 条，滚到底继续放 10 条，放完为止（纯本地切片，不再发请求） */
const LIST_PAGE = 10
const listLimit = ref(LIST_PAGE)

const hasMoreRows = computed(() => cityRows.value.length > listLimit.value)

/** 面板列表用这份数据：城市内、按距离排好、只切前 listLimit 条 */
const listRows = computed<SpotRow[]>(() => cityRows.value.slice(0, listLimit.value))

function loadMore(): void {
  if (hasMoreRows.value) {
    listLimit.value += LIST_PAGE
  }
}

/**
 * 等级多选的开/关。
 *
 * 每次都换一个新数组：`rankFilter` 是 ref<number[]>，watch 与 computed 靠引用变化触发，
 * 原地 push/splice 会让过滤结果不刷新。
 */
function toggleRankFilter(level: number): void {
  rankFilter.value = rankFilter.value.includes(level)
    ? rankFilter.value.filter((item) => item !== level)
    : [...rankFilter.value, level]
}

// 数据口径一变就回到第一页：城市换了/关键字换了/状态换了/等级换了，续着旧页数会跳着显示
watch([filter, rankFilter, loadedQuery, meCity], () => {
  listLimit.value = LIST_PAGE
})

/** 空态文案要说清「为什么是空的」，否则用户会以为数据丢了 */
const emptyText = computed(() => {
  const needle = keyword.value.trim()
  if (needle) {
    return `没有匹配「${needle}」的打卡点`
  }
  if (filter.value !== 'all') {
    return `${statusLabel(filter.value)}的打卡点在这附近没有`
  }
  if (rankFilter.value.length > 0) {
    const names = rankFilter.value.map((level) => rankOf(level)?.name ?? `${level}级`).join('、')
    return `${names}的打卡点在这附近没有`
  }
  return '还没有打卡点，先建一个'
})

const panelState = computed<PanelState>(() => {
  if (panelHeight.value <= PANEL_COLLAPSED + 12) {
    return 'collapsed'
  }
  if (panelHeight.value <= heights.value.peek + 30) {
    return 'peek'
  }
  if (panelHeight.value >= heights.value.full - 30) {
    return 'full'
  }
  return 'half'
})

function coordsOf(spot: SpotListItem): LatLng | null {
  if (spot.lat === undefined || spot.lng === undefined) {
    return null
  }
  return { lat: spot.lat, lng: spot.lng }
}

/** 标记上的两行文案：已打卡给分数，其余给状态，有距离才补距离 */
function markerMeta(row: SpotRow): string {
  const head =
    row.spot.status === 'visited'
      ? row.spot.avgRating
        ? `${row.spot.avgRating.toFixed(1)} 分`
        : '已打卡'
      : statusLabel(row.spot.status)
  return row.distance === null ? head : `${head} · ${formatDistance(row.distance)}`
}

function rowMeta(row: SpotRow): string {
  const status = statusLabel(row.spot.status)
  return row.distance === null ? status : `${status} · ${formatDistance(row.distance)}`
}

/* ---------------- 三份数据源的取数 ---------------- */

/** 视野不低于这一级才查 bbox：再往外就是半个中国，查了也是被截断的 300 条 */
const VIEWPORT_MIN_ZOOM = 10
/** 视野外扩比例：小幅拖动不会在边缘反复增删标记 */
const VIEWPORT_PAD = 0.2
const VIEWPORT_LIMIT = 300

let listSeq = 0
let searchSeq = 0
let viewSeq = 0
let viewTimer: number | undefined
let viewAbort: AbortController | null = null

function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { name?: string }).name === 'AbortError'
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/** 缓存见过的点：视野外的「当前选中」要能补回来 */
function cacheSpots(list: SpotListItem[]): void {
  for (const spot of list) {
    // 重新 set 会把 key 移到 Map 尾部，天然就是 LRU 顺序
    spotCache.delete(spot.id)
    spotCache.set(spot.id, spot)
  }
  while (spotCache.size > 2000) {
    const oldest = spotCache.keys().next().value
    if (oldest === undefined) {
      break
    }
    spotCache.delete(oldest)
  }
}

/**
 * 抽屉列表：定位所在城市（拿不到城市就不过滤）。
 * 城市的点数有界，所以不分页；距离排序与「10 条一续」仍在本地做。
 */
async function loadList(): Promise<void> {
  const seq = ++listSeq
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await listSpots({ city: meCity.value || undefined })
    if (seq !== listSeq) {
      return
    }
    citySpots.value = data
    cacheSpots(data)
    // 缩得很远时标记就是用城市这批兜底的，列表回来了要同步过去
    if (mapRef.value && (mapRef.value.getZoom?.() ?? 0) < VIEWPORT_MIN_ZOOM) {
      viewSpots.value = data
    }
  } catch (error) {
    if (seq !== listSeq) {
      return
    }
    citySpots.value = []
    errorMessage.value = messageOf(error)
  } finally {
    if (seq === listSeq) {
      loading.value = false
    }
  }
}

/**
 * 抽屉列表的首次加载：先等定位反查的城市（约 1s）。
 *
 * 不等的话启动就是「先拉一次全表 → 城市回来再按城市拉一次」，
 * 两次都碰全量大头，而这正是这次要省掉的东西。定位失败（geoIssue）时不必再等，立即拉。
 */
const LIST_GRACE_MS = 1200
let listTimer: number | undefined

function scheduleListLoad(): void {
  window.clearTimeout(listTimer)
  if (meCity.value) {
    void loadList()
    return
  }
  listTimer = window.setTimeout(() => {
    void loadList()
  }, LIST_GRACE_MS)
}

/** 搜索：全局 LIKE，命中后由本地按「匹配度 − 距离惩罚」重排 */
async function loadSearch(): Promise<void> {
  const needle = keyword.value.trim()
  if (!needle) {
    searchSpots.value = []
    loadedQuery.value = ''
    // 搜索期间视野查询被跳过（没有消费者），清空关键字时补一次：
    // 不然标记会切回一份「搜索开始前」的旧视野数据
    void loadViewport()
    return
  }
  const seq = ++searchSeq
  try {
    const data = await listSpots({ keyword: needle })
    if (seq !== searchSeq) {
      return
    }
    searchSpots.value = data
    cacheSpots(data)
    loadedQuery.value = needle.toLowerCase()
  } catch (error) {
    if (seq !== searchSeq) {
      return
    }
    searchSpots.value = []
    // 失败时不能让下拉拿旧数据装作「这一轮的结果」
    loadedQuery.value = ''
    errorMessage.value = messageOf(error)
  }
}

/** 视野查询（防抖 300ms）：只查当前 bbox，点太多时服务端按 limit 截断 */
function scheduleViewport(): void {
  window.clearTimeout(viewTimer)
  viewTimer = window.setTimeout(() => {
    void loadViewport()
  }, 300)
}

async function loadViewport(): Promise<void> {
  const map = mapRef.value
  if (!map) {
    return
  }
  // 搜索期间标记用全局命中，视野查询没有消费者：先不发，关键字清空时会补一次
  if (query.value) {
    return
  }
  // 缩到省级 / 国家级：bbox 等于半张地图，查了也是「被截断的前 300 个」。
  // 直接用城市那批兜底，标记至少是「我附近的点」，不会整屏空掉。
  if ((map.getZoom?.() ?? 0) < VIEWPORT_MIN_ZOOM) {
    viewAbort?.abort()
    viewAbort = null
    viewSpots.value = citySpots.value
    viewTruncated.value = false
    viewLimit.value = 0
    return
  }
  const bounds = map.getBounds?.()
  const sw = bounds?.getSouthWest?.()
  const ne = bounds?.getNorthEast?.()
  if (!sw || !ne) {
    return
  }
  const lngSpan = ne.getLng() - sw.getLng()
  const latSpan = ne.getLat() - sw.getLat()
  const box: SpotBounds = {
    minLng: sw.getLng() - lngSpan * VIEWPORT_PAD,
    maxLng: ne.getLng() + lngSpan * VIEWPORT_PAD,
    minLat: sw.getLat() - latSpan * VIEWPORT_PAD,
    maxLat: ne.getLat() + latSpan * VIEWPORT_PAD,
  }

  const seq = ++viewSeq
  // 取消上一次：拖动时请求会排成队，回来的顺序和发出的顺序不一致
  viewAbort?.abort()
  const controller = new AbortController()
  viewAbort = controller
  try {
    const data = await mapSpots(box, { limit: VIEWPORT_LIMIT, signal: controller.signal })
    if (seq !== viewSeq) {
      return
    }
    markerError.value = ''
    viewSpots.value = data.items
    viewTruncated.value = data.truncated
    viewLimit.value = data.limit
    cacheSpots(data.items)
  } catch (error) {
    if (isAbortError(error) || seq !== viewSeq) {
      return
    }
    markerError.value = `地图点位没加载出来：${messageOf(error)}`
  } finally {
    if (seq === viewSeq) {
      viewAbort = null
    }
  }
}

async function initMap(): Promise<void> {
  if (!mapEl.value) {
    return
  }
  mapLoading.value = true
  mapError.value = ''
  try {
    await loadAmap()
    const amap = window.AMap
    if (!amap || !mapEl.value) {
      throw new Error('高德 JS API 未就绪')
    }
    const first = markerRows.value.map((row) => coordsOf(row.spot)).find((coords) => coords !== null)
    const map = new amap.Map(mapEl.value, {
      zoom: 13,
      center: first ? [first.lng, first.lat] : DEFAULT_CENTER,
      viewMode: '2D',
      mapStyle: amapStyle(),
      // 去掉自带 POI 点：整屏都是别家的店名，会让自己的标记读不出来
      features: ['bg', 'road', 'building'],
      showLabel: true,
      resizeEnable: true,
      zooms: [3, 19],
    })
    mapRef.value = map
    // 拖动 / 缩放结束后重查视野（防抖在 scheduleViewport 里）
    map.on('moveend', () => scheduleViewport())
    syncMarkers()
    syncMeMarker()
    ensureFit()
    // 首屏视野要等地图实例就位才知道，list 那份已经先发了
    void loadViewport()
  } catch (error) {
    mapError.value = error instanceof Error ? error.message : String(error)
  } finally {
    mapLoading.value = false
  }
}

/**
 * 标记同步：**增量**。
 *
 * bbox 查询下数组每次拖动都变，整体重建（先 remove 再 add）会让拖动明显掉帧，
 * 还会把选中态与头像加载状态一起丢掉。这里只做三件事：
 *   内容指纹变了 → 就地换 content；新出现的 → 建；不在了 → 删。
 */
function syncMarkers(): void {
  const map = mapRef.value
  const amap = window.AMap
  if (!map || !amap) {
    return
  }

  const wanted = new Set<string>()
  const overlays: AmapOverlay[] = []

  for (const row of markerRows.value) {
    const coords = coordsOf(row.spot)
    if (!coords) {
      continue
    }
    const selected = row.spot.id === selectedId.value
    const meta = markerMeta(row)
    // 选中态不参与指纹：它靠 class 切（applySelection），重建 DOM 反而会闪
    const sig = [row.spot.name, row.spot.avatar ?? '', row.spot.status, row.spot.rankLevel, meta].join('|')
    wanted.add(row.spot.id)

    const existing = markerIndex.get(row.spot.id)
    if (existing) {
      if (existing.sig !== sig) {
        const el = createSpotMarker(row.spot, meta, selected)
        existing.marker.setContent(el)
        existing.el = el
        existing.sig = sig
      }
      existing.marker.setzIndex(selected ? 200 : 100)
      overlays.push(existing.marker)
      continue
    }

    const el = createSpotMarker(row.spot, meta, selected)
    const marker = new amap.Marker({
      position: [coords.lng, coords.lat],
      content: el,
      anchor: 'center',
      zIndex: selected ? 200 : 100,
    })
    marker.on('click', () => onMarkerClick(row.spot))
    map.add(marker)
    markerIndex.set(row.spot.id, { marker, el, sig })
    overlays.push(marker)
  }

  // 移出视野的点：只删这几个，别动其他的
  for (const [id, entry] of markerIndex) {
    if (!wanted.has(id)) {
      map.remove(entry.marker)
      markerIndex.delete(id)
    }
  }

  markers.value = overlays
}

function syncMeMarker(): void {
  const map = mapRef.value
  const amap = window.AMap
  if (!map || !amap) {
    return
  }
  const here = me.value
  if (!here) {
    if (meMarker) {
      map.remove(meMarker)
      meMarker = null
      meEl = null
    }
    return
  }
  if (meMarker) {
    meMarker.setPosition([here.lng, here.lat])
    meEl?.classList.toggle('mk-me--temp', tempLoc.value !== null)
    return
  }
  meEl = createMeMarker(tempLoc.value !== null)
  meMarker = new amap.Marker({
    position: [here.lng, here.lat],
    content: meEl,
    anchor: 'center',
    zIndex: 90,
  })
  map.add(meMarker)
}

/**
 * 把当前可见的标记框进视野。
 *
 * avoid 顺序是「上 左 下 右」：底部留出面板高度，否则标记全被面板压住。
 */
function fitRows(): void {
  const map = mapRef.value
  if (!map || markers.value.length === 0) {
    return
  }
  if (markers.value.length === 1) {
    const only = markerRows.value.map((row) => coordsOf(row.spot)).find((coords) => coords !== null)
    if (only) {
      map.setCenter([only.lng, only.lat])
      map.setZoom(15)
    }
    return
  }
  map.setFitView(markers.value, false, [40, 150, 40, 340], 16)
}

/** 首屏只自动取景一次：之后用户自己拖动地图，不该被强行拉回去 */
function ensureFit(): void {
  if (fitted || markers.value.length === 0) {
    return
  }
  fitted = true
  const here = me.value
  const map = mapRef.value
  if (here && map) {
    // 有定位就按「我 + 默认半径」取景：框标记会把镜头拉去标记群中间
    located = true
    map.setCenter([here.lng, here.lat])
    map.setZoom(zoomForRadius(DEFAULT_RADIUS))
    return
  }
  fitRows()
}

function applySelection(): void {
  for (const [id, entry] of markerIndex) {
    entry.el.classList.toggle('mk--on', id === selectedId.value)
  }
}

function onMarkerClick(spot: SpotListItem): void {
  if (selectedId.value === spot.id) {
    void router.push(`/spot/${spot.id}`)
    return
  }
  selectedId.value = spot.id
  applySelection()
  setPanel('peek')
  nudgeIntoView(spot.id)
}

/**
 * 把标记挪进「面板上方」的可见区。
 *
 * 不能直接用 panTo 居中：面板占掉底部 366px（peek 300 + TabBar 66），
 * 居中后选中的点正好落在面板后面 —— 点了却看不见，像没反应。
 */
function nudgeIntoView(id: string): void {
  const map = mapRef.value
  const amap = window.AMap
  const el = markerIndex.get(id)?.el
  const container = mapEl.value
  if (!map || !amap || !el || !container || typeof map.containerToLngLat !== 'function') {
    return
  }
  const rect = el.getBoundingClientRect()
  const box = container.getBoundingClientRect()
  // 用 peek 的固定高度算边界，不用面板的实时 rect：面板正在做 280ms 高度过渡，量出来是中间值
  // 面板完全收起时没有遮挡，底边就是 TabBar 上沿
  const panelInset = panelState.value === 'collapsed' ? PANEL_COLLAPSED : heights.value.peek
  const limitBottom = box.bottom - TABBAR_HEIGHT - panelInset - 12
  const limitTop = box.top + 120

  let shift = 0
  if (rect.bottom > limitBottom) {
    shift = rect.bottom - limitBottom
  } else if (rect.top < limitTop) {
    shift = rect.top - limitTop
  }
  if (shift === 0) {
    return
  }
  // 中心点往下移 shift 像素，标记在屏幕上就上移 shift 像素
  const target = map.containerToLngLat(
    new amap.Pixel(container.clientWidth / 2, container.clientHeight / 2 + shift),
  )
  map.setCenter([target.getLng(), target.getLat()])
}

/**
 * 让「以我为圆心、半径 km」的整个圆落进可见区域。
 *
 * 用 Web Mercator 换算自己算 zoom，而不是 setFitView 框标记：
 * 标记只落在圆内的某几个点，框标记的结果是「圆只露一角」或过度放大，跟点了几公里对不上。
 * 156543.03392 = zoom 0 时赤道上的每像素米数。
 *
 * 注意：**只算缩放，不动中心**。中心交给 setCenter，不要在这里做遮挡补偿 ——
 * 补偿量是按当前 zoom 算出来的像素，紧接着改 zoom 会把它按 2^Δzoom 放大，
 * 表现为「点了之后随机偏到附近去」。
 */
function zoomForRadius(km: number): number {
  const map = mapRef.value
  const el = mapEl.value
  // 没有定位时用当前地图中心的纬度，缩放照样能算
  const lat = me.value?.lat ?? map?.getCenter().getLat()
  if (!el || typeof lat !== 'number') {
    return 15
  }
  const rect = el.getBoundingClientRect()
  const hidden = Math.min(panelHeight.value + TABBAR_HEIGHT, rect.height * 0.45)
  const visibleHeight = Math.max(160, rect.height - hidden)
  // 圆的直径（2R）要塞进短边，再留一点边距
  const fitPx = Math.max(120, Math.min(rect.width, visibleHeight) * 0.85)
  const metersPerPixel = (km * 2000) / fitPx
  const zoom = Math.log2((156543.03392 * Math.cos((lat * Math.PI) / 180)) / metersPerPixel)
  return Math.min(19, Math.max(3, Math.round(zoom * 10) / 10))
}

/**
 * 归位：回到「我」，并清掉半径快选。
 *
 * 半径只是一组缩放快捷键，不该留下勾选态；也别在这里套用半径的 zoom，
 * 否则「归位」会看起来像「点了某个半径」。
 */
function recenter(): void {
  const map = mapRef.value
  const here = me.value
  if (!map || !here) {
    return
  }
  map.setCenter([here.lng, here.lat])
  map.setZoom(15)
}

/** 自动定位失败后静默重试的间隔：定位源预热通常就差这一下 */
const AUTO_RETRY_DELAY = 1200

/** 每次 locate 递增。旧请求的回调不能盖掉新结果（用户可能已经手动点过「重新定位」） */
let locateSeq = 0

function applyPosition(position: GeolocationPosition, manual: boolean): void {
  // 浏览器给的是 WGS-84，项目统一 GCJ-02；不转会有 300~500m 的系统性偏移
  const here = wgs84ToGcj02(position.coords.latitude, position.coords.longitude)
  realMe.value = here
  geoIssue.value = ''
  geoNoticeOpen.value = false
  if (manual) {
    // 只居中，**不要** setFitView：拟合所有标记会把镜头拉到标记群中间
    recenter()
  }
}

function reportGeoError(error: GeolocationPositionError, manual: boolean): void {
  // 1 = 权限被拒，2 = 位置不可用，3 = 超时
  geoIssue.value = error.code === 1 ? 'denied' : error.code === 2 ? 'unavailable' : 'timeout'
  geoNoticeOpen.value = true
  if (manual) {
    ElMessage.warning('还是拿不到定位，看看页面上的提示')
  }
}

/**
 * 拿定位。
 * manual（点「归位」）= 临时坐标生效时先清掉它（归位就是「回到真实位置」），
 * 再用已知坐标立刻归位，最后静默刷新一次 GPS；
 * auto（进页面）= 只更新坐标，取景交给 watch([me, mapRef]) / ensureFit。
 */
function locate(mode: 'auto' | 'manual'): void {
  const manual = mode === 'manual'
  if (manual) {
    if (tempLoc.value) {
      tempLoc.value = null
      ElMessage.success('已还原为真实定位')
    }
    // 已经有坐标就先回位：别让用户盯着一个可能超时 8s 的定位请求
    recenter()
    geoNoticeOpen.value = false
  }

  // 非安全上下文：浏览器直接封掉定位，先给明确原因（手机用 IP 访问最常踩的坑）
  if (window.isSecureContext === false) {
    geoIssue.value = 'insecure'
    geoNoticeOpen.value = true
    if (manual) {
      ElMessage.warning('HTTP 访问无法定位，请改用 HTTPS')
    }
    return
  }
  if (!('geolocation' in navigator)) {
    geoIssue.value = 'unsupported'
    geoNoticeOpen.value = true
    if (manual) {
      ElMessage.warning('这个浏览器不支持定位')
    }
    return
  }

  const seq = ++locateSeq

  /**
   * 自动定位第一次失败先别弹卡：刷新后首次取位时定位源（GPS / Wi-Fi 扫描）往往
   * 还没预热完，浏览器会直接回 POSITION_UNAVAILABLE，隔一秒再问一次基本就有了。
   * 权限被拒(1) 重试没有意义；超时(3) 已经等了几十秒，再等只会更让人烦。
   */
  const attempt = (retried: boolean): void => {
    const request = retried ? getPositionWithFallback({ gpsOnly: true }) : getPositionWithFallback()
    request.then(
      (position) => {
        if (seq !== locateSeq) {
          return
        }
        applyPosition(position, manual)
      },
      (error: GeolocationPositionError) => {
        if (seq !== locateSeq) {
          return
        }
        if (!manual && !retried && error.code === 2) {
          window.setTimeout(() => {
            if (seq === locateSeq) {
              attempt(true)
            }
          }, AUTO_RETRY_DELAY)
          return
        }
        reportGeoError(error, manual)
      },
    )
  }

  attempt(false)
}

/**
 * 监听授权状态：用户在设置里允许后回到页面，自动再取一次，不用手动点「重新定位」。
 * Safari 不支持 geolocation 的 Permission 查询，查询失败就静默跳过。
 */
let geoPermission: PermissionStatus | null = null

async function watchGeoPermission(): Promise<void> {
  if (!('permissions' in navigator) || !navigator.permissions?.query) {
    return
  }
  try {
    geoPermission = await navigator.permissions.query({ name: 'geolocation' })
    geoPermission.onchange = () => {
      if (geoPermission?.state === 'granted' && !realMe.value) {
        locate('auto')
      }
    }
  } catch {
    // 某些浏览器（Safari）不接受 name: 'geolocation'，忽略即可
  }
}

function clampHeight(value: number): number {
  return Math.min(heights.value.full, Math.max(PANEL_COLLAPSED, value))
}

function setPanel(state: PanelState): void {
  panelHeight.value = state === 'collapsed' ? PANEL_COLLAPSED : heights.value[state]
}

let dragFrom = 0
let dragStartHeight = 0
let dragging = false
let dragMoved = false

function onGrabDown(event: PointerEvent): void {
  dragging = true
  dragMoved = false
  dragFrom = event.clientY
  dragStartHeight = panelHeight.value
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onGrabMove(event: PointerEvent): void {
  if (!dragging) {
    return
  }
  const delta = dragFrom - event.clientY
  if (Math.abs(delta) > 3) {
    dragMoved = true
  }
  panelHeight.value = clampHeight(dragStartHeight + delta)
}

function onGrabUp(event: PointerEvent): void {
  if (!dragging) {
    return
  }
  dragging = false
  const target = event.currentTarget as HTMLElement
  if (target.hasPointerCapture(event.pointerId)) {
    target.releasePointerCapture(event.pointerId)
  }
  // 吸附到最近的档位：多出 0 这一档，往下拖到底就能真正收起来
  const stops = [PANEL_COLLAPSED, heights.value.peek, heights.value.half, heights.value.full]
  const height = panelHeight.value
  panelHeight.value = stops.reduce((best, stop) =>
    Math.abs(height - stop) < Math.abs(height - best) ? stop : best,
  )
}

/** 点手柄（而不是拖动）在 peek / half 之间切换 */
function onGrabClick(): void {
  if (dragMoved) {
    dragMoved = false
    return
  }
  panelHeight.value = panelState.value === 'peek' ? heights.value.half : heights.value.peek
}

function recalcHeights(): void {
  const max = Math.max(360, window.innerHeight - TABBAR_HEIGHT - 90)
  heights.value = {
    peek: Math.min(PANEL_BASE.peek, max - 60),
    half: Math.min(PANEL_BASE.half, max - 10),
    full: max,
  }
  panelHeight.value = clampHeight(panelHeight.value)
}

async function toggleSearch(): Promise<void> {
  searchOpen.value = !searchOpen.value
  moreOpen.value = false
  if (searchOpen.value) {
    suggestOpen.value = true
    await nextTick()
    searchEl.value?.focus()
    return
  }
  suggestOpen.value = false
  if (keyword.value) {
    keyword.value = ''
  }
}

/**
 * 点搜索建议 = 把这个点搬到眼前，而不是直接跳详情：
 * 地图页的职责是「看位置」，详情留给她自己点。
 */
function pickSuggestion(row: SpotRow): void {
  suggestOpen.value = false
  selectedId.value = row.spot.id
  setPanel('peek')
  // 标记是这一帧才建出来的（markerRows 变了才轮到 syncMarkers），所以取景放到 nextTick
  void nextTick(() => {
    applySelection()
    const coords = coordsOf(row.spot)
    if (coords) {
      mapRef.value?.setCenter([coords.lng, coords.lat])
      mapRef.value?.setZoom(15)
    }
    // **不要**在这里 nudgeIntoView：它靠 getBoundingClientRect() 量标记位置，
    // 而刚 setCenter 完高德还没把标记挪到新位置，量到的是旧坐标 ——
    // 于是每点一次就被「修正」一个随机像素量，表现为「点好几次才落到位上」
  })
}

function pickFirst(): void {
  if (!suggestions.value.length) {
    return
  }
  const first = suggestions.value[0]
  if (first) {
    pickSuggestion(first)
  }
}

/**
 * 一键缩放到 km 公里：以我为中心，让半径刚好装满可见区域。
 *
 * 纯动作、不留状态：不筛点位、不改列表、不记录"当前选了哪一档"。
 * 用户缩放过之后再点同一个按钮，照样能回到该比例 —— 这也是它不能有选中态的原因。
 */
function zoomToRadius(km: number): void {
  const map = mapRef.value
  if (!map) {
    return
  }
  const here = me.value
  if (here) {
    map.setCenter([here.lng, here.lat])
  }
  map.setZoom(zoomForRadius(km))
}

async function signOut(): Promise<void> {
  moreOpen.value = false
  try {
    await logout()
  } catch {
    // 网络失败也照样回口令门：本地会话记忆清掉，服务端那条到期自然消失
  }
  markAnonymous()
  await router.replace('/gate')
}

watch(markerRows, () => {
  syncMarkers()
  ensureFit()
})

/**
 * 首屏取景：等「地图实例」和「定位」都就位后各做一次，以我为中心 + 默认半径的视野。
 * 两者谁先回来都可能（定位常有缓存，地图要等高德 JS 加载），所以两个都监听，只做一次。
 */
watch([me, mapRef], () => {
  const map = mapRef.value
  const here = me.value
  if (!map || !here || located) {
    return
  }
  located = true
  fitted = true
  map.setCenter([here.lng, here.lat])
  map.setZoom(zoomForRadius(DEFAULT_RADIUS))
})

// ---------------- 有效坐标 → 城市（逆地理编码） ----------------
let geocoder: AmapGeocoder | null = null
let cityTimer: number | undefined

/** Geocoder 是插件，用到时才加载；加载完缓存实例 */
function ensureGeocoder(ready: (g: AmapGeocoder | null) => void): void {
  const amap = window.AMap
  if (!amap) {
    ready(null)
    return
  }
  if (geocoder) {
    ready(geocoder)
    return
  }
  if (amap.Geocoder) {
    geocoder = new amap.Geocoder({ radius: 200 })
    ready(geocoder)
    return
  }
  if (typeof amap.plugin !== 'function') {
    ready(null)
    return
  }
  amap.plugin(['AMap.Geocoder'], () => {
    geocoder = amap.Geocoder ? new amap.Geocoder({ radius: 200 }) : null
    ready(geocoder)
  })
}

/** 有效坐标一动就反查城市（防抖 350ms）：列表只列这个城市的点 */
function scheduleCityLookup(): void {
  window.clearTimeout(cityTimer)
  const here = me.value
  if (!here) {
    meCity.value = ''
    return
  }
  cityTimer = window.setTimeout(() => {
    const point = me.value
    if (!point) {
      return
    }
    ensureGeocoder((g) => {
      const final = me.value
      if (!g || !final) {
        return
      }
      g.getAddress([final.lng, final.lat], (status, result) => {
        if (status !== 'complete') {
          return
        }
        const city = cityOfComponent(result.regeocode?.addressComponent)
        if (city) {
          meCity.value = city
        }
      })
    })
  }, 350)
}

// 有效坐标一变：挪蓝点（临时坐标是橙心）+ 反查城市
watch(me, () => {
  syncMeMarker()
  scheduleCityLookup()
})

// ---------------- 临时坐标 ----------------
function pickTemp(): void {
  moreOpen.value = false
  pickingTemp.value = true
}

/** 选完临时坐标：生效、把镜头带过去；城市先信选择器反查的结果，之后 scheduleCityLookup 会再校一遍 */
function applyTemp(value: { lat: number; lng: number; address: string; city: string }): void {
  tempLoc.value = { lng: value.lng, lat: value.lat }
  pickingTemp.value = false
  if (value.city) {
    meCity.value = value.city
  }
  mapRef.value?.setCenter([value.lng, value.lat])
  mapRef.value?.setZoom(15)
  ElMessage.success('临时坐标已生效：列表与距离都按它算，点「归位」还原')
}

function clearTemp(): void {
  if (!tempLoc.value) {
    return
  }
  tempLoc.value = null
  ElMessage.success('已还原为真实定位')
  recenter()
}

// ---------------- 列表加载更多 ----------------
const moreEl = ref<HTMLElement | null>(null)
let moreObserver: IntersectionObserver | null = null

// 哨兵元素进视野就续一页；root 是面板里的滚动容器，不传 root 会拿视口算、一挂载就全量放出来
watch(moreEl, (el) => {
  moreObserver?.disconnect()
  moreObserver = null
  if (!el) {
    return
  }
  const root = el.closest('.plist')
  moreObserver = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        loadMore()
      }
    },
    { root: root instanceof Element ? root : null, rootMargin: '160px' },
  )
  moreObserver.observe(el)
})

// 注意：切状态 / 等级**不再重新取景**（以前会 fitRows）。
// 标记现在只含视野内的点，筛完还在视野里，重新取景只会让地图莫名其妙缩放一下；
// 筛空时由抽屉的「没有符合条件的打卡点」兜住，不会让人误以为数据丢了。

// 一改搜索词就重新问服务端，并把下拉打开（选过一条后是收起的）。
// 清空关键字也要走一次：loadSearch 会清掉 searchSpots，标记才会切回视野结果。
watch(keyword, () => {
  suggestOpen.value = true
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    void loadSearch()
  }, 240)
})

// 定位城市变了就重取抽屉（服务端按城市筛）；反查没回来时 city 为空，退化成全量
watch(meCity, () => {
  scheduleListLoad()
})

// 定位明确失败：城市不会再来了，别让抽屉一直等那 1.2s
watch(geoIssue, () => {
  if (geoIssue.value && !meCity.value) {
    window.clearTimeout(listTimer)
    void loadList()
  }
})

onMounted(() => {
  recalcHeights()
  window.addEventListener('resize', recalcHeights)
  scheduleListLoad()
  void initMap()
  locate('auto')
  void watchGeoPermission()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', recalcHeights)
  window.clearTimeout(searchTimer)
  window.clearTimeout(cityTimer)
  window.clearTimeout(viewTimer)
  window.clearTimeout(listTimer)
  viewAbort?.abort()
  moreObserver?.disconnect()
  if (geoPermission) {
    geoPermission.onchange = null
    geoPermission = null
  }
  const map = mapRef.value
  if (map) {
    markerIndex.clear()
    meMarker = null
    map.destroy()
    mapRef.value = null
  }
})
</script>

<template>
  <div
    class="home"
    :class="{
      'home--panelup': panelState === 'half' || panelState === 'full',
      'home--dragging': dragging,
    }"
    :style="{ '--panel-h': `${panelHeight}px` }"
  >
    <div ref="mapEl" class="map" />

    <div class="topfade" />

    <!-- 定位拿不到时的主动引导：说清原因 + 开启步骤 + 重试 -->
    <div v-if="geoNoticeOpen" class="geohelp card" role="alertdialog" aria-live="polite">
      <div class="geohelp__head">
        <b>{{ geoHint.title }}</b>
        <button class="geohelp__close" type="button" aria-label="关闭提示" @click="geoNoticeOpen = false">
          ×
        </button>
      </div>
      <p>{{ geoHint.desc }}</p>
      <ul v-if="geoHint.steps.length" class="geohelp__steps">
        <li v-for="(step, index) in geoHint.steps" :key="index">{{ step }}</li>
      </ul>
      <div v-if="geoHint.retry" class="geohelp__acts">
        <button class="btn" type="button" @click="locate('manual')">重新定位</button>
      </div>
    </div>

    <header class="btop">
      <span class="btop__count">{{ markerRows.length }} 个点</span>
      <h1 class="btop__title">附近榜</h1>
      <div class="btop__acts">
        <button class="round" type="button" aria-label="搜索" @click="toggleSearch">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round">
            <circle cx="11" cy="11" r="6.6" />
            <path d="M16.2 16.2 20.5 20.5" />
          </svg>
        </button>
        <button class="round" type="button" aria-label="更多" @click="moreOpen = !moreOpen">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="6" cy="12" r="1.5" />
            <circle cx="12" cy="12" r="1.5" />
            <circle cx="18" cy="12" r="1.5" />
          </svg>
        </button>
      </div>
    </header>

    <form v-if="searchOpen" class="search" @submit.prevent>
      <input
        ref="searchEl"
        v-model="keyword"
        type="search"
        placeholder="搜名字或地址"
        @focus="suggestOpen = true"
        @keydown.enter.prevent="pickFirst"
        @keydown.esc="suggestOpen = false"
      />
      <button type="button" class="search__close" @click="toggleSearch">取消</button>
    </form>

    <!-- 搜索建议：顺序 = 匹配度为主、距离为辅（见 compareRows） -->
    <ul v-if="showSuggestPanel" class="sugs card">
      <li v-if="searching" class="sugs__hint">搜索中…</li>
      <li v-for="row in suggestions" :key="row.spot.id">
        <button type="button" @click="pickSuggestion(row)">
          <img
            v-if="row.spot.avatar"
            class="sugs__ph"
            :src="row.spot.avatar"
            :alt="row.spot.name"
            loading="lazy"
            decoding="async"
          />
          <span v-else class="sugs__ph sugs__ph--empty">{{ row.spot.name.slice(0, 1) }}</span>
          <span class="sugs__main">
            <b>{{ row.spot.name }}</b>
            <span>{{ rowMeta(row) }}</span>
          </span>
          <RankBar :level="row.spot.rankLevel" />
        </button>
      </li>
    </ul>

    <!-- 快捷缩放：竖排在左侧，避免跟顶部状态胶囊抢横向空间。
         只是「一键缩放到几公里」的动作，**没有选中态**（不筛点位、可以连点） -->
    <div class="radii" role="group" aria-label="快捷缩放">
      <button
        v-for="km in RADIUS_OPTIONS"
        :key="km"
        type="button"
        class="radius"
        @click="zoomToRadius(km)"
      >
        {{ km }}<i>km</i>
      </button>
    </div>

    <div v-if="!searchOpen" class="pills">
      <button
        v-for="pill in PILLS"
        :key="pill.value"
        type="button"
        class="pill"
        :class="{ 'pill--on': filter === pill.value }"
        @click="filter = pill.value"
      >
        {{ pill.label }}
      </button>
    </div>

    <div v-if="moreOpen" class="more card">
      <button type="button" @click="pickTemp">
        {{ tempLoc ? '换一个临时坐标' : '设一个临时坐标' }}
      </button>
      <RouterLink to="/debug" @click="moreOpen = false">环境自检</RouterLink>
      <button type="button" @click="signOut">退出登录</button>
    </div>

    <!-- 临时坐标生效中的常驻提示：这是它和真实定位「区分开」的关键，一按就还原 -->
    <button v-if="tempLoc" class="mockchip" type="button" @click="clearTemp">
      <i class="mockchip__dot" />
      <span>临时坐标生效中 · 列表与距离按它算 · 点此还原</span>
    </button>

    <!-- 视野查询的两条状态：失败给重试，截断给「放大看全部」。
         截断不提示等于静默丢点，这是 /spots/map 的必需语义 -->
    <p v-if="markerError" class="mapnote mapnote--warn" role="status">
      {{ markerError }}
      <button type="button" @click="loadViewport">重试</button>
    </p>
    <p v-else-if="viewTruncated && !query" class="mapnote" role="status">
      视野内点太多，已显示前 {{ viewLimit }} 个，放大可看全部
    </p>

    <div v-if="mapError" class="notice card">
      <b>地图没加载出来</b>
      <p>{{ mapError }}</p>
      <button type="button" @click="initMap">重试</button>
    </div>
    <div v-else-if="mapLoading" class="notice card notice--quiet">地图加载中…</div>

    <div class="rail">
      <button
        class="round round--lg"
        :class="{ 'round--warn': !!geoIssue }"
        type="button"
        aria-label="回到当前位置"
        @click="locate('manual')"
      >
        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
          <path d="M12 3.5v2.6M12 17.9v2.6M3.5 12h2.6M17.9 12h2.6" />
          <circle cx="12" cy="12" r="4.2" />
        </svg>
      </button>
    </div>

    <RouterLink class="fab" to="/spot/new" aria-label="新建打卡点">
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round">
        <path d="M12 5.5v13M5.5 12h13" />
      </svg>
    </RouterLink>

    <button v-if="panelState === 'collapsed'" class="reopen" type="button" @click="setPanel('peek')">
      附近 {{ cityRows.length }} 个 · 展开
    </button>

    <section
      class="panel"
      :class="{ 'panel--gone': panelState === 'collapsed' }"
      :data-h="panelState"
      :style="{ height: `${panelHeight}px` }"
    >
      <div
        class="grab"
        @pointerdown="onGrabDown"
        @pointermove="onGrabMove"
        @pointerup="onGrabUp"
        @pointercancel="onGrabUp"
        @click="onGrabClick"
      >
        <span />
      </div>

      <p v-if="loading" class="panel__state">加载中…</p>

      <div v-else-if="errorMessage" class="panel__state">
        <p>{{ errorMessage }}</p>
        <button class="btn" type="button" @click="loadList">重新加载</button>
      </div>

      <div v-else-if="listRows.length === 0" class="panel__state">
        <template v-if="markerRows.length > 0">
          <p>
            {{
              meCity
                ? `${meCity}没有符合条件的打卡点，其它城市的没有列进来`
                : '没有符合条件的打卡点'
            }}
          </p>
        </template>
        <template v-else>
          <p>{{ emptyText }}</p>
          <!-- 筛空了得给条退路：等级胶囊在列表区里，列表没了它就跟着没了 -->
          <button v-if="rankFilter.length > 0" class="btn" type="button" @click="rankFilter = []">
            清除等级筛选
          </button>
          <RouterLink class="btn" to="/spot/new">新建打卡点</RouterLink>
        </template>
      </div>

      <template v-else>
        <div class="listwrap">
          <div class="shead">
            <div class="shead__left">
              <b>附近 {{ cityRows.length }} 个</b>
              <span class="shead__hint">{{ meCity || '定位城市未知，先看全部' }}</span>
            </div>
            <button
              class="shead__all"
              type="button"
              @click="setPanel(panelState === 'full' ? 'half' : 'full')"
            >
              {{ panelState === 'full' ? '收起' : '展开' }}
            </button>
          </div>

          <!-- 等级筛选：多选，不选 = 不限。放在抽屉里而不是地图顶上，
               那里已经有一排状态胶囊，两排叠着会挤成一团 -->
          <div class="rfilter">
            <button
              type="button"
              class="rfilter__chip"
              :class="{ 'rfilter__chip--on': rankFilter.length === 0 }"
              @click="rankFilter = []"
            >
              不限等级
            </button>
            <button
              v-for="rank in RANKS"
              :key="rank.level"
              type="button"
              class="rfilter__chip"
              :class="{ 'rfilter__chip--on': rankFilter.includes(rank.level) }"
              @click="toggleRankFilter(rank.level)"
            >
              {{ rank.name }}
            </button>
          </div>

          <div class="plist">
            <SpotRow
              v-for="row in listRows"
              :key="row.spot.id"
              :spot="row.spot"
              :distance="row.distance"
            />

            <!-- 无限加载的哨兵：进视野续一页；点一下也能手动续 -->
            <div v-if="hasMoreRows" ref="moreEl" class="plist__more">
              <button type="button" @click="loadMore">
                加载更多（还有 {{ cityRows.length - listRows.length }} 个）
              </button>
            </div>
            <p v-else-if="listRows.length > 0" class="plist__end">就这些了</p>
          </div>
        </div>
      </template>
    </section>

    <MapPicker
      v-if="pickingTemp"
      :lat="me?.lat ?? null"
      :lng="me?.lng ?? null"
      @close="pickingTemp = false"
      @confirm="applyTemp"
    />

    <TabBar active="map" />
  </div>
</template>

<style scoped>
/* ---------------- 壳 ---------------- */
.home {
  position: relative;
  height: 100dvh;
  max-width: 720px;
  margin: 0 auto;
  overflow: hidden;
  background: var(--map-bg);
}

.map {
  position: absolute;
  inset: 0;
  z-index: 1;
}

/* 顶部白色渐隐：让标题与胶囊在地图上读得出来，同时不挡地图 */
.topfade {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  z-index: 5;
  height: 150px;
  background: linear-gradient(
    to bottom,
    rgba(255, 255, 255, 0.94) 0%,
    rgba(255, 255, 255, 0.72) 44%,
    rgba(255, 255, 255, 0) 100%
  );
  pointer-events: none;
}

/* ---------------- 顶部条 ---------------- */
.btop {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 14px);
  right: 12px;
  left: 12px;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 8px;
}

.btop__count {
  flex: none;
  color: var(--ink2);
  font-size: 11.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.btop__title {
  flex: 1;
  margin: 0;
  color: var(--ink);
  font-size: 16.5px;
  font-weight: 800;
  letter-spacing: -0.02em;
  text-align: center;
}

.btop__acts {
  display: flex;
  flex: none;
  gap: 7px;
}

.round {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  background: var(--card);
  box-shadow: 0 2px 8px -2px rgba(17, 24, 39, 0.18);
  color: var(--ink);
  cursor: pointer;
}

.round--lg {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  box-shadow: var(--sh);
}

/* ---------------- 筛选 / 搜索 ---------------- */
.pills {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 58px);
  right: 0;
  left: 0;
  z-index: 20;
  display: flex;
  gap: 8px;
  padding: 0 12px 2px;
  overflow-x: auto;
}

.pills::-webkit-scrollbar {
  height: 0;
}

.pill {
  flex: 0 0 auto;
  padding: 8px 14px;
  border: 0;
  border-radius: 999px;
  background: var(--card);
  box-shadow: 0 2px 8px -2px rgba(17, 24, 39, 0.16);
  color: var(--ink2);
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.pill--on {
  background: var(--ink);
  color: #fff;
  font-weight: 700;
}

.search {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 56px);
  right: 12px;
  left: 12px;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 6px 6px 12px;
  background: var(--card);
  border-radius: 999px;
  box-shadow: 0 2px 8px -2px rgba(17, 24, 39, 0.18);
}

.search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 500;
}

.search__close {
  flex: none;
  padding: 6px 10px;
  border: 0;
  border-radius: 999px;
  background: var(--bg);
  color: var(--ink2);
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

/* ---------------- 搜索建议 ---------------- */
.sugs {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 104px);
  right: 12px;
  left: 12px;
  z-index: 22;
  max-height: 52vh;
  padding: 4px;
  margin: 0;
  overflow-y: auto;
  list-style: none;
}

.sugs li + li {
  border-top: 1px solid var(--line);
}

.sugs li button {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 9px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--ink);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.sugs li button:active {
  background: var(--bg);
}

.sugs__ph {
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  object-fit: cover;
}

.sugs__ph--empty {
  display: grid;
  place-items: center;
  background: var(--bg);
  color: var(--ink3);
  font-size: 15px;
  font-weight: 800;
}

.sugs__main {
  flex: 1;
  min-width: 0;
}

.sugs__main b {
  display: block;
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 700;
  letter-spacing: -0.01em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sugs__main span {
  display: block;
  margin-top: 2px;
  color: var(--ink3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

/* ---------------- 半径快选（左侧竖排） ---------------- */
.radii {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 108px);
  left: 12px;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: 6px;
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.sugs__hint {
  padding: 12px 14px;
  color: var(--ink3);
  font-size: 12px;
}

.radius {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1px;
  width: 46px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: var(--card);
  box-shadow: 0 2px 8px -2px rgba(17, 24, 39, 0.16);
  color: var(--ink2);
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1;
  cursor: pointer;
}

.radius i {
  font-size: 9px;
  font-style: normal;
  font-weight: 600;
}

/* 按下时闪一下墨黑就复原：给「点到了」的反馈，但绝不留下选中态 */
.radius:active {
  background: var(--ink);
  box-shadow: 0 4px 12px -4px rgba(17, 24, 39, 0.45);
  color: #fff;
}

/* 更多菜单：白卡两行，点空白处靠再次点 ⋯ 收起 */
/* 临时坐标生效中的提示条：黑底白字 + 橙点，一眼和常态区分开，点按即还原 */
.mockchip {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 96px);
  left: 50%;
  z-index: 21;
  display: flex;
  gap: 6px;
  align-items: center;
  max-width: calc(100% - 24px);
  height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: 999px;
  background: var(--ink);
  color: #fff;
  font-family: inherit;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transform: translateX(-50%);
}

.mockchip span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mockchip__dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #ff5a1e;
}

.more {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 56px);
  right: 12px;
  z-index: 25;
  display: flex;
  flex-direction: column;
  min-width: 132px;
  padding: 4px;
  overflow: hidden;
}

.more a,
.more button {
  padding: 10px 12px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--ink);
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.more a:active,
.more button:active {
  background: var(--bg);
}

/* ---------------- 地图没加载出来 ---------------- */
.notice {
  position: absolute;
  top: 46%;
  left: 50%;
  z-index: 18;
  width: min(300px, calc(100% - 48px));
  padding: 16px;
  transform: translate(-50%, -50%);
  text-align: center;
}

.notice b {
  display: block;
  margin-bottom: 6px;
  font-size: 14px;
  font-weight: 700;
}

.notice p {
  margin: 0 0 12px;
  color: var(--ink2);
  font-size: 11.5px;
  line-height: 1.7;
  word-break: break-word;
}

.notice button {
  padding: 8px 16px;
  border: 0;
  border-radius: 9px;
  background: var(--ink);
  color: #fff;
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
}

.notice--quiet {
  color: var(--ink3);
  font-size: 12px;
}

/* ---------------- 视野查询状态（截断 / 失败） ---------------- */
/* 贴在面板上方：不与顶部搜索框、搜索建议、状态胶囊抢位置 */
.mapnote {
  position: absolute;
  left: 50%;
  bottom: calc(66px + var(--panel-h, 0px) + 10px);
  z-index: 11;
  max-width: calc(100% - 32px);
  margin: 0;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 2px 8px -2px rgba(17, 24, 39, 0.18);
  color: var(--ink2);
  font-size: 11.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transform: translateX(-50%);
}

.mapnote--warn {
  color: var(--hot);
}

.mapnote button {
  margin-left: 4px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--hot);
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
}

/* ---------------- 定位引导 ---------------- */
.geohelp {
  position: absolute;
  top: 42%;
  left: 50%;
  z-index: 30;
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: min(88vw, 360px);
  padding: 16px;
  transform: translate(-50%, -50%);
}

.geohelp__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.geohelp__head b {
  font-size: 14.5px;
  font-weight: 800;
  line-height: 1.35;
}

.geohelp__close {
  flex: none;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 50%;
  background: var(--bg);
  color: var(--ink2);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}

.geohelp p {
  margin: 0;
  color: var(--ink2);
  font-size: 12px;
  line-height: 1.7;
}

.geohelp__steps {
  margin: 0;
  padding-left: 18px;
  color: var(--ink2);
  font-size: 12px;
  line-height: 1.8;
}

.geohelp__acts {
  display: flex;
  gap: 8px;
}

.geohelp__acts .btn {
  flex: none;
  height: 38px;
  padding: 0 16px;
}

/* 定位异常：归位按钮右上角一个小橙点，关掉引导卡后也还能看出「有问题」 */
.round--warn {
  position: relative;
}

.round--warn::after {
  content: '';
  position: absolute;
  top: 5px;
  right: 5px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--hot);
  box-shadow: 0 0 0 2px var(--card);
}

/* ---------------- 右侧工具与新增 ---------------- */
.rail {
  position: absolute;
  right: 12px;
  bottom: calc(var(--panel-h) + 66px + 72px);
  z-index: 14;
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.fab {
  position: absolute;
  right: 12px;
  bottom: calc(var(--panel-h) + 66px + 16px);
  z-index: 14;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: var(--hot);
  box-shadow: 0 10px 22px -8px rgba(255, 90, 30, 0.7);
  transition: opacity 0.18s ease, transform 0.18s ease;
}

/* 面板升高后工具与 FAB 必须真正消失：只写 opacity:0 在截图里仍会残留 */
.home--panelup .rail,
.home--panelup .fab,
.home--panelup .radii {
  transform: scale(0.9);
  visibility: hidden;
  opacity: 0;
  pointer-events: none;
}

/* ---------------- 底部面板 ---------------- */
.panel {
  position: absolute;
  right: 0;
  bottom: 66px;
  left: 0;
  z-index: 12;
  display: flex;
  flex-direction: column;
  padding: 0 12px;
  overflow: hidden;
  border-radius: 16px 16px 0 0;
  background: var(--bg);
  box-shadow: 0 -10px 30px -22px rgba(17, 24, 39, 0.55);
  transition: height 0.28s cubic-bezier(0.22, 0.61, 0.36, 1);
}

/* 收到 0 时连投影一起收掉，否则底部会留一灰线；不要加 visibility:hidden ——
   那会让 280ms 的收起动画直接跳变（类名在高度归零的瞬间就生效了） */
.panel--gone {
  box-shadow: none;
}

/* 完全收起后唯一的重开入口：没有它，面板就再也拉不上来了 */
.reopen {
  position: absolute;
  /* TabBar 总高就是 66px（安全区内边距算在它自己里面），这里跟 FAB 用同一套基准 */
  bottom: calc(66px + 14px);
  left: 12px;
  z-index: 14;
  height: 40px;
  padding: 0 16px;
  border: 0;
  border-radius: 999px;
  background: var(--card);
  box-shadow: 0 8px 20px -10px rgba(17, 24, 39, 0.5), 0 1px 3px rgba(17, 24, 39, 0.08);
  color: var(--ink);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
}

/* 拖拽中不要过渡，否则跟手会有橡皮筋感 */
.home--dragging .panel {
  transition: none;
}

.grab {
  display: flex;
  justify-content: center;
  padding: 8px 0 6px;
  cursor: grab;
  touch-action: none;
}

.grab span {
  display: block;
  width: 34px;
  height: 4px;
  border-radius: 2px;
  background: #d6dae0;
}

.panel__state {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  justify-content: center;
  padding: 24px 12px 32px;
  margin: 0;
  color: var(--ink3);
  font-size: 12.5px;
  text-align: center;
}

.panel__state p {
  margin: 0;
  max-width: 260px;
  line-height: 1.7;
  word-break: break-word;
}

.panel__state .btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 44px;
  padding: 0 20px;
  text-decoration: none;
}

/* 列表 */
.listwrap {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.shead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 2px 9px;
}

.shead__left {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.shead b {
  font-size: 16px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.shead__hint {
  color: var(--ink3);
  font-size: 11px;
  font-weight: 500;
}

.shead__all {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 5px 10px;
  border: 0;
  border-radius: 999px;
  background: var(--card);
  box-shadow: 0 1px 3px rgba(17, 24, 39, 0.1);
  color: var(--ink2);
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

/* 等级筛选：横向滚动的一排小胶囊。
   选中不用段位色块（AGENTS.md 第 22 条：段位只走 7 段刻度条），
   沿用状态胶囊的「墨黑 = 选中」语言 */
.rfilter {
  display: flex;
  gap: 6px;
  margin: 0 -2px 9px;
  padding: 0 2px 2px;
  overflow-x: auto;
}

.rfilter::-webkit-scrollbar {
  height: 0;
}

.rfilter__chip {
  flex: 0 0 auto;
  padding: 5px 10px;
  border: 0;
  border-radius: 999px;
  background: var(--card);
  box-shadow: 0 1px 3px rgba(17, 24, 39, 0.1);
  color: var(--ink2);
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
}

.rfilter__chip--on {
  background: var(--ink);
  box-shadow: none;
  color: #fff;
}

.plist {
  flex: 1;
  padding-bottom: 10px;
  overflow-y: auto;
}

.plist::-webkit-scrollbar {
  width: 0;
}

/* 加载更多：哨兵进视野自动续一页，点一下也能手动续 */
.plist__more {
  display: flex;
  justify-content: center;
  padding: 8px 0 2px;
}

.plist__more button {
  padding: 8px 16px;
  border: 0;
  border-radius: 999px;
  background: var(--bg);
  color: var(--ink2);
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.plist__end {
  margin: 0;
  padding: 12px 0 2px;
  color: var(--ink3);
  font-size: 11px;
  text-align: center;
}

</style>
