<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import SegmentedControl from '@/components/SegmentedControl.vue'
import RankPicker from '@/components/RankPicker.vue'
import MapPicker from '@/components/MapPicker.vue'
import SafeImage from '@/components/SafeImage.vue'
import ContactDuplicateDialog from '@/components/ContactDuplicateDialog.vue'
import {
  checkContactDuplicates,
  createSpot,
  deleteSpot,
  getSpot,
  listSpots,
  updateSpot,
  type ContactDuplicate,
  type PackageItem,
  type PhotoRef,
  type SpotContact,
  type SpotContactType,
  type SpotKind,
  type SpotListItem,
  type SpotSaveRequest,
  type SpotStatus,
} from '@/api/spots'
import { CONTACT_TYPES, contactMeta } from '@/config/contacts'
import { listTags, type TagItem } from '@/api/tags'
import { leaveSpotPage } from '@/router/spotEntry'
import { resolveTagColor } from '@/utils/tagColor'
import { groupTagsByCategory } from '@/config/tagCategories'
import { useTagCategories } from '@/composables/useTagCategories'
import { uploadImage } from '@/api/upload'
import { compressImage } from '@/utils/image'
import { HttpError } from '@/api/http'
import { DEFAULT_STATUS, SPOT_STATUSES } from '@/config/status'
import { PROVINCES, citiesOf, districtsOf, resolveRegion, stripRegionPrefix } from '@/config/regions'
import { useFieldFeedback } from '@/composables/useFieldFeedback'

/**
 * 新建 / 编辑打卡点（同一个页面，靠路由区分）。
 *
 * 形态照 PRD 5.7「表单控件」：白卡分组、54px 输入行（左标签灰、右值黑右对齐）、
 * 分段控件、标签 chip、段位阶梯选择器。**不要用 el-form 拼** —— 那会立刻变成后台模板。
 *
 * 尚未接入的部分（等 ③ 地图主页）：从地图选点带入坐标。现在只能手填经纬度。
 */
const route = useRoute()
const router = useRouter()

const spotId = computed(() => (route.name === 'spot-edit' ? String(route.params.id) : null))
const isEdit = computed(() => spotId.value !== null)

const loading = ref(true)
const saving = ref(false)
const uploading = ref<'' | 'avatar' | 'cover' | 'photos'>('')
const pickerOpen = ref(false)

/**
 * 联系方式查重。
 *
 * `dupConfirmed` 是「用户在弹窗里点了仍然保存」的放行标记：
 * 保存流程要走两遍（第一遍查重被拦下 → 第二遍跳过检查真正提交），
 * 否则点「仍然保存」会再次弹同一个框，变成死循环。
 */
const dupOpen = ref(false)
const dupList = ref<ContactDuplicate[]>([])
const dupMode = ref<'check' | 'save'>('check')
const dupConfirmed = ref(false)
const checkingDup = ref(false)
const nameInput = ref<HTMLInputElement | null>(null)
/** 名称是唯一必填项：出错时轻晃 + 红框 + 下方滑入提示 */
const {
  fail: markFieldError,
  clear: clearFieldError,
  stopShake: stopFieldShake,
  has: fieldHasError,
  messageOf: fieldError,
  isShaking: fieldShaking,
} = useFieldFeedback<'name'>()
const tags = ref<TagItem[]>([])
/** 分类清单来自后端（用户能在标签管理页增删类型），组顺序 = 分类表 sort_order */
const { categories, ensure: ensureCategories } = useTagCategories()
/** 编辑页标签按分类分组；组内顺序 = 后端 tag.sort_order（与标签管理页一致） */
const tagGroups = computed(() => groupTagsByCategory(tags.value, categories.value))
const places = ref<SpotListItem[]>([])

/** PRD 5.6：一个打卡点最多挂 8 个标签 */
const MAX_TAGS = 8

/** 编辑时记住原始段位，用来判断「用户有没有真的改过」 */
const originalRankLevel = ref<number | null>(null)
const originalRankManual = ref(false)
const visitSummary = ref('')

/** 经纬度都填了才算「已选点」，只填一个不能拿去做标记 */
const hasCoords = computed(() => parseNumber(form.lat) !== null && parseNumber(form.lng) !== null)

const form = reactive({
  name: '',
  /**
   * 头像 / 封面存的是后端给的完整 URL（本地是 `/uploads/...` 相对路径），
   * 保存时原样回传 —— 后端认得出「以 / 开头的相对路径」，会照旧落库成相对路径。
   */
  avatar: '',
  cover: '',
  kind: 'place' as SpotKind,
  /** 新建默认「未打卡」：先记下来，去没去过另说（可改） */
  status: DEFAULT_STATUS as SpotStatus,
  rankLevel: 3,
  address: '',
  city: '',
  /**
   * 用字符串承接输入，提交时再转数字：空串要能表达「没填」。
   *
   * 但 `<input type="number">` 上的 v-model 会被 Vue 隐式转成 number
   * （runtime-dom 的 vModelText 对 type="number" 自动加了 .number），
   * 所以运行期实际可能是 string 也可能是 number。类型必须如实写成联合，
   * 解析函数也必须两种都收 —— 只当字符串处理会在用户一改数字框时
   * 于 `.trim()` 上抛 TypeError，表现为「保存点了没反应 / 操作失败」。
   */
  lat: '' as string | number,
  lng: '' as string | number,
  province: '',
  district: '',
  photos: [] as PhotoRef[],
  /**
   * 联系方式：不再固定四个输入框，而是「加一行 = 一条」的数组。
   * 同类型可以多条（两个手机号），顺序就是详情页的展示顺序。
   * 空行允许存在（用户点了 + 还没填），提交时过滤掉。
   */
  contacts: [] as ContactDraft[],
  description: '',
  parentSpotId: '',
  tagIds: [] as string[],
  /** price 同上：数字框会被 Vue 转成 number，不能只声明成 string */
  packages: [] as { name: string; price: string | number }[],
})

const kindOptions = [
  { value: 'place', label: '场所' },
  { value: 'person', label: '人物' },
] as const

/**
 * 状态选项带自己的底色（config/status.ts 是唯一来源）：
 * 四个状态切换后如果都是墨黑，用户根本看不出切到哪一档了。
 */
const statusOptions = SPOT_STATUSES.map((item) => ({
  value: item.value,
  label: item.label,
  background: item.background,
  foreground: item.foreground,
}))

