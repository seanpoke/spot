<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { HttpError } from '@/api/http'
import {
  listSpotRegions,
  pageSpots,
  type SpotListItem,
  type SpotPageQuery,
  type SpotRegion,
  type SpotStatus,
} from '@/api/spots'
import { listTags, type TagItem } from '@/api/tags'
import { RANKS } from '@/config/ranks'
import { SPOT_STATUSES } from '@/config/status'
import SpotRow from '@/components/SpotRow.vue'
import TabBar from '@/components/TabBar.vue'

/**
 * 列表页（`/list`）。
 *
 * 所有条件（省市区 / 状态 / 等级 / 标签 / 价格 / 关键字 / 联系方式）都下发到服务端，**筛完再切页**：
 * 服务端分页必须这样，否则第 2 页会把第 1 页筛掉的行捡回来，条数与结果都是错的。
 * 每页 20 条，点「加载更多」续页；条件一变（防抖 300ms）就回到第 1 页。
 *
 * 两个下拉的选项来源也跟着换了 —— 分页后前端拿不到全量数据，选项不能再从当前行里现取，
 * 否则「加载了几页」会决定有哪些省市区可选：
 *   - 省市区：`GET /api/spots/regions`（去重组合）
 *   - 标签：`GET /api/tags`（带 useCount，只列用过的，按使用次数排）
 *
 * 条件在会话内记住（sessionStorage）：从详情页返回列表时，条件不该被清空。
 */

const PAGE_SIZE = 20
const FILTER_STORAGE_KEY = 'visit:list:filters'
/**
 * 离开列表页时记住滚动位置与已加载条数，回来时先把数据续到同样的条数再滚回去。
 * 只记 y 不够：重新进页面只加载第 1 页（20 条），文档没那么高，滚到 y 会被浏览器夹回底部。
 */
const SCROLL_STORAGE_KEY = 'visit:list:scroll'
/** 恢复时最多自动续载几页，防止「上次在 1000 条处」这种极端情况回来连打几十个请求 */
const MAX_RESTORE_PAGES = 10

interface SavedFilters {
  keyword: string
  contact: string
  province: string
  city: string
  district: string
  statuses: SpotStatus[]
  rankLevels: number[]
  tagIds: string[]
  priceMin: string
  priceMax: string
}

function restoreFilters(): Partial<SavedFilters> {
  try {
    const raw = sessionStorage.getItem(FILTER_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as Partial<SavedFilters>) : {}
  } catch {
    return {}
  }
}

const restored = restoreFilters()
const text = (value: unknown): string => (typeof value === 'string' ? value : '')
const list = <T,>(value: unknown): T[] => (Array.isArray(value) ? (value as T[]) : [])

/** 输入框里的关键字（防抖后才发请求） */
const keyword = ref(text(restored.keyword))
/** 联系方式搜索框（电话可只敲数字，剥分隔符由服务端做） */
const contact = ref(text(restored.contact))
const province = ref(text(restored.province))
const city = ref(text(restored.city))
const district = ref(text(restored.district))
const statuses = ref<SpotStatus[]>(list<SpotStatus>(restored.statuses))
const rankLevels = ref<number[]>(list<number>(restored.rankLevels))
const tagIds = ref<string[]>(list<string>(restored.tagIds))
/** 价格区间，空串表示不限；只收数字 */
const priceMin = ref(text(restored.priceMin))
const priceMax = ref(text(restored.priceMax))

/* ---- 结果与分页 ---- */
/** 已加载的行（第 1 页 + 续页累加） */
const spots = ref<SpotListItem[]>([])
/** 命中条件的总条数，服务端给 */
const total = ref(0)
const currentPage = ref(0)
const hasMore = ref(false)
const loading = ref(true)
const loadingMore = ref(false)
const loadingMoreError = ref('')
const errorText = ref('')

/* ---- 筛选选项 ---- */
const regions = ref<SpotRegion[]>([])
const tagOptions = ref<TagItem[]>([])

