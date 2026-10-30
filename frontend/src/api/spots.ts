import { http } from './http'

/**
 * 打卡点接口封装。
 *
 * 类型上注意两点：
 *   1) 后端开了 {@code default-property-inclusion: non_null}，null 字段不会出现在 JSON 里，
 *      所以除主键与枚举外一律写成可选（`?:`），不要假设字段一定存在
 *   2) 图片字段（avatar / cover）是后端拼好的完整 URL，直接塞进 &lt;img :src&gt;，前端不拼前缀
 */

export type SpotKind = 'place' | 'person'
/** 与 db/schema.sql 的 spot.status ENUM 一一对应；名称/配色见 config/status.ts */
export type SpotStatus = 'unvisited' | 'visited' | 'wishlist' | 'blacklist'

export interface TagBrief {
  id: string
  name: string
  /** 标签颜色 #RRGGBB，可能为 null（老标签没选过） */
  color?: string | null
}

/**
 * 联系方式类型，与后端 `SpotService.CONTACT_TYPES` 一一对应；
 * 展示名 / 图标 / 输入提示 / 点击行为见 `config/contacts.ts`（唯一来源，别在页面里硬编码）。
 */
export type SpotContactType = 'phone' | 'wechat' | 'qq' | 'telegram' | 'youyu'

/**
 * 一条联系方式。整体提交、顺序即展示顺序；同类型可以多条（两个手机号）。
 *
 * 以前是 spot 上的 contactQq / contactWechat 四个固定列，一列只能一个值，
 * 也没法放电话，所以拆成了独立表 + 数组。
 */
export interface SpotContact {
  type: SpotContactType
  value: string
}

/** 一张图片引用（打卡点照片用），URL 已是可直接用的完整地址 */
export interface PhotoRef {
  id: string
  large: string
  thumb: string
  width: number
  height: number
  size: number
}

export interface PackageItem {
  name: string
  /** 元；null / 缺省表示「有这个项目但价格不写」 */
  price?: number | null
}

export interface SpotChild {
  id: string
  name: string
  avatar?: string
  rankLevel: number
}

export interface SpotListItem {
  id: string
  kind: SpotKind
  name: string
  avatar?: string
  rankLevel: number
  status: SpotStatus
  lat?: number
  lng?: number
  /** 城市，可能为空：地图页用它做「只看定位所在城市」的本地过滤 */
  city?: string
  /** 省 / 直辖市、区 / 县：列表页按省市区筛选 */
  province?: string
  district?: string
  /** 价目里的最低 / 最高价（元）：列表页按价格范围筛选，null 表示没填过价 */
  priceMin?: number
  priceMax?: number
  /** 平均星级，没有到访记录时不存在 */
  avgRating?: number
  tags?: TagBrief[]
}

export interface SpotDetail {
  id: string
  kind: SpotKind
  name: string
  avatar?: string
  cover?: string
  /**
   * true = 这个头像/封面是「没设，取第一张照片」兜底出来的，不是用户设定。
   * 整体提交时要把它们当空值传，否则存一次就把第一张照片钉死成头像/封面。
   */
  avatarFromPhotos?: boolean
  coverFromPhotos?: boolean
  description?: string
  lat?: number
  lng?: number
  address?: string
  city?: string
  province?: string
  district?: string
  /** 联系方式，按用户添加顺序；同类型可能多条（两个手机号） */
  contacts?: SpotContact[]
  photos?: PhotoRef[]
  packages?: PackageItem[]
  rankLevel: number
  rankManual: boolean
  /**
   * 按历史到访算出的建议段位（PRD 5.1）。0 次到访时不存在。
   * 与 rankManual 无关：手动锁过也会给值，由前端决定要不要露出「建议」入口。
   */
  suggestedRankLevel?: number
  parentSpotId?: string
  parentSpotName?: string
  children?: SpotChild[]
  status: SpotStatus
  tags?: TagBrief[]
  visitCount: number
  avgRating?: number
  lastVisitAt?: string
  createdAt?: string
  updatedAt?: string
}