function messageOf(error: unknown): string {
  return error instanceof HttpError ? error.message : '操作失败，请重试'
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

/**
 * 数字框的取值统一走这里：入参可能是 string（手填 / 后端回填）也可能是
 * number（Vue 对 type="number" 的 v-model 自动转换），不要直接 `.trim()`。
 */
function parseNumber(value: string | number): number | null {
  const raw = typeof value === 'number' ? value : value.trim()
  if (raw === '') return null
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? parsed : null
}

/** 价格只收正整数（元）：老数据里带小数的按四舍五入显示，用户在框里看得见改得动 */
function formatPrice(price: number | null | undefined): string {
  return price === null || price === undefined ? '' : String(Math.round(price))
}

/**
 * 价格框只允许正整数。
 *
 * 不用 `type="number"`：那个在中文输入法与移动端仍能塞进小数点和 e，
 * 而后端会把非整数直接拒掉（SpotService.normalizePackages）。
 * 这里两道关：beforeinput 拦住非数字按键，input 兜住粘贴/输入法的情况。
 */
function blockNonDigit(event: InputEvent): void {
  if (event.data && /\D/.test(event.data)) {
    event.preventDefault()
  }
}

function onPriceInput(item: { price: string | number }, event: Event): void {
  const el = event.target as HTMLInputElement
  // 粘贴 88.5 这种：小数点之后的部分不要（不是把点去掉拼成 885）
  const dot = el.value.search(/[.,]/)
  const digits = (dot >= 0 ? el.value.slice(0, dot) : el.value).replace(/\D/g, '').slice(0, 6)
  if (el.value !== digits) {
    el.value = digits
  }
  item.price = digits
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [tagList, placeList] = await Promise.all([
      listTags(),
      listSpots({ kind: 'place' }),
      // 分类只影响分组顺序，失败时用兜底清单，不挡页面
      ensureCategories(),
    ])
    tags.value = tagList
    // 编辑人物时不能把自己挂到自己名下
    places.value = placeList.filter((place) => place.id !== spotId.value)

    if (spotId.value) {
      const spot = await getSpot(spotId.value)
      form.name = spot.name
      // 后端返回的是可直接用的完整地址；保存时后端会还原成相对路径再落库。
      // *FromPhotos = 那是「第一张照片」兜底出来的，不当成用户设定 ——
      // 否则打开编辑页什么都不改、点一次保存，就把第一张照片钉死成头像/封面了。
      form.avatar = spot.avatarFromPhotos ? '' : spot.avatar ?? ''
      form.cover = spot.coverFromPhotos ? '' : spot.cover ?? ''
      form.kind = spot.kind
      form.status = spot.status
      form.rankLevel = spot.rankLevel
      form.address = spot.address ?? ''
      form.city = spot.city ?? ''
      form.province = spot.province ?? ''
      form.district = spot.district ?? ''
      // 两级下拉的选项跟着已有值走（纯查表）
      syncRegionOptions()
      // 老数据的地址里带着「省市区」前缀（逆地理编码原样存下来的），而省市区现在是独立字段：
      // 打开编辑页先剥掉，地址行只留「xxx路xxx号」。不点保存就不会落库。
      syncAddressRegion()
      form.photos = spot.photos ?? []
      form.lat = spot.lat === undefined ? '' : String(spot.lat)
      form.lng = spot.lng === undefined ? '' : String(spot.lng)
      form.contacts = (spot.contacts ?? []).map((item) => newContactRow(item.type, item.value))
      form.description = spot.description ?? ''
      form.parentSpotId = spot.parentSpotId ?? ''
      form.tagIds = (spot.tags ?? []).map((tag) => tag.id)
      form.packages = (spot.packages ?? []).map((item: PackageItem) => ({
        name: item.name,
        price: formatPrice(item.price),
      }))

      originalRankLevel.value = spot.rankLevel
      originalRankManual.value = spot.rankManual
      visitSummary.value =
        spot.visitCount > 0
          ? `已到访 ${spot.visitCount} 次 · 均分 ${spot.avgRating?.toFixed(1) ?? '—'}`
          : '还没有到访记录'
    }
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    loading.value = false
  }
}

/**
 * 点上 / 取消一个标签（平铺多选）。
 *
 * 顺序按点击先后 —— 提交给后端的 `tagIds` 就是展示顺序。
 * 选满 8 个后再点未选中的：给提示而不是静默无反应（静默会让人以为点错了）。
 */
function toggleTag(id: string): void {
  const index = form.tagIds.indexOf(id)
  if (index >= 0) {
    form.tagIds.splice(index, 1)
    return
  }
  if (form.tagIds.length >= MAX_TAGS) {
    ElMessage.warning(`最多挂 ${MAX_TAGS} 个标签，先取消一个再选`)
    return
  }
  form.tagIds.push(id)
}

function addPackage(): void {
  form.packages.push({ name: '', price: '' })
}

function removePackage(index: number): void {
  form.packages.splice(index, 1)
}

/* ---------------- 联系方式：大类（可折叠）→ 类下多条 ---------------- */

/**
 * 表单里的联系方式是**扁平数组**，展示时按 type 归组：
 * 大类 = 一个可折叠分组（电话 / 微信 / QQ / Telegram / 与你），组内可以多条。
 *
 * `uid` 只给前端用（v-for 的 key 与聚焦）：同类型两条值相同时，
 * 拿数组下标当 key 会在删除中间一条时把输入框内容串到隔壁行。
 */
interface ContactDraft {
  uid: number
  type: SpotContactType
  value: string
}

let contactUid = 0
function newContactRow(type: SpotContactType, value = ''): ContactDraft {
  contactUid += 1
  return { uid: contactUid, type, value }
}

/** 折起来的大类（默认全展开，记的是 type → 是否折叠） */
const collapsedTypes = ref<Record<string, boolean>>({})
/** 顶部「新增大类」的类型选择面板 */
const groupPickerOpen = ref(false)

const contactGroups = computed(() => {
  const byType = new Map<SpotContactType, ContactDraft[]>()
  for (const row of form.contacts) {
    const list = byType.get(row.type)
    if (list) {
      list.push(row)
    } else {
      byType.set(row.type, [row])
    }
  }
  // 顺序跟 CONTACT_TYPES 走（电话在最前）；后端先加了新类型时追加到末尾，不让整块消失
  const groups = CONTACT_TYPES.filter((meta) => byType.has(meta.value)).map((meta) => ({
    type: meta.value,
    meta,
    rows: byType.get(meta.value) as ContactDraft[],
  }))
  for (const [type, rows] of byType) {
    if (!CONTACT_TYPES.some((meta) => meta.value === type)) {
      groups.push({ type, meta: contactMeta(type), rows })
    }
  }
  return groups
})

/** 还没加过的大类：「新增大类」只列这些，同一类不会出现两个分组 */
const unusedTypes = computed(() =>
  CONTACT_TYPES.filter((meta) => !form.contacts.some((row) => row.type === meta.value)),
)

function isCollapsed(type: string): boolean {
  return collapsedTypes.value[type] === true
}

function toggleGroup(type: string): void {
  collapsedTypes.value[type] = !isCollapsed(type)
}

/** 「新增大类」：建一个空分组（先放一条空行），自动展开并聚焦 */
function addGroup(type: SpotContactType): void {
  groupPickerOpen.value = false
  collapsedTypes.value[type] = false
  const row = newContactRow(type)
  form.contacts.push(row)
  focusDraft(row.uid)
}

/** 「在 XX 下新增一条」：同类型再来一行 */
function addRowToGroup(type: SpotContactType): void {
  collapsedTypes.value[type] = false
  const row = newContactRow(type)
  form.contacts.push(row)
  focusDraft(row.uid)
}

function removeContact(row: ContactDraft): void {
  const index = form.contacts.indexOf(row)
  if (index >= 0) {
    form.contacts.splice(index, 1)
  }
}

/** 新行刚插入，必须等 DOM 出来才聚焦 */
function focusDraft(uid: number): void {
  void nextTick(() => {
    document.getElementById(`ct-input-${uid}`)?.focus()
  })
}

/* ---- 省 / 市 / 区：三档下拉（内置静态数据；三个都非必填，空着就是没填） ---- */
const cityOptions = ref<string[]>([])
const districtOptions = ref<string[]>([])