function uniq(values: (string | undefined)[]): string[] {
  return [...new Set(values.filter((value): value is string => !!value?.trim()))]
}

const provinceOptions = computed(() => uniq(regions.value.map((region) => region.province)))
const cityOptions = computed(() =>
  uniq(
    regions.value
      .filter((region) => !province.value || region.province === province.value)
      .map((region) => region.city),
  ),
)
const districtOptions = computed(() =>
  uniq(
    regions.value
      .filter((region) => !province.value || region.province === province.value)
      .filter((region) => !city.value || region.city === city.value)
      .map((region) => region.district),
  ),
)

/**
 * 多选开关。三个列表各写一个「换新数组」的小函数：
 * computed 靠引用变化触发，原地 push/splice 筛不出来；模板里也拿不到 Ref，
 * 所以不用泛型 helper，直接在这里收口。
 */
function toggleStatus(value: SpotStatus): void {
  statuses.value = statuses.value.includes(value)
    ? statuses.value.filter((item) => item !== value)
    : [...statuses.value, value]
}

function toggleRank(level: number): void {
  rankLevels.value = rankLevels.value.includes(level)
    ? rankLevels.value.filter((item) => item !== level)
    : [...rankLevels.value, level]
}

function toggleTag(id: string): void {
  tagIds.value = tagIds.value.includes(id)
    ? tagIds.value.filter((item) => item !== id)
    : [...tagIds.value, id]
}

function toPrice(raw: string): number | null {
  const digits = raw.replace(/\D/g, '')
  return digits === '' ? null : Number(digits)
}

/** 只留数字：价格是正整数，别让人输入 88.5 或 abc（粘贴时同样处理） */
function onPriceInput(field: 'min' | 'max', event: Event): void {
  const el = event.target as HTMLInputElement
  const dot = el.value.search(/[.,]/)
  const digits = (dot >= 0 ? el.value.slice(0, dot) : el.value).replace(/\D/g, '').slice(0, 6)
  if (el.value !== digits) {
    el.value = digits
  }
  if (field === 'min') {
    priceMin.value = digits
  } else {
    priceMax.value = digits
  }
}

const hasFilter = computed(
  () =>
    province.value !== '' ||
    city.value !== '' ||
    district.value !== '' ||
    statuses.value.length > 0 ||
    rankLevels.value.length > 0 ||
    tagIds.value.length > 0 ||
    priceMin.value !== '' ||
    priceMax.value !== '' ||
    keyword.value.trim() !== '' ||
    contact.value.trim() !== '',
)

function resetFilters(): void {
  province.value = ''
  city.value = ''
  district.value = ''
  statuses.value = []
  rankLevels.value = []
  tagIds.value = []
  priceMin.value = ''
  priceMax.value = ''
  keyword.value = ''
  contact.value = ''
}

// 上一级换了，下一级的选择就没有意义了
watch(province, () => {
  city.value = ''
  district.value = ''
})
watch(city, () => {
  district.value = ''
})

/* ---- 取数 ---- */
let loadSeq = 0
let debounceTimer: number | undefined

function buildQuery(page: number): SpotPageQuery {
  return {
    page,
    size: PAGE_SIZE,
    statuses: statuses.value,
    rankLevels: rankLevels.value,
    tagIds: tagIds.value,
    province: province.value || undefined,
    city: city.value || undefined,
    district: district.value || undefined,
    priceMin: toPrice(priceMin.value) ?? undefined,
    priceMax: toPrice(priceMax.value) ?? undefined,
    keyword: keyword.value.trim() || undefined,
    contact: contact.value.trim() || undefined,
  }
}

