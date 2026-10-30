<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import SegmentedControl from '@/components/SegmentedControl.vue'
import RankPicker from '@/components/RankPicker.vue'
import MapPicker from '@/components/MapPicker.vue'
import {
  createSpot,
  deleteSpot,
  getSpot,
  listSpots,
  updateSpot,
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
import { createTag, listTags, type TagItem } from '@/api/tags'
import { leaveSpotPage } from '@/router/spotEntry'
import { resolveTagColor } from '@/utils/tagColor'
import { uploadImage } from '@/api/upload'
import { compressImage } from '@/utils/image'
import { HttpError } from '@/api/http'
import { DEFAULT_STATUS, SPOT_STATUSES } from '@/config/status'
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
const creatingTag = ref(false)
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
const places = ref<SpotListItem[]>([])
const newTagName = ref('')
/** 新建标签时可挑的颜色，回退色放在最后（传空=用默认灰底） */
const TAG_COLORS = ['#FF5A1E', '#12B7F5', '#07C160', '#8A5CF6', '#F5A623', '#E8413B', '#2AABEE', '#FF7BAC', '#23B09C', '#5A6069']
const newTagColor = ref(TAG_COLORS[0])

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
    const [tagList, placeList] = await Promise.all([listTags(), listSpots({ kind: 'place' })])
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

/** 从标签库里按 id 反查名称与颜色（卡片里展示已选标签用） */
function tagOf(id: string): TagItem | undefined {
  return tags.value.find((item) => item.id === id)
}

function tagName(id: string): string {
  return tagOf(id)?.name ?? ''
}

function tagColor(id: string): string {
  const tag = tagOf(id)
  return tag ? resolveTagColor(tag) : ''
}

/**
 * 新建标签：后端同名会直接复用（绝不产生第二行），所以这里不判定「重复」，
 * 建完一律选中。先把 8 个上限挡在前面 —— 否则会出现「标签建出来了却没挂上」的怪状态。
 */
async function addTag(): Promise<void> {
  const name = newTagName.value.trim()
  if (!name || creatingTag.value) return
  if (form.tagIds.length >= MAX_TAGS) {
    ElMessage.warning('已选满 8 个标签，先去掉一个再建新的')
    return
  }

  const existed = tags.value.some((item) => item.name === name)
  creatingTag.value = true
  try {
    const tag = await createTag(name, newTagColor.value)
    if (!tags.value.some((item) => item.id === tag.id)) {
      tags.value = [...tags.value, tag]
    }
    if (!form.tagIds.includes(tag.id)) {
      form.tagIds.push(tag.id)
    }
    newTagName.value = ''
    ElMessage.success(existed ? `「${tag.name}」已在标签库里，已选中` : `标签「${tag.name}」已创建并选中`)
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    creatingTag.value = false
  }
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
  if (value.address) {
    form.address = value.address
  }
  if (value.city) {
    form.city = value.city
  }
  if (value.province) {
    form.province = value.province
  }
  if (value.district) {
    form.district = value.district
  }
  pickerOpen.value = false
  ElMessage.success('位置已带入，记得点保存')
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
    contacts: form.contacts
      .map((item) => ({ type: item.type, value: item.value.trim() }))
      .filter((item): item is SpotContact => item.value.length > 0),
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

  saving.value = true
  try {
    const payload = buildPayload()
    if (spotId.value) {
      await updateSpot(spotId.value, payload)
      ElMessage.success('已保存')
      // 存完回详情页：编辑页不是常驻页，停在这里会让人以为「没保存成功 / 退不出去」
      await router.replace(`/spot/${spotId.value}`)
    } else {
      const { id } = await createSpot(payload)
      ElMessage.success('已创建')
      // 新建 / 编辑都在保存后回详情页：编辑页停在原地会让人以为没存上、也退不出去
      await router.replace(`/spot/${id}`)
    }
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    saving.value = false
  }
}

/** 返回：编辑回详情，新建回地图（没有上级页面可靠，所以用 replace 而不是 back） */
function goBack(): void {
  void router.replace(spotId.value ? `/spot/${spotId.value}` : '/')
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
          <img v-if="form.avatar" :src="form.avatar" alt="头像" />
          <span v-else>{{ uploading === 'avatar' ? '上传中…' : '头像' }}</span>
        </button>
        <button
          class="media__box media__box--cover"
          type="button"
          :disabled="!!uploading"
          @click="pickImage('cover')"
        >
          <img v-if="form.cover" :src="form.cover" alt="封面" />
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
      <label class="row">
        <span class="row__label">地址</span>
        <input v-model="form.address" class="row__input" type="text" placeholder="可空" maxlength="255" />
      </label>
      <label class="row">
        <span class="row__label">城市</span>
        <input v-model="form.city" class="row__input" type="text" placeholder="可空" maxlength="60" />
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

      <el-select
        v-model="form.tagIds"
        class="select"
        multiple
        filterable
        collapse-tags
        collapse-tags-tooltip
        :multiple-limit="MAX_TAGS"
        placeholder="勾选标签（可搜索）"
      >
        <el-option v-for="tag in tags" :key="tag.id" :label="tag.name" :value="tag.id">
          <span :style="{ display: 'inline-flex', alignItems: 'center', gap: '8px' }">
            <span
              :style="{
                width: '10px',
                height: '10px',
                borderRadius: '50%',
                flex: 'none',
                background: resolveTagColor(tag),
              }"
            />
            <span>{{ tag.name }}</span>
          </span>
        </el-option>
      </el-select>

      <div v-if="form.tagIds.length" class="chips">
        <span
          v-for="id in form.tagIds"
          :key="id"
          class="chip chip--color"
          :style="{ '--tag-color': tagColor(id) }"
        >
          <i class="chip__dot" />
          {{ tagName(id) }}
        </span>
      </div>

      <p v-if="tags.length === 0" class="meta">还没有标签，下面敲一个就会出现</p>

      <div class="tag-new">
        <input
          v-model="newTagName"
          class="row__input"
          type="text"
          placeholder="新标签名，回车即建"
          maxlength="24"
          @keyup.enter.prevent="addTag"
        />
        <button type="button" class="btn btn--ghost tag-new__btn" :disabled="creatingTag" @click="addTag">
          {{ creatingTag ? '建中…' : '添加' }}
        </button>
      </div>

      <div class="tag-colors">
        <button
          v-for="color in TAG_COLORS"
          :key="color"
          type="button"
          class="swatch"
          :class="{ 'swatch--on': newTagColor === color }"
          :style="{ background: color }"
          :aria-label="`选择颜色 ${color}`"
          @click="newTagColor = color"
        />
      </div>
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
            <img :src="photo.thumb" :alt="`照片 ${index + 1}`" loading="lazy" decoding="async" />
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
        <span class="meta">先加大类，再在类下加条目</span>
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
            <img :src="sheetPhoto.thumb" alt="" loading="lazy" decoding="async" />
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

/* ---- 标签 chip：白底细阴影，选中黑底白字 ---- */
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 13px;
  border: 0;
  border-radius: 999px;
  background: var(--card);
  box-shadow: 0 0 0 1px var(--line2) inset;
  color: var(--ink2);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
}

/* 带色标签：浅底 + 同色深字 + 左侧实心点，颜色一眼可见 */
.chip--color {
  background: color-mix(in srgb, var(--tag-color) 16%, #fff);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--tag-color) 28%, #fff) inset;
  color: color-mix(in srgb, var(--tag-color) 72%, #000);
}

.chip__dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--tag-color);
}

.tag-new {
  display: flex;
  gap: 8px;
  align-items: center;
}

.tag-new__btn {
  flex: none;
  height: 44px;
  padding: 0 16px;
}

/* ---- 标签颜色选择 ---- */
.tag-colors {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.swatch {
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  box-shadow: 0 0 0 1px var(--line2) inset;
  cursor: pointer;
}

.swatch--on {
  box-shadow: 0 0 0 2px var(--card), 0 0 0 4px var(--ink);
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

/* ---- 底部操作 ---- */
.actions {
  display: flex;
  gap: 10px;
  padding-bottom: 8px;
}

.actions__save {
  flex: 1;
}
</style>