/**
 * 三个下拉各自的搜索词。
 *
 * 为什么不用 el-select 的 `filterable`：那会把 trigger 本身变成一个真输入框，
 * 真机上一碰就弹键盘 —— 而这里绝大多数操作只是滑动挑选，弹键盘非常打扰。
 * 改成「先出全量列表，想搜时再点列表顶部的搜索框」，键盘只在明确要搜时才出现。
 */
const regionFilter = reactive({ province: '', city: '', district: '' })

function filterRegion(list: string[], keyword: string): string[] {
  const key = keyword.trim()
  return key ? list.filter((name) => name.includes(key)) : list
}

const filteredProvinces = computed(() => filterRegion(PROVINCES, regionFilter.province))
const filteredCities = computed(() => filterRegion(cityOptions.value, regionFilter.city))
const filteredDistricts = computed(() => filterRegion(districtOptions.value, regionFilter.district))

/** 每次展开都把搜索词清掉：上次筛了一半的列表再打开，会让人以为数据没了 */
function onRegionOpen(level: 'province' | 'city' | 'district', open: boolean): void {
  if (open) {
    regionFilter[level] = ''
  }
}

/** 省变了：市 / 区一律清空 —— 上一级的旧值留着就是错的数据 */
function onProvinceChange(value: string): void {
  form.city = ''
  form.district = ''
  regionFilter.city = ''
  regionFilter.district = ''
  cityOptions.value = value ? citiesOf(value) : []
  districtOptions.value = []
  // 直辖市只有「市」这一级（市名就是省名），直接补上，别让人对着空的市下拉发愣
  if (cityOptions.value.length === 1 && cityOptions.value[0] === value) {
    form.city = value
    districtOptions.value = districtsOf(value, value)
  }
  syncAddressRegion()
}

function onCityChange(value: string): void {
  form.district = ''
  regionFilter.district = ''
  districtOptions.value = value ? districtsOf(form.province, value) : []
  syncAddressRegion()
}

/** 编辑既有数据 / 地图选点之后，把两级下拉的选项跟当前值对齐（纯查表，不发请求） */
function syncRegionOptions(): void {
  cityOptions.value = form.province ? citiesOf(form.province) : []
  districtOptions.value = form.city ? districtsOf(form.province, form.city) : []
}

/**
 * 地址行只留「xxx路xxx号」：省市区既然单独存了，地址里再带一遍就是重复。
 * 地图选点反查回来的是完整地址，所以选完省市区也要跟着剥一次前缀。
 */
function syncAddressRegion(): void {
  const next = stripRegionPrefix(form.address, form.province, form.city, form.district)
  if (next !== form.address) {
    form.address = next
  }
}

interface PickedLocation {
  lat: number
  lng: number
  address: string
  city: string
  province: string
  district: string
}

/**
 * 地图选点的回填：坐标必填，地址/城市/省市区取反查结果（反查失败就保留原值）。
 * 仍然要点「保存」才落库，跟其它字段一致。
 */
function applyPicked(value: PickedLocation): void {
  form.lat = String(value.lat)
  form.lng = String(value.lng)
  // 反查给的名字未必跟内置数据一字不差（「武汉」对「武汉市」），先归一化再落表单；
  // 对不上的（表里没有的「某某新区」）原样保留，别把位置信息丢了
  const region = resolveRegion({
    province: value.province,
    city: value.city,
    district: value.district,
  })
  if (region.province) {
    form.province = region.province
  }
  if (region.city) {
    form.city = region.city
  }
  if (region.district) {
    form.district = region.district
  }
  if (value.address) {
    form.address = value.address
  }
  syncRegionOptions()
  // 详细地址不带省市区：反查回来的是完整地址，剥掉前缀只留「xxx路xxx号」
  syncAddressRegion()
  pickerOpen.value = false
  ElMessage.success('位置已带入，记得点保存')
}

/**
 * 表单里的联系方式 → 提交/查重用的数组。
 *
 * 去掉空行（点了「新增大类」还没填值），其余保持用户添加顺序 ——
 * 顺序就是详情页的展示顺序，查重弹窗里也按这个顺序列冲突。
 */
function contactPayload(): SpotContact[] {
  return form.contacts
    .map((item) => ({ type: item.type, value: item.value.trim() }))
    .filter((item): item is SpotContact => item.value.length > 0)
}

function buildPayload(): SpotSaveRequest {
  const packages: PackageItem[] = form.packages
    .map((row) => ({ name: row.name.trim(), price: parseNumber(row.price) }))
    .filter((row) => row.name.length > 0)

  // 新建即是手动定级；编辑时只要用户动过段位，也算手动定过，系统不再给建议值
  const rankManual = isEdit.value
    ? originalRankManual.value || form.rankLevel !== originalRankLevel.value
    : true

  return {
    name: form.name.trim(),
    kind: form.kind,
    status: form.status,
    rankLevel: form.rankLevel,
    rankManual,
    avatar: emptyToNull(form.avatar),
    cover: emptyToNull(form.cover),
    address: emptyToNull(form.address),
    city: emptyToNull(form.city),
    province: emptyToNull(form.province),
    district: emptyToNull(form.district),
    lat: parseNumber(form.lat),
    lng: parseNumber(form.lng),
    // 空行丢掉（用户点了 + 没填），其余按顺序提交；后端还会做类型校验与去重
    contacts: contactPayload(),
    description: emptyToNull(form.description),
    photos: form.photos.map((photo) => ({
      id: photo.id,
      large: photo.large,
      thumb: photo.thumb,
      width: photo.width,
      height: photo.height,
      size: photo.size,
    })),
    packages,
    // 场所不允许有上级；只有人物才提交所属场所
    parentSpotId: form.kind === 'person' ? emptyToNull(form.parentSpotId) : null,
    tagIds: [...form.tagIds],
  }
}

/**
 * 选图 → 压缩（全链路唯一一次有损压缩）→ 上传 → 只把地址填进表单。
 * 仍然要点「保存」才落库，跟其它字段一致。
 */
async function pickImage(kind: 'avatar' | 'cover'): Promise<void> {
  if (uploading.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file) return
    uploading.value = kind
    try {
      const uploaded = await uploadImage(await compressImage(file))
      if (kind === 'avatar') {
        form.avatar = uploaded.avatar
      } else {
        form.cover = uploaded.large
      }
      ElMessage.success('图片已上传，记得点保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '上传失败，请重试')
    } finally {
      uploading.value = ''
    }
  }
  input.click()
}