/** 第 1 页 / 条件变化后的整表重载 */
async function loadFirst(): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  errorText.value = ''
  loadingMoreError.value = ''
  try {
    const data = await pageSpots(buildQuery(1))
    if (seq !== loadSeq) {
      return
    }
    spots.value = data.items
    total.value = data.total
    currentPage.value = data.page
    hasMore.value = data.hasMore
  } catch (error) {
    if (seq !== loadSeq) {
      return
    }
    spots.value = []
    total.value = 0
    currentPage.value = 0
    hasMore.value = false
    errorText.value = error instanceof HttpError ? error.message : '加载失败，请重试'
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

/** 续页：只追加，不重载已看过的行 */
async function loadMore(): Promise<void> {
  if (loading.value || loadingMore.value || !hasMore.value) {
    return
  }
  const seq = loadSeq
  loadingMore.value = true
  loadingMoreError.value = ''
  try {
    const data = await pageSpots(buildQuery(currentPage.value + 1))
    if (seq !== loadSeq) {
      return
    }
    // 翻页期间新增 / 改段位会让同一条落到两页，按 id 去重（否则 :key 冲突、行重复）
    const seen = new Set(spots.value.map((spot) => spot.id))
    spots.value = [...spots.value, ...data.items.filter((item) => !seen.has(item.id))]
    total.value = data.total
    currentPage.value = data.page
    hasMore.value = data.hasMore
  } catch (error) {
    if (seq !== loadSeq) {
      return
    }
    loadingMoreError.value = error instanceof HttpError ? error.message : '加载失败，请重试'
  } finally {
    loadingMore.value = false
  }
}

function persistFilters(): void {
  try {
    const snapshot: SavedFilters = {
      keyword: keyword.value,
      contact: contact.value,
      province: province.value,
      city: city.value,
      district: district.value,
      statuses: statuses.value,
      rankLevels: rankLevels.value,
      tagIds: tagIds.value,
      priceMin: priceMin.value,
      priceMax: priceMax.value,
    }
    sessionStorage.setItem(FILTER_STORAGE_KEY, JSON.stringify(snapshot))
  } catch {
    // 隐私模式 / 配额满：记不住就算了，不影响查询
  }
}

/** 条件的指纹：任何一项变了就回第 1 页重查（防抖 300ms，别让每次按键都发请求） */
const filterKey = computed(() =>
  JSON.stringify([
    statuses.value,
    rankLevels.value,
    tagIds.value,
    province.value,
    city.value,
    district.value,
    priceMin.value,
    priceMax.value,
    keyword.value.trim(),
    contact.value.trim(),
  ]),
)

watch(filterKey, () => {
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => {
    persistFilters()
    window.scrollTo({ top: 0 })
    void loadFirst()
  }, 300)
})

async function loadMeta(): Promise<void> {
  const [regionResult, tagResult] = await Promise.allSettled([listSpotRegions(), listTags()])
  if (regionResult.status === 'fulfilled') {
    regions.value = regionResult.value
  }
  if (tagResult.status === 'fulfilled') {
    tagOptions.value = tagResult.value
      .filter((tag) => tag.useCount > 0)
      .sort((a, b) => b.useCount - a.useCount)
  }
}

/* ---- 来回详情页要落回原位（否则每次回来都从头刷） ---- */
interface ScrollSnapshot {
  y: number
  count: number
}