/** 整体提交：字段传 null / 空就是清空，不是「不改」 */
export interface SpotSaveRequest {
  name: string
  kind: SpotKind
  status: SpotStatus
  rankLevel: number
  rankManual: boolean
  avatar?: string | null
  cover?: string | null
  description?: string | null
  lat?: number | null
  lng?: number | null
  address?: string | null
  city?: string | null
  province?: string | null
  district?: string | null
  /** 联系方式整体提交：传 [] 等于清空，不是「不改」（删掉最后一个电话必须能生效） */
  contacts?: SpotContact[]
  photos?: PhotoRef[]
  packages?: PackageItem[]
  parentSpotId?: string | null
  tagIds?: string[]
}

export interface SpotListQuery {
  status?: SpotStatus
  kind?: SpotKind
  tagId?: string
  /** 只列这个城市的点（地图抽屉用）；后端会兼容「武汉 / 武汉市」两种写法 */
  city?: string
  /** 名称或地址模糊匹配 */
  keyword?: string
}

export function listSpots(query: SpotListQuery = {}): Promise<SpotListItem[]> {
  const params = new URLSearchParams()
  if (query.status) params.set('status', query.status)
  if (query.kind) params.set('kind', query.kind)
  if (query.tagId) params.set('tagId', query.tagId)
  if (query.city) params.set('city', query.city)
  if (query.keyword) params.set('q', query.keyword)
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return http.get<SpotListItem[]>(`/spots${suffix}`)
}

/**
 * 地图标记行：后端 `/api/spots/map` 的返回。
 *
 * 字段比 {@link SpotListItem} 少（没有 tags / 价格 / 省市区），因为一次要吞几百个点。
 * 结构上是 `SpotListItem` 的子集，可以直接当列表项用（头像、名字、状态、段位都够画标记）。
 */
export interface SpotMarkerItem {
  id: string
  kind: SpotKind
  name: string
  avatar?: string
  status: SpotStatus
  rankLevel: number
  lat: number
  lng: number
  /** 平均星级；没有到访记录时不存在 */
  avgRating?: number
}

export interface SpotMapPage {
  items: SpotMarkerItem[]
  /** 点太多被 limit 截断：界面必须提示「已显示前 N 个」 */
  truncated: boolean
  /** 本次生效的条数上限，提示文案用它，避免前后端各写一个数字 */
  limit: number
}

/** 视野矩形（GCJ-02，与库里的 lat/lng 同一坐标系） */
export interface SpotBounds {
  minLng: number
  minLat: number
  maxLng: number
  maxLat: number
}

/**
 * 按视野矩形取标记。
 *
 * 这是地图页唯一的标记数据源：拖动 / 缩放后重新查，视野外的点不取。
 * `signal` 用来取消「还没回来就已经被下一次拖动取代」的请求。
 */
export function mapSpots(
  bounds: SpotBounds,
  options: { limit?: number; signal?: AbortSignal } = {},
): Promise<SpotMapPage> {
  const params = new URLSearchParams({
    minLng: String(bounds.minLng),
    minLat: String(bounds.minLat),
    maxLng: String(bounds.maxLng),
    maxLat: String(bounds.maxLat),
    limit: String(options.limit ?? 300),
  })
  return http.get<SpotMapPage>(`/spots/map?${params.toString()}`, {
    signal: options.signal,
  })
}

/**
 * 列表页分页查询条件（全部在服务端筛，前端只拿当前页）。
 *
 * 以前这些条件是拿到全量后本地筛的，数据一多就慢；改成「筛完再切页」后，
 * 条件必须随请求发出去，否则第 2 页会把第 1 页筛掉的行捡回来。
 */