/** 选择并上传打卡点自己的照片（最多 9 张，与头像/封面分开存） */
async function addPhotos(): Promise<void> {
  if (uploading.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.multiple = true
  input.onchange = async () => {
    const files = Array.from(input.files ?? []).slice(0, 9 - form.photos.length)
    if (files.length === 0) return
    uploading.value = 'photos'
    try {
      for (const file of files) {
        const uploaded = await uploadImage(await compressImage(file))
        form.photos.push({
          id: uploaded.id,
          large: uploaded.large,
          thumb: uploaded.thumb,
          width: uploaded.width,
          height: uploaded.height,
          size: uploaded.size,
        })
      }
      ElMessage.success('照片已上传，记得点保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '上传失败，请重试')
    } finally {
      uploading.value = ''
    }
  }
  input.click()
}

/** 底部弹层里正在操作的照片（null = 收起） */
const sheetPhoto = ref<PhotoRef | null>(null)

function removePhoto(index: number): void {
  form.photos.splice(index, 1)
  // 删掉的正好是弹层里那张，弹层就没对象了
  if (sheetPhoto.value && !form.photos.some((photo) => photo.id === sheetPhoto.value?.id)) {
    sheetPhoto.value = null
  }
}

function openPhotoSheet(photo: PhotoRef): void {
  sheetPhoto.value = photo
}

function closePhotoSheet(): void {
  sheetPhoto.value = null
}

/**
 * 把某张照片设为封面 / 头像。
 *
 * 只是把地址填进表单，仍要点「保存」才落库（跟其它字段一致）：
 * 封面用 large（详情页顶部是整屏大图），头像用 thumb —— 方形裁切交给 CSS，
 * 前端不拼 /uploads/avatars 那种派生图路径（拼了就绕开 CDN，换域名单点失效）。
 */
function usePhotoAs(kind: 'cover' | 'avatar'): void {
  const photo = sheetPhoto.value
  if (!photo) {
    return
  }
  // 照片行理论上两档地址都有；老数据缺档时明说，别把 undefined 塞进表单
  // （emptyToNull 会对它 .trim()，表现是「点保存没反应」）
  const url = kind === 'cover' ? photo.large : photo.thumb
  if (!url) {
    ElMessage.warning('这张照片没有可用的地址，换一张试试')
    return
  }
  if (kind === 'cover') {
    form.cover = url
    ElMessage.success('已设为封面，记得点保存')
  } else {
    form.avatar = url
    ElMessage.success('已设为头像，记得点保存')
  }
  closePhotoSheet()
}

/** 名称一改对就自动收起错误提示（输入过程中实时判断） */
function onNameInput(): void {
  if (form.name.trim()) {
    clearFieldError('name')
  }
}

async function save(): Promise<void> {
  if (!form.name.trim()) {
    markFieldError('name', '请给这个打卡点起个名字')
    nameInput.value?.focus()
    return
  }
  clearFieldError('name')

  // 兜底校验：输入框已经挡过非数字，这里防的是回填的老数据（带小数 / 0）
  const badPrice = form.packages.find((row) => {
    const value = parseNumber(row.price)
    return value !== null && (!Number.isInteger(value) || value <= 0)
  })
  if (badPrice) {
    ElMessage.warning('价格只能是正整数（元），可以不填')
    return
  }
  if (saving.value) return

  // 保存前先查重：有冲突就弹窗把「谁和谁撞了」摆出来，由用户决定继续还是回去改。
  // 用户点过「仍然保存」后 dupConfirmed=true，第二遍直接提交，不再弹第二次。
  if (!dupConfirmed.value) {
    saving.value = true
    try {
      if (!(await confirmContacts())) {
        return
      }
    } finally {
      saving.value = false
    }
  }

  saving.value = true
  try {
    await persist()
  } catch (error) {
    // 提交失败（网络 / 校验）后撤掉放行标记：再点保存要重新查一次
    dupConfirmed.value = false
    ElMessage.error(messageOf(error))
  } finally {
    saving.value = false
  }
}

/**
 * 联系方式一改，就把上一次「仍然保存」的放行撤掉。
 *
 * 否则会出现：点过仍然保存 → 改了一条联系方式 → 再点保存直接提交，
 * 新填的这条根本没被查过 —— 放行只对「刚才看到的那份内容」有效。
 */
watch(
  () => form.contacts.map((item) => `${item.type}\u0000${item.value}`).join('\u0001'),
  () => {
    dupConfirmed.value = false
  },
)

/**
 * 查重闸门：返回 true = 可以继续保存（没冲突，或查重本身失败），false = 已弹窗等用户确认。
 *
 * 查重失败**不拦保存**：它是提醒而不是校验，一次网络抖动不该让人存不了。
 */
async function confirmContacts(): Promise<boolean> {
  if (!contactPayload().length) {
    return true
  }
  checkingDup.value = true
  try {
    const list = await checkContactDuplicates(spotId.value, contactPayload())
    if (!list.length) {
      return true
    }
    dupList.value = list
    dupMode.value = 'save'
    dupOpen.value = true
    return false
  } catch {
    ElMessage.warning('联系方式查重失败，已跳过这次检查')
    return true
  } finally {
    checkingDup.value = false
  }
}

/** 真正落库；新建与编辑都在成功后回详情页 */
async function persist(): Promise<void> {
  const payload = buildPayload()
  if (spotId.value) {
    await updateSpot(spotId.value, payload)
    ElMessage.success('已保存')
    // 存完回详情页：编辑页不是常驻页，停在这里会让人以为「没保存成功 / 退不出去」
    await router.replace(`/spot/${spotId.value}`)
    return
  }
  const { id } = await createSpot(payload)
  ElMessage.success('已创建')
  // 新建 / 编辑都在保存后回详情页：编辑页停在原地会让人以为没存上、也退不出去
  await router.replace(`/spot/${id}`)
}

/**
 * 手动「查重」：没重复也弹，给个明确结论（保存流程里则只在有冲突时弹，免得每次保存都打扰）。
 */
async function runContactCheck(): Promise<void> {
  if (checkingDup.value) return
  if (!contactPayload().length) {
    ElMessage.info('还没有填联系方式')
    return
  }
  checkingDup.value = true
  try {
    dupList.value = await checkContactDuplicates(spotId.value, contactPayload())
    dupMode.value = 'check'
    dupOpen.value = true
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    checkingDup.value = false
  }
}

/** 弹窗里点「仍然保存」：放行本次保存 */
function onDuplicateConfirm(): void {
  dupOpen.value = false
  dupConfirmed.value = true
  void save()
}

/** 返回：编辑回详情，新建回地图（没有上级页面可靠，所以用 replace 而不是 back） */
function goBack(): void {
  void router.replace(spotId.value ? `/spot/${spotId.value}` : '/')
}

/**
 * 跳标签管理页（`/tags`）前先问一句。
 *
 * 编辑页不保存就离开，这次填的东西会全丢；标签维护又是个「顺手去建一个」的低频动作，
 * 很容易在填了一半的时候点过去。不用 RouterLink 正是因为那没有任何拦截点。
 */
async function goTagManage(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '标签的新增、改名和删除都在「我的 → 编辑标签」里。现在离开这一页，还没保存的修改会丢掉。',
      '先去管理标签？',
      { confirmButtonText: '过去', cancelButtonText: '留在这里' },
    )
  } catch {
    return
  }
  void router.push('/tags')
}