function readScrollSnapshot(): ScrollSnapshot | null {
  try {
    const raw = sessionStorage.getItem(SCROLL_STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as Partial<ScrollSnapshot>
    if (typeof parsed.y === 'number' && typeof parsed.count === 'number' && parsed.count > 0) {
      return { y: parsed.y, count: parsed.count }
    }
  } catch {
    // 存的东西坏了就当没有，不要因此打不开列表
  }
  return null
}

function saveScrollSnapshot(): void {
  // 还在加载 / 没有结果时不写：免得用空列表覆盖掉上一次有效的快照
  if (loading.value || spots.value.length === 0) {
    return
  }
  try {
    sessionStorage.setItem(
      SCROLL_STORAGE_KEY,
      JSON.stringify({ y: Math.max(window.scrollY, 0), count: spots.value.length }),
    )
  } catch {
    // 隐私模式 / 配额满：记不住就算了
  }
}

/**
 * 回到上次的位置：
 *   1) 先把条数续回去（第 2 页起逐页拉），否则文档高度不够，scrollTo 会被夹到底部
 *   2) 再等一帧滚过去 —— 行是刚渲染的，早滚会滚不动
 * 快照只恢复一次，恢复完就删：之后正常进入列表仍是从头开始。
 */
async function restoreScroll(snapshot: ScrollSnapshot): Promise<void> {
  sessionStorage.removeItem(SCROLL_STORAGE_KEY)
  if (errorText.value) {
    return
  }
  let guard = 0
  while (spots.value.length < snapshot.count && hasMore.value && guard < MAX_RESTORE_PAGES) {
    await loadMore()
    guard += 1
  }
  await nextTick()
  window.scrollTo({ top: snapshot.y })
}

onMounted(async () => {
  // 选项加载失败不该挡住列表本身，所以分开请求、静默失败
  void loadMeta()
  const snapshot = readScrollSnapshot()
  await loadFirst()
  if (snapshot) {
    await restoreScroll(snapshot)
  }
})

onBeforeUnmount(() => {
  window.clearTimeout(debounceTimer)
  // 离开（进详情 / 切到别的 Tab）前记下位置；卸载时还在原页面，scrollY 是准的
  saveScrollSnapshot()
})
</script>

<template>
  <div class="list">
    <header class="list__head">
      <h1 class="page-title">列表</h1>
      <p class="meta">
        <template v-if="hasFilter">筛出 {{ total }} 个</template>
        <template v-else>共 {{ total }} 个</template>
      </p>
    </header>

    <section class="card filters">
      <label class="field">
        <span class="field__label">联系方式</span>
        <input
          v-model="contact"
          class="field__input"
          type="search"
          placeholder="电话 / 微信 / QQ / TG，电话可只敲数字"
        />
      </label>

      <label class="field">
        <span class="field__label">名称 / 地址</span>
        <input v-model="keyword" class="field__input" type="search" placeholder="模糊匹配，如「武汉」「咖啡」" />
      </label>

      <div class="field field--geo">
        <span class="field__label">省市区</span>
        <div class="geo">
          <select v-model="province" class="field__select">
            <option value="">全部省份</option>
            <option v-for="item in provinceOptions" :key="item" :value="item">{{ item }}</option>
          </select>
          <select v-model="city" class="field__select">
            <option value="">全部城市</option>
            <option v-for="item in cityOptions" :key="item" :value="item">{{ item }}</option>
          </select>
          <select v-model="district" class="field__select">
            <option value="">全部区县</option>
            <option v-for="item in districtOptions" :key="item" :value="item">{{ item }}</option>
          </select>
        </div>
      </div>

      <div class="fgroup">
        <span class="field__label">状态</span>
        <div class="chips">
          <button
            v-for="item in SPOT_STATUSES"
            :key="item.value"
            type="button"
            class="chip"
            :class="{ 'chip--on': statuses.includes(item.value) }"
            @click="toggleStatus(item.value)"
          >
            {{ item.label }}
          </button>
        </div>
      </div>

      <div class="fgroup">
        <span class="field__label">等级</span>
        <div class="chips">
          <button
            v-for="rank in RANKS"
            :key="rank.level"
            type="button"
            class="chip"
            :class="{ 'chip--on': rankLevels.includes(rank.level) }"
            @click="toggleRank(rank.level)"
          >
            {{ rank.name }}
          </button>
        </div>
      </div>

      <div v-if="tagOptions.length" class="fgroup">
        <span class="field__label">标签</span>
        <div class="chips">
          <button
            v-for="tag in tagOptions"
            :key="tag.id"
            type="button"
            class="chip"
            :class="{ 'chip--on': tagIds.includes(tag.id) }"
            @click="toggleTag(tag.id)"
          >
            {{ tag.name }}
          </button>
        </div>
      </div>

      <div class="fgroup">
        <span class="field__label">价格</span>
        <div class="price">
          <input
            :value="priceMin"
            class="field__input price__input"
            type="text"
            inputmode="numeric"
            placeholder="最低"
            @input="onPriceInput('min', $event)"
          />
          <span class="price__dash">—</span>
          <input
            :value="priceMax"
            class="field__input price__input"
            type="text"
            inputmode="numeric"
            placeholder="最高"
            @input="onPriceInput('max', $event)"
          />
          <span class="meta">元</span>
          <button v-if="hasFilter" type="button" class="filters__reset" @click="resetFilters">
            重置
          </button>
        </div>
      </div>
    </section>

    <div v-if="loading" class="list__state">
      <p class="meta">加载中…</p>
    </div>

    <div v-else-if="errorText" class="list__state">
      <p class="meta">{{ errorText }}</p>
      <button class="btn" type="button" @click="loadFirst">重新加载</button>
    </div>

    <div v-else-if="spots.length === 0" class="list__state">
      <p class="meta">{{ hasFilter ? '没有符合条件的打卡点' : '还没有打卡点，先去地图上记一个。' }}</p>
      <button v-if="hasFilter" class="btn" type="button" @click="resetFilters">清空条件</button>
      <RouterLink v-else class="btn" to="/">去地图</RouterLink>
    </div>

    <template v-else>
      <section class="rows">
        <SpotRow v-for="spot in spots" :key="spot.id" :spot="spot" />
      </section>

      <div class="list__more">
        <p v-if="loadingMoreError" class="meta list__more__err">{{ loadingMoreError }}</p>
        <button
          v-if="hasMore"
          class="btn list__more__btn"
          type="button"
          :disabled="loadingMore"
          @click="loadMore"
        >
          {{ loadingMore ? '加载中…' : '加载更多' }}
        </button>
        <p class="meta">已显示 {{ spots.length }} / 共 {{ total }} 个</p>
      </div>
    </template>

    <TabBar active="list" />
  </div>
</template>

<style scoped>
.list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 720px;
  min-height: 100dvh;
  margin: 0 auto;
  padding: 18px 16px 92px;
}