export interface SpotPageQuery {
  page?: number
  size?: number
  /** 状态多选，任一命中 */
  statuses?: SpotStatus[]
  /** 段位多选 0~6，任一命中 */
  rankLevels?: number[]
  /** 标签多选，任一命中 */
  tagIds?: string[]
  province?: string
  city?: string
  district?: string
  /** 价格区间（元）：与点位的价位跨度求重叠，只给下限或只给上限都行 */
  priceMin?: number
  priceMax?: number
  /** 名称或地址模糊匹配 */
  keyword?: string
  /**
   * 联系方式模糊匹配（电话 / 微信 / QQ / TG / 与你，跨类型搜）。
   * 电话可以只敲数字：库里存成 138-0013-8000 也能命中，剥分隔符在服务端做。
   */
  contact?: string
}

export interface SpotPage {
  items: SpotListItem[]
  /** 命中条件的总条数（不是当前页条数） */
  total: number
  page: number
  size: number
  /** 有没有下一页：由服务端算，前端不要自己拿 page * size 推 */
  hasMore: boolean
}

/** 省 / 市 / 区某个组合（筛选下拉的选项来源），任一段可能为空 */
export interface SpotRegion {
  province?: string
  city?: string
  district?: string
}

export function pageSpots(query: SpotPageQuery = {}): Promise<SpotPage> {
  const params = new URLSearchParams()
  params.set('page', String(query.page ?? 1))
  params.set('size', String(query.size ?? 20))
  // 多选一律重复键传：Spring 端 List<String> 直接收，逗号分隔也支持
  for (const status of query.statuses ?? []) {
    params.append('status', status)
  }
  for (const level of query.rankLevels ?? []) {
    params.append('rank', String(level))
  }
  for (const id of query.tagIds ?? []) {
    params.append('tagId', id)
  }
  if (query.province) params.set('province', query.province)
  if (query.city) params.set('city', query.city)
  if (query.district) params.set('district', query.district)
  if (query.priceMin != null) params.set('priceMin', String(query.priceMin))
  if (query.priceMax != null) params.set('priceMax', String(query.priceMax))
  if (query.keyword) params.set('q', query.keyword)
  if (query.contact) params.set('contact', query.contact)
  return http.get<SpotPage>(`/spots/page?${params.toString()}`)
}

export function listSpotRegions(): Promise<SpotRegion[]> {
  return http.get<SpotRegion[]>('/spots/regions')
}

export function getSpot(id: string): Promise<SpotDetail> {
  return http.get<SpotDetail>(`/spots/${id}`)
}

export function createSpot(payload: SpotSaveRequest): Promise<{ id: string }> {
  return http.post<{ id: string }>('/spots', payload)
}

export function updateSpot(id: string, payload: SpotSaveRequest): Promise<void> {
  return http.put<void>(`/spots/${id}`, payload)
}

export function deleteSpot(id: string): Promise<void> {
  return http.delete<void>(`/spots/${id}`)
}

/** 一条「可能重复」的联系方式：我填的这条和别人家的同类型撞了 */
export interface ContactDuplicate {
  type: SpotContactType
  /** 我表单里的值（原样展示） */
  value: string
  /** 撞上的打卡点，用于「跳转」 */
  otherSpotId: string
  /** 撞上的打卡点名，展示成蓝色可点 */
  otherSpotName: string
}

/**
 * 联系方式查重（保存前 / 手动点「查重」都走它）。
 *
 * 传的是**表单当前值**，不是库里已存的：改完还没保存也要能看见冲突。
 * `spotId` 传 null 表示新建（没有「自己」要排除）。
 * 后端只比同类型、电话剥分隔符与 +86、其余忽略大小写。
 */
export function checkContactDuplicates(
  spotId: string | null,
  contacts: SpotContact[],
): Promise<ContactDuplicate[]> {
  return http.post<ContactDuplicate[]>('/spots/contacts/check', { spotId, contacts })
}