async function remove(): Promise<void> {
  if (!spotId.value) return
  try {
    await ElMessageBox.confirm('删除后不可恢复，相关到访记录也会一起删除。', '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteSpot(spotId.value)
    ElMessage.success('已删除')
    // 回「上一次入口」（列表 / 统计 / 地图）：既不能停在编辑页，也不能回退到刚删掉的详情页
    leaveSpotPage(router)
  } catch (error) {
    ElMessage.error(messageOf(error))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="edit__head">
      <button class="edit__back" type="button" aria-label="返回" @click="goBack()">←</button>
      <div class="edit__head-text">
        <h1 class="page-title">{{ isEdit ? '编辑打卡点' : '新建打卡点' }}</h1>
        <p v-if="isEdit" class="meta">{{ visitSummary }}</p>
        <p v-else class="meta">段位是「我对这个点的推荐等级」，没去过也能定</p>
      </div>
    </header>

    <section class="card block">
      <div class="block__title">
        <h2 class="section-title">图片</h2>
        <span class="meta">头像用在列表，封面用在详情大图；没设就默认第一张照片</span>
      </div>
      <div class="media">
        <button
          class="media__box media__box--avatar"
          type="button"
          :disabled="!!uploading"
          @click="pickImage('avatar')"
        >
          <SafeImage v-if="form.avatar" :src="form.avatar" kind="avatar" alt="头像" loading="eager" />
          <span v-else>{{ uploading === 'avatar' ? '上传中…' : '头像' }}</span>
        </button>
        <button
          class="media__box media__box--cover"
          type="button"
          :disabled="!!uploading"
          @click="pickImage('cover')"
        >
          <SafeImage v-if="form.cover" :src="form.cover" alt="封面" loading="eager" />
          <span v-else>{{ uploading === 'cover' ? '上传中…' : '封面' }}</span>
        </button>
      </div>
    </section>

    <section class="card block">
      <!-- 不用 v-model：分段控件发的是 string，而 form.kind 是字面量联合类型，
           直接绑会在类型检查时打架，这里显式断言一次 -->
      <SegmentedControl
        :model-value="form.kind"
        :options="kindOptions"
        @update:model-value="form.kind = $event as SpotKind"
      />

      <div class="ff-group">
        <div
          class="ff-field"
          :class="{ 'ff-field--error': fieldHasError('name'), 'ff-field--shake': fieldShaking('name') }"
          @animationend="stopFieldShake('name')"
        >
          <label class="row">
            <span class="row__label">名称</span>
            <input
              ref="nameInput"
              v-model="form.name"
              class="row__input"
              type="text"
              placeholder="必填"
              maxlength="120"
              @input="onNameInput"
            />
          </label>
        </div>
        <Transition name="ff-slide">
          <p v-if="fieldHasError('name')" class="ff-error">{{ fieldError('name') }}</p>
        </Transition>
      </div>

      <div class="field">
        <span class="field__label">段位</span>
        <RankPicker v-model="form.rankLevel" />
      </div>

      <div class="field">
        <span class="field__label">状态</span>
        <div class="statusbar">
          <SegmentedControl
            :model-value="form.status"
            :options="statusOptions"
            @update:model-value="form.status = $event as SpotStatus"
          />
        </div>
        <p class="meta block__hint">未打卡 = 还没去过 · 想打卡 = 计划要去 · 避雷 = 不再考虑</p>
      </div>
    </section>

    <section class="card block">
      <div class="row row--region">
        <span class="row__label">省市区</span>
        <div class="region">
          <el-select
            v-model="form.province"
            class="region__select"
            popper-class="region-popper"
            clearable
            placeholder="省份"
            @visible-change="onRegionOpen('province', $event)"
            @change="onProvinceChange"
          >
            <template #header>
              <div class="region__search">
                <input
                  v-model="regionFilter.province"
                  class="region__search-input"
                  type="search"
                  placeholder="搜索省份"
                />
              </div>
            </template>
            <el-option v-for="item in filteredProvinces" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select
            v-model="form.city"
            class="region__select"
            popper-class="region-popper"
            clearable
            placeholder="城市"
            :disabled="!form.province"
            @visible-change="onRegionOpen('city', $event)"
            @change="onCityChange"
          >
            <template #header>
              <div class="region__search">
                <input
                  v-model="regionFilter.city"
                  class="region__search-input"
                  type="search"
                  placeholder="搜索城市"
                />
              </div>
            </template>
            <el-option v-for="item in filteredCities" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select
            v-model="form.district"
            class="region__select"
            popper-class="region-popper"
            clearable
            placeholder="区县"
            :disabled="!form.city"
            @visible-change="onRegionOpen('district', $event)"
            @change="syncAddressRegion"
          >
            <template #header>
              <div class="region__search">
                <input
                  v-model="regionFilter.district"
                  class="region__search-input"
                  type="search"
                  placeholder="搜索区县"
                />
              </div>
            </template>
            <el-option v-for="item in filteredDistricts" :key="item" :label="item" :value="item" />
          </el-select>
        </div>
      </div>
      <label class="row">
        <span class="row__label">地址</span>
        <input v-model="form.address" class="row__input" type="text" placeholder="xxx路xxx号" maxlength="255" />
      </label>
      <div class="row">
        <span class="row__label">坐标</span>
        <input v-model="form.lat" class="row__input row__input--half" type="number" step="0.000001" placeholder="纬度" />
        <input v-model="form.lng" class="row__input row__input--half" type="number" step="0.000001" placeholder="经度" />
      </div>
      <div class="row">
        <span class="row__label">地图</span>
        <span class="row__note">{{ hasCoords ? '已带坐标，可直接保存' : '还没选点' }}</span>
        <button class="pickbtn" type="button" @click="pickerOpen = true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 21s7-5.6 7-11a7 7 0 1 0-14 0c0 5.4 7 11 7 11z" />
            <circle cx="12" cy="10" r="2.4" />
          </svg>
          地图选点
        </button>
      </div>
    </section>

    <section class="card block">
      <div class="block__title">
        <h2 class="section-title">标签</h2>
        <span class="meta">已选 {{ form.tagIds.length }}/8</span>
      </div>

      <!-- 全部标签按分类分组平铺：不用下拉。标签量本来就不大，一次看全比
           「点开下拉 → 找 → 再点开」快得多；分组是为了几十个标签时不至于找不到。
           组内顺序沿用后端 tag.sort_order，与标签管理页拖出来的优先级一致。
           选满 8 个后未选中的变淡，点它给出提示而不是静默无反应。 -->
      <div v-if="tags.length" class="taggroups">
        <div v-for="group in tagGroups" :key="group.code" class="taggroup">
          <p class="taggroup__head">{{ group.label }}</p>
          <div class="tagpick">
            <button
              v-for="tag in group.items"
              :key="tag.id"
              class="tagpick__item"
              :class="{
                'tagpick__item--on': form.tagIds.includes(tag.id),
                'tagpick__item--dim':
                  !form.tagIds.includes(tag.id) && form.tagIds.length >= MAX_TAGS,
              }"
              :style="{ '--tag-color': resolveTagColor(tag) }"
              type="button"
              :aria-pressed="form.tagIds.includes(tag.id)"
              @click="toggleTag(tag.id)"
            >
              <i class="tagpick__dot" />
              {{ tag.name }}
            </button>
          </div>
        </div>
      </div>

      <!-- 标签是全库共享的字典：新增 / 改名 / 删除都收在「我的 → 编辑标签」，这里只负责勾选。
           跳过去会离开本页（未保存的修改会丢），所以点之前先问一句，不做静默丢弃。 -->
      <p class="meta tagnote">
        标签统一在
        <button class="tagnote__link" type="button" @click="goTagManage()">我的 → 编辑标签</button>
        里新增、改名和删除{{ tags.length === 0 ? '；现在还是空的，先去建几个' : '' }}
      </p>
    </section>

    <section class="card block">
      <div class="block__title">
        <h2 class="section-title">照片</h2>
        <span class="meta">点照片可设为封面 / 头像，最多 9 张</span>
      </div>

      <div class="photogrid">
        <div v-for="(photo, index) in form.photos" :key="photo.id" class="photogrid__item">
          <button
            type="button"
            class="photogrid__open"
            :aria-label="`第 ${index + 1} 张照片，点开可设为封面或头像`"
            @click="openPhotoSheet(photo)"
          >
            <SafeImage :src="photo.thumb" :alt="`照片 ${index + 1}`" />
          </button>
          <button type="button" class="photogrid__del" :aria-label="`删除第 ${index + 1} 张`" @click="removePhoto(index)">×</button>
        </div>
        <button
          v-if="form.photos.length < 9"
          type="button"
          class="photogrid__add"
          :disabled="!!uploading"
          @click="addPhotos"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
          <span>{{ uploading ? '上传中…' : '添加照片' }}</span>
        </button>
      </div>
    </section>

    <!--
      联系方式：不再把五种类型各摆一个空输入框（绝大多数永远空着，白占一屏）。
      现在「加一行 = 一条联系方式」：点类型块换类型，点 + 追加一行，同类型可以多条。
      类型清单在 config/contacts.ts，与后端白名单一一对应。
    -->
    <section class="card block">
      <div class="block__title">
        <h2 class="section-title">联系方式</h2>
        <!-- 手动查重：没重复也弹，给个明确结论；保存时则只在有冲突时弹 -->
        <button class="dupcheck" type="button" :disabled="checkingDup" @click="runContactCheck()">
          {{ checkingDup ? '检查中…' : '查重' }}
        </button>
      </div>

      <p v-if="!form.contacts.length" class="block__hint meta">
        还没有联系方式。先点「新增大类」选类型（电话 / 微信 / QQ …），再在类下填值。
      </p>

      <!-- 大类分组：一个类型一组，可折叠；组内多条（两个手机号、两个微信号） -->
      <div v-for="group in contactGroups" :key="group.type" class="cg">
        <button
          type="button"
          class="cg__head"
          :aria-expanded="!isCollapsed(group.type)"
          @click="toggleGroup(group.type)"
        >
          <svg class="cg__caret" width="12" height="12" viewBox="0 0 24 24" fill="none"
               stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"
               aria-hidden="true">
            <path :d="isCollapsed(group.type) ? 'M9 6l6 6-6 6' : 'M6 9l6 6 6-6'" />
          </svg>
          <span class="cg__label" :class="`cg__label--${group.type}`">{{ group.meta.label }}</span>
          <span class="cg__count">{{ group.rows.length }}</span>
        </button>

        <div v-if="!isCollapsed(group.type)" class="cg__body">
          <div v-for="row in group.rows" :key="row.uid" class="cg__row">
            <input
              :id="`ct-input-${row.uid}`"
              v-model="row.value"
              class="cg__input"
              type="text"
              :inputmode="group.meta.inputMode"
              :placeholder="group.meta.placeholder"
              maxlength="120"
            />
            <button
              type="button"
              class="cg__del"
              :aria-label="`删除这条${group.meta.label}`"
              @click="removeContact(row)"
            >
              ×
            </button>
          </div>
          <button type="button" class="cg__add" @click="addRowToGroup(group.type)">
            ＋ 在{{ group.meta.label }}下新增一条
          </button>
        </div>
      </div>

      <!-- 新增大类：只列还没加过的类型，避免同一类出现两个分组 -->
      <div v-if="groupPickerOpen && unusedTypes.length" class="cg__picker">
        <button
          v-for="meta in unusedTypes"
          :key="meta.value"
          type="button"
          class="cg__option"
          @click="addGroup(meta.value)"
        >
          <span class="cg__option-label">{{ meta.label }}</span>
          <span class="cg__option-hint">{{ meta.placeholder }}</span>
        </button>
      </div>

      <button
        v-if="unusedTypes.length"
        type="button"
        class="btn btn--ghost block__add"
        :aria-expanded="groupPickerOpen"
        @click="groupPickerOpen = !groupPickerOpen"
      >
        ＋ 新增大类
      </button>
      <p v-else class="meta block__hint">五类联系方式都加过了，可在各分组里继续加条目。</p>
    </section>

    <section class="card block">
      <div class="block__title">
        <h2 class="section-title">消费项目</h2>
        <span class="meta">价格填正整数（元），不填就是不写价</span>
      </div>

      <div v-for="(item, index) in form.packages" :key="index" class="pkg">
        <input v-model="item.name" class="pkg__input pkg__input--name" type="text" placeholder="项目名" maxlength="40" />
        <input
          v-model="item.price"
          class="pkg__input pkg__input--price"
          type="text"
          inputmode="numeric"
          maxlength="6"
          placeholder="价格"
          @beforeinput="blockNonDigit"
          @input="onPriceInput(item, $event)"
        />
        <button type="button" class="pkg__del" :aria-label="`删除第 ${index + 1} 项`" @click="removePackage(index)">×</button>
      </div>

      <button type="button" class="btn btn--ghost block__add" @click="addPackage">添加</button>
    </section>

    <section class="card block">
      <div class="field">
        <span class="field__label">描述 / 备注</span>
        <textarea v-model="form.description" class="textarea" rows="4" placeholder="服务内容、印象深刻的地方…"></textarea>
      </div>

      <div v-if="form.kind === 'person'" class="field">
        <span class="field__label">所属场所</span>
        <el-select v-model="form.parentSpotId" class="select" clearable filterable placeholder="不挂靠">
          <el-option v-for="place in places" :key="place.id" :label="place.name" :value="place.id" />
        </el-select>
      </div>
    </section>

    <div class="actions">
      <button class="btn actions__save" type="button" :disabled="saving || loading" @click="save">
        {{ saving ? '保存中…' : '保存' }}
      </button>
      <button v-if="isEdit" class="btn btn--ghost" type="button" :disabled="saving" @click="remove">
        删除
      </button>
    </div>

    <!-- 点照片弹的底部操作条：只做「设为封面 / 头像」，删除仍在格子右上角 -->
    <Transition name="sheet">
      <div v-if="sheetPhoto" class="sheet" @click.self="closePhotoSheet">
        <div class="sheet__panel" role="dialog" aria-modal="true" aria-label="照片操作">
          <div class="sheet__head">
            <SafeImage :src="sheetPhoto.thumb" alt="" />
            <span>这张照片</span>
          </div>
          <button type="button" class="sheet__act" @click="usePhotoAs('cover')">设为封面</button>
          <button type="button" class="sheet__act" @click="usePhotoAs('avatar')">设为头像</button>
          <button type="button" class="sheet__act sheet__act--cancel" @click="closePhotoSheet">取消</button>
        </div>
      </div>
    </Transition>

    <MapPicker
      v-if="pickerOpen"
      :lat="parseNumber(form.lat)"
      :lng="parseNumber(form.lng)"
      :address="form.address"
      @close="pickerOpen = false"
      @confirm="applyPicked"
    />

    <!-- 联系方式查重结果：手动「查重」与保存前自动查共用同一个弹窗 -->
    <ContactDuplicateDialog
      :visible="dupOpen"
      :duplicates="dupList"
      :mode="dupMode"
      @close="dupOpen = false"
      @confirm="onDuplicateConfirm"
    />
  </div>
</template>

<style scoped>
.edit__head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.edit__head-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.edit__head p {
  margin: 0;
}

.edit__back {
  flex: none;
  width: 38px;
  height: 38px;
  margin-top: 4px;
  border: 0;
  border-radius: 50%;
  background: var(--card);
  box-shadow: var(--sh);
  color: var(--ink);
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
}

.block {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
}

.block__title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.block__hint {
  margin: 0;
}

/* 次要按钮一律靠右：跟着每一行的右端走，别在左侧另起一列 */
.block__add {
  align-self: flex-end;
  height: 38px;
  padding: 0 16px;
}

/* 「查重」是卡片标题行上的文字按钮：常驻但低调，别跟「保存」抢注意力 */
.dupcheck {
  padding: 0;
  border: 0;
  background: none;
  color: var(--ink2);
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  text-decoration: underline;
  text-decoration-thickness: 1px;
  text-underline-offset: 2px;
  cursor: pointer;
}

.dupcheck:disabled {
  opacity: 0.5;
  cursor: default;
}

/* ---- 54px 输入行：左标签灰、右值黑右对齐 ---- */
.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 54px;
}

.row + .row,
.row + .field,
.field + .row {
  border-top: 1px solid var(--line);
}

.row__label {
  flex: none;
  min-width: 64px;
  font-size: 13px;
  color: var(--ink2);
}

.row__input {
  flex: 1;
  min-width: 0;
  height: 54px;
  border: 0;
  background: transparent;
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 500;
  text-align: right;
  outline: none;
}

.row__input::placeholder {
  color: var(--ink3);
}

.row__input--half {
  flex: 1;
}

/* 省市区：三个可搜索下拉。故意做成浅底胶囊，跟右边「手输」的地址行区分开 ——
   一眼能看出这三格是**选**，不是填 */
.row--region {
  gap: 10px;
}

.region {
  display: flex;
  flex: 1;
  gap: 6px;
  min-width: 0;
}

.region__select {
  flex: 1 1 0;
  min-width: 0;
}

.region__select :deep(.el-select__wrapper) {
  min-height: 34px;
  padding: 0 10px;
  border-radius: 9px;
  background: var(--bg);
  box-shadow: none;
  font-size: 12.5px;
}

.region__select :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--line2) inset;
}