.list__head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* ---- 查询条件 ---- */
.filters {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field__label {
  color: var(--ink3);
  font-size: 11.5px;
  font-weight: 600;
}

.field__input {
  width: 100%;
  height: 38px;
  padding: 0 12px;
  border: 1px solid var(--line2);
  border-radius: 9px;
  background: var(--bg);
  color: var(--ink);
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
}

.field__input::placeholder {
  color: var(--ink3);
}

.field__select {
  flex: 1;
  min-width: 0;
  height: 38px;
  padding: 0 8px;
  border: 1px solid var(--line2);
  border-radius: 9px;
  background: var(--bg);
  color: var(--ink);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 500;
}

.geo {
  display: flex;
  gap: 6px;
}

.fgroup {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 5px 10px;
  border: 0;
  border-radius: 999px;
  background: var(--bg);
  color: var(--ink2);
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
}

.chip--on {
  background: var(--ink);
  color: #fff;
}

.price {
  display: flex;
  gap: 6px;
  align-items: center;
}

.price__input {
  width: 76px;
  flex: none;
  text-align: center;
}

.price__dash {
  color: var(--ink3);
  font-size: 12px;
}

.filters__reset {
  margin-left: auto;
  padding: 6px 12px;
  border: 0;
  border-radius: 999px;
  background: var(--bg);
  color: var(--ink2);
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

/* ---- 结果 ---- */
.rows {
  display: flex;
  flex-direction: column;
}

.list__state {
  display: flex;
  flex-direction: column;
  gap: 14px;
  align-items: center;
  padding: 40px 16px;
  text-align: center;
}

.list__state .meta {
  max-width: 260px;
  line-height: 1.8;
}

.list__state .btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 44px;
  padding: 0 22px;
  text-decoration: none;
}

/* ---- 续页 ---- */
.list__more {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  padding: 6px 0 4px;
}

.list__more__btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 44px;
  padding: 0 22px;
}

.list__more__btn:disabled {
  opacity: 0.6;
}

.list__more__err {
  color: var(--hot);
}
</style>