.region__select :deep(.el-select__placeholder) {
  color: var(--ink3);
}

.region__select :deep(.el-select__selected-item) {
  color: var(--ink);
  font-weight: 600;
}

/* 下拉面板顶部的搜索框：点了才聚焦、才弹键盘，默认展开只是列表 */
.region__search {
  padding: 8px 8px 6px;
}

.region__search-input {
  width: 100%;
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  background: var(--bg);
  color: var(--ink);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 500;
  outline: none;
}

.region__search-input::placeholder {
  color: var(--ink3);
}

/* 窄屏（真机）：一行里塞「标签 + 三格」时，每格只剩 87px ——
   扣掉 20px 内边距与右侧箭头，文字区只剩 45px，「湖北省」都会被截成「湖…」。
   所以窄屏把标签提到上一行，三格平分整卡宽度（412px 屏上是 112px/格）。 */
@media (max-width: 560px) {
  .row--region {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    padding: 10px 0;
  }

  .region {
    flex-wrap: wrap;
  }

  .region__select {
    flex: 1 1 88px;
  }

  .region__select :deep(.el-select__wrapper) {
    padding: 0 8px;
    font-size: 13px;
  }
}

/* 坐标行右端的「已选/未选」提示，靠右贴着按钮 */
.row__note {
  flex: 1;
  min-width: 0;
  color: var(--ink3);
  font-size: 11.5px;
  text-align: right;
}

.pickbtn {
  display: flex;
  flex: none;
  gap: 5px;
  align-items: center;
  height: 34px;
  padding: 0 12px;
  border: 0;
  border-radius: 999px;
  background: var(--bg);
  color: var(--ink);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
}

/* ---- 价目一行：项目 + 价格靠左，删除键顶到最右 ---- */
/* 原来价格框是「透明输入框 + 右对齐」，在白卡上根本看不出可输入边界，所以给两个框都加浅色底 */
.pkg {
  display: flex;
  gap: 8px;
  align-items: center;
}

.pkg__input {
  height: 44px;
  padding: 0 12px;
  border: 0;
  border-radius: 10px;
  background: var(--bg);
  box-shadow: 0 0 0 1px var(--line2) inset;
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 500;
  outline: none;
}

.pkg__input::placeholder {
  color: var(--ink3);
}

.pkg__input:focus {
  background: var(--card);
  box-shadow: 0 0 0 1.5px var(--ink) inset;
}

.pkg__input--name {
  flex: 1;
  min-width: 0;
}

.pkg__input--price {
  flex: none;
  width: 88px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.pkg__del {
  flex: none;
  width: 30px;
  height: 36px;
  border: 0;
  border-radius: 9px;
  background: var(--bg);
  color: var(--ink2);
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
}

/* ---- 联系方式：大类（可折叠）→ 类下多条 ---- */
.cg + .cg {
  margin-top: 8px;
}

/* 大类头：整行可点，折叠态靠箭头方向表达，不另做开关控件 */
.cg__head {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 0;
  border-radius: 10px;
  background: var(--bg);
  box-shadow: 0 0 0 1px var(--line2) inset;
  color: var(--ink);
  font-family: inherit;
  font-size: 13px;
  font-weight: 700;
  text-align: left;
  cursor: pointer;
}

.cg__caret {
  flex: none;
  color: var(--ink3);
}

.cg__label {
  flex: 1;
  min-width: 0;
}

/* 类型各给一点极淡的色，扫一眼能分清，但不抢内容的位置 */
.cg__label--phone {
  color: #1f7a4d;
}

.cg__label--wechat {
  color: #17783f;
}

.cg__label--qq {
  color: #1b64c0;
}

.cg__label--telegram {
  color: #1d84b8;
}

.cg__label--youyu {
  color: #6b4fd0;
}

.cg__count {
  flex: none;
  color: var(--ink3);
  font-size: 11.5px;
  font-weight: 600;
}

/* 组内：左侧一条竖线做层级，比给每行加缩进卡片更轻，也不会套成三层卡片 */
.cg__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 8px 0 0 14px;
  padding-left: 12px;
  border-left: 2px solid var(--line);
}

.cg__row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.cg__input {
  flex: 1;
  min-width: 0;
  height: 44px;
  padding: 0 12px;
  border: 0;
  border-radius: 10px;
  background: var(--bg);
  box-shadow: 0 0 0 1px var(--line2) inset;
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 500;
  outline: none;
}

.cg__input::placeholder {
  color: var(--ink3);
}

.cg__input:focus {
  background: var(--card);
  box-shadow: 0 0 0 1.5px var(--ink) inset;
}

.cg__del {
  flex: none;
  width: 30px;
  height: 36px;
  border: 0;
  border-radius: 9px;
  background: var(--bg);
  color: var(--ink2);
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
}

/* 「＋ 在 QQ 下新增一条」：文字按钮，避免每组再长出一个小方块 */
.cg__add {
  align-self: flex-start;
  padding: 2px 0;
  border: 0;
  background: transparent;
  color: var(--ink3);
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.cg__add:active {
  color: var(--ink);
}

/* 「新增大类」的类型清单：与之前换类型的面板同一套视觉 */
.cg__picker {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin-top: 10px;
  border-radius: 10px;
  background: var(--bg);
  box-shadow: 0 0 0 1px var(--line2) inset;
}

.cg__option {
  display: flex;
  gap: 10px;
  align-items: baseline;
  padding: 10px 12px;
  border: 0;
  background: transparent;
  color: var(--ink);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.cg__option + .cg__option {
  border-top: 1px solid var(--line);
}

.cg__option-label {
  flex: none;
  min-width: 66px;
  font-size: 13px;
  font-weight: 700;
}

.cg__option-hint {
  min-width: 0;
  color: var(--ink3);
  font-size: 11.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 头像 / 封面 ---- */
.media {
  display: flex;
  align-items: stretch;
  gap: 12px;
}

.media__box {
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: var(--r);
  background: var(--bg);
  color: var(--ink3);
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  overflow: hidden;
  cursor: pointer;
}

.media__box:disabled {
  opacity: 0.6;
  cursor: default;
}

.media__box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.media__box--avatar {
  flex: none;
  width: 84px;
  height: 84px;
}

.media__box--cover {
  flex: 1;
  height: 84px;
}

/* ---- 非输入行的字段块 ---- */
.field {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

/* 四个状态平分整行：375px 屏上「未打卡/已打卡/想打卡/避雷」四个胶囊塞不进一行，
   挤在一起会被裁掉，所以让它撑满卡片并等宽 */
.statusbar :deep(.seg) {
  display: flex;
  width: 100%;
}

.statusbar :deep(.seg__item) {
  flex: 1;
  padding: 8px 4px;
  font-size: 12.5px;
}

.field__label {
  font-size: 13px;
  color: var(--ink2);
}

.textarea {
  width: 100%;
  padding: 12px;
  border: 0;
  border-radius: var(--r);
  background: var(--bg);
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 500;
  line-height: 1.6;
  resize: vertical;
  outline: none;
}

.select {
  width: 100%;
}

/* ---- 标签平铺多选（按分类分组）：未选=灰底细线，选中=浅彩底 + 同色字 + 同色描边 ---- */
.taggroups {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.taggroup {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

/* 分组标题：小、弱，只做分隔，不跟标签名抢注意力 */
.taggroup__head {
  margin: 0;
  color: var(--ink3);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.tagpick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tagpick__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 6px 13px;
  border: 0;
  border-radius: 999px;
  background: var(--bg);
  /* 未选中的 1px 内描边是给选中态占位的：切换时宽度不变，整行不会抖 */
  box-shadow: 0 0 0 1px var(--line2) inset;
  color: var(--ink2);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.14s ease, color 0.14s ease, box-shadow 0.14s ease;
}

/* 与详情页标签 chip 同一套颜色语言：浅彩底 + 同色深字。
   描边用 2px：标签名常是 emoji，只靠底色区分太弱，靠一圈实色边才一眼看出勾没勾。 */
.tagpick__item--on {
  background: color-mix(in srgb, var(--tag-color) 20%, #fff);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--tag-color) 70%, #fff) inset;
  color: color-mix(in srgb, var(--tag-color) 70%, #000);
  font-weight: 700;
}

/* 选满 8 个后未选中的变淡。不做 disabled —— 仍要能点，点了给提示 */
.tagpick__item--dim {
  opacity: 0.45;
}

.tagpick__dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--tag-color);
  opacity: 0.55;
}

.tagpick__item--on .tagpick__dot {
  opacity: 1;
}

/* 标签维护入口的说明行：链接做成「看起来像链接的文字按钮」，
   但底色透明、不加边框，避免跟底部的「保存」抢注意力 */
.tagnote {
  margin: 0;
}

.tagnote__link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--ink);
  font-family: inherit;
  font-size: inherit;
  font-weight: 700;
  text-decoration: underline;
  text-decoration-thickness: 1.5px;
  text-underline-offset: 2px;
  cursor: pointer;
}

/* ---- 照片九宫格 ---- */
.photogrid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.photogrid__item {
  position: relative;
  aspect-ratio: 1 / 1;
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg);
}

.photogrid__item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 整格是一个按钮（去掉默认外观），点开底部操作条 */
.photogrid__open {
  display: block;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.photogrid__open:active img {
  opacity: 0.82;
}

.photogrid__del {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 50%;
  background: rgba(17, 19, 24, 0.62);
  color: #fff;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}

.photogrid__add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  aspect-ratio: 1 / 1;
  border: 0;
  border-radius: 10px;
  background: var(--bg);
  box-shadow: 0 0 0 1px var(--line2) inset;
  color: var(--ink3);
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

/* ---- 照片操作弹层（点单张照片从底部升起） ---- */
.sheet {
  position: fixed;
  inset: 0;
  z-index: 210;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(17, 24, 39, 0.38);
}

.sheet__panel {
  width: 100%;
  max-width: 720px;
  padding: 10px 12px calc(env(safe-area-inset-bottom, 0px) + 12px);
  border-radius: 16px 16px 0 0;
  background: var(--card);
  box-shadow: 0 -8px 28px -18px rgba(17, 24, 39, 0.42);
}

.sheet__head {
  display: flex;
  gap: 9px;
  align-items: center;
  padding: 2px 6px 10px;
}

.sheet__head img {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  object-fit: cover;
}

.sheet__head span {
  overflow: hidden;
  color: var(--ink3);
  font-size: 11.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sheet__act {
  display: block;
  width: 100%;
  height: 52px;
  border: 0;
  border-top: 1px solid var(--line);
  background: transparent;
  color: var(--ink);
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.sheet__act:active {
  background: var(--bg);
}

/* 取消单独一格：跟上面两个动作隔开，避免误点成「删除」之类的错觉 */
.sheet__act--cancel {
  margin-top: 8px;
  border-top: 0;
  border-radius: 10px;
  background: var(--bg);
  color: var(--ink2);
  font-weight: 500;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.16s ease;
}

.sheet-enter-active .sheet__panel,
.sheet-leave-active .sheet__panel {
  transition: transform 0.18s ease;
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .sheet__panel,
.sheet-leave-to .sheet__panel {
  transform: translateY(14px);
}

/* ---- 底部操作：常驻视口底部（与详情页的操作栏一套做法） ----
   编辑页很长，保存按钮若留在文档流末尾，用户每次都得先滚到底才能存。
   固定后必须给 .page 补底部内边距，否则最后一张卡被按钮压住（见文件上方 .page）。 */
.actions {
  position: fixed;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%);
  width: 100%;
  max-width: 720px;
  /* 低于 TabBar(30) 与照片弹层(210)：它只是页面内的操作栏 */
  z-index: 20;
  display: flex;
  gap: 10px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom, 0px));
  background: var(--card);
  /* 一条 1px 线不够：滚动时卡片边缘像贴在按钮上 */
  box-shadow: 0 -1px 0 var(--line), 0 -6px 20px -14px rgba(17, 24, 39, 0.18);
}

.actions__save {
  flex: 1;
}

/* 删除按钮固定宽度，别跟保存按钮抢空间 */
.actions .btn--ghost {
  flex: none;
  padding: 0 22px;
}

/* 给底部常驻操作栏留位置：栏高 = 48(按钮) + 10+10(padding) + 安全区，再多留一点呼吸 */
.page {
  padding-bottom: calc(96px + env(safe-area-inset-bottom, 0px));
}
</style>

<!-- el-select 的下拉浮层被 teleport 到 body，scoped 样式够不着，只能写一份不带 scoped 的 -->
<style>
/* 省市区下拉：宽一点，否则搜索框和「内蒙古自治区」这类长名字会被 112px 的触发器宽度挤爆 */
.region-popper {
  min-width: 220px !important;
}
</style>
