<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import RankBar from '@/components/RankBar.vue'
import PhotoLightbox from '@/components/PhotoLightbox.vue'
import ActionSheet from '@/components/ActionSheet.vue'
import {
  getSpot,
  updateSpot,
  type SpotContactType,
  type SpotDetail,
  type SpotSaveRequest,
} from '@/api/spots'
import { listVisits, type VisitItem } from '@/api/visits'
import { HttpError } from '@/api/http'
import { appScheme, contactMeta, dialNumber, groupContacts } from '@/config/contacts'
import { rankOf } from '@/config/ranks'
import { leaveSpotPage } from '@/router/spotEntry'
import { statusMeta } from '@/config/status'
import { resolveTagColor } from '@/utils/tagColor'

/**
 * 打卡点详情。
 *
 * 一个 `GET /api/spots/{id}` 就能渲染整页：标签、挂靠人物、到访统计后端已一次给全，
 * **不要**为了「所属场所名」「标签名」这类东西再补请求（AGENTS.md 第 12 条的 N+1 禁忌）。
 *
 * 版面要点（视觉基准 `designs/打卡地图/选稿/详情改版/detail-top.png`）：
 *   - 有封面：hero 图，头像与标题同行，状态是压在封面上的白色胶囊，白卡负 margin 浮起来
 *   - 没封面：标题落回白卡，状态胶囊反成墨黑实底（白底上没有白色可依托）
 *   - 概览卡三格（评分 / 段位 / 到访次数）+ 7 段通栏刻度条 + 标签
 *   - 状态**不与标签同排**：状态是「这个点的属性」，标签是「分类」，混在一起会被读成同一类
 *   - 标签最多 8 个，左对齐自然换行，不截断（AGENTS.md 第 19 条）
 */
const route = useRoute()
const router = useRouter()

const spotId = computed(() => String(route.params.id))
const spot = ref<SpotDetail | null>(null)
const visits = ref<VisitItem[]>([])
const loading = ref(true)
const adopting = ref(false)
const errorText = ref('')

/** 段位色只能从 ranks.ts 取，这里只读不用改 */
const rank = computed(() => (spot.value ? rankOf(spot.value.rankLevel) : null))

const avgRatingText = computed(() => {
  const value = spot.value?.avgRating
  return value === undefined || value === null ? '—' : value.toFixed(1)
})

/** 状态徽标：名称与配色都从 config/status.ts 取 */
const statusBadge = computed(() => (spot.value ? statusMeta(spot.value.status) : null))

/** 标题下的副标题：类型 + 城市；到访次数下面三格已经有了，不重复 */
const heroMeta = computed(() => {
  const current = spot.value
  if (!current) return ''
  const parts = [current.kind === 'person' ? '人物' : '场所']
  if (current.city) parts.push(current.city)
  return parts.join(' · ')
})

/** 位置卡「省市区」行：省 / 市 / 区，缺项跳过 */
const regionLine = computed(() => {
  const current = spot.value
  if (!current) return ''
  return [current.province, current.city, current.district].filter((part) => !!part).join(' ')
})

/** 经纬度都填了才叫「有坐标」，否则地图链接打不开 */
const hasCoords = computed(() => {
  const lat = spot.value?.lat
  const lng = spot.value?.lng
  return typeof lat === 'number' && typeof lng === 'number'
})

/**
 * 高德地图链接，两条：
 *   - amapUrl       网页版（uri.amap.com），桌面端与「没装高德 App」时的兜底
 *   - amapNativeUrl 原生 scheme，手机上直接唤起 App，跳过网页那层「打开高德地图」的二次点击
 * name 固定为「打卡点」：这是外链传给高德的标记名/导航目的地名，
 * 不带点位标题，避免点位名被高德 App 的收藏与搜索历史当成地点名留存。
 * 坐标已是 GCJ-02，所以网页版传 coordinate=gcj02、scheme 传 dev=0（不走坐标转换）。
 */
const AMAP_MARKER_NAME = '打卡点'
const amapUrl = computed(() => {
  if (!hasCoords.value) return ''
  const lat = spot.value!.lat as number
  const lng = spot.value!.lng as number
  const name = encodeURIComponent(AMAP_MARKER_NAME)
  return `https://uri.amap.com/marker?position=${lng},${lat}&name=${name}&src=visit&coordinate=gcj02&callnative=1`
})
const amapNativeUrl = computed(() => {
  if (!hasCoords.value) return ''
  const lat = spot.value!.lat as number
  const lng = spot.value!.lng as number
  const query = `sourceApplication=visit&poiname=${encodeURIComponent(AMAP_MARKER_NAME)}&lat=${lat}&lon=${lng}&dev=0`
  // iOS 与 Android 的 scheme 前缀不同，别的系统（含鸿蒙浏览器）统一按 Android 试
  return /iPad|iPhone|iPod/i.test(navigator.userAgent)
    ? `iosamap://viewMap?${query}`
    : `androidamap://viewMap?${query}`
})

/**
 * 点「在地图中查看」：
 *   手机 → 只唤起原生 App，**不做网页版兜底**：没装高德 / 被浏览器拦截时就停在原页，
 *          绝不把用户甩到 uri.amap.com 那层网页（那是本次明确要去掉的二次点击）
 *   桌面 → 不拦，交给 <a href> 走网页版（电脑上不存在 App，这是唯一可用的通道）
 */
function openInAmap(event: MouseEvent): void {
  if (!amapNativeUrl.value) return
  if (!/Android|iPhone|iPad|iPod|HarmonyOS/i.test(navigator.userAgent)) return

  event.preventDefault()
  window.location.href = amapNativeUrl.value
}

/**
 * 位置卡地址：剥掉省/市/区前缀，只留街道门牌。
 * 反查回来的地址常是「江苏省南京市玄武区中山路123号」，卡片里不需要省市区（跟地图重复）。
 */
const addressLine = computed(() => {
  const current = spot.value
  if (!current?.address) return ''
  const parts = [current.province, current.city, current.district].filter((part) => !!part) as string[]
  if (parts.length) {
    // 反查结果可能带空格也可能不带，两种前缀都试一遍
    for (const sep of ['', ' ']) {
      const prefix = parts.join(sep)
      if (prefix && current.address.startsWith(prefix)) {
        return current.address.slice(prefix.length).replace(/^[省市区县\s]+/, '') || current.address
      }
    }
  }
  return current.address
})

/** 省市区、地址、坐标任一存在就展示位置卡 */
const hasLocation = computed(
  () =>
    !!(spot.value?.province || spot.value?.city || spot.value?.district || spot.value?.address) ||
    hasCoords.value,
)

/** 打卡点自己的照片（与到访记录照片分开） */
const photos = computed(() => spot.value?.photos ?? [])

/**
 * 照片列数按数量走，不写死 3 列：
 *   1 张 → 全宽（半宽只剩 155px，看不清）
 *   2~4 张 → 2 列（3 列时第 4 张孤零零留在第二行，中间两个洞）
 *   5 张以上 → 3 列九宫格
 */
const photoLayout = computed<'single' | 'pair' | 'grid'>(() => {
  const count = photos.value.length
  if (count === 1) return 'single'
  if (count <= 4) return 'pair'
  return 'grid'
})

interface ContactRow {
  /** v-for 的 key 与「已复制」态：同类型可以多条（两个 QQ / 两个手机号），必须带序号 */
  key: string
  type: SpotContactType
  value: string
}

/**
 * 只留填过的联系方式，没填的不占位（详情页不留空行）。
 *
 * **全部在卡里铺开，不做折叠、不再跳页**：按平台分组（QQ / 微信 / Telegram / 与你 / 电话），
 * 每条后面跟一个「复制」。分组顺序与平台名只来自 `config/contacts.ts`，页面里不硬编码。
 */
const contacts = computed<ContactRow[]>(() =>
  (spot.value?.contacts ?? [])
    .filter((item) => !!item.value?.trim())
    .map((item, index) => ({ key: `${item.type}-${index}`, type: item.type, value: item.value })),
)

const contactGroups = computed(() => groupContacts(contacts.value))

/** 当前在底部选项框里展开的那一条；null = 选项框关着 */
const sheetRow = ref<ContactRow | null>(null)

/**
 * 选项框只留三条：打开 XXX / 复制 / 取消。
 *
 * 「打开」会**顺带复制**（微信 QQ TG 只能靠粘贴或协议直达），所以不再单列「复制并打开」；
 * 电话没有可复制的去处，第一条就是「拨打电话」。
 */
const sheetActions = computed(() => {
  const row = sheetRow.value
  if (!row) return []
  const meta = contactMeta(row.type)
  return [
    { key: 'open', label: meta.action === 'dial' ? '拨打电话' : `打开${meta.label}` },
    { key: 'copy', label: '复制' },
  ]
})

function openSheet(row: ContactRow): void {
  sheetRow.value = row
}

function onSheetSelect(key: string): void {
  const row = sheetRow.value
  // 先关面板再执行：拉起 App / 弹提示时面板不该还盖着
  sheetRow.value = null
  if (!row) return
  if (key === 'copy') {
    void copyContact(row)
    return
  }
  void openContact(row)
}

/**
 * 去对应的 App（选项框里点了「打开 / 拨号」之后走这里）：
 *
 *   电话  → 直接拨号（点电话的意图就是打）
 *   微信  → 复制 + 拉起微信（微信没有「带关键字打开搜索」的公开 scheme，只能粘贴）
 *   QQ / Telegram → 复制 + 直达账号的协议
 *   与你  → 复制 + 打开 `yuni://`（值填整条二维码链接则原样打开）
 */
async function openContact(row: ContactRow): Promise<void> {
  const meta = contactMeta(row.type)

  if (meta.action === 'dial') {
    window.location.href = `tel:${dialNumber(row.value)}`
    return
  }

  // 不 await 复制再跳转：iOS Safari 会把「await 之后」算作失去用户手势，
  // 那样 weixin:// 会被拦。先把协议发出去（仍在手势内），再回来处理复制结果。
  const copying = navigator.clipboard.writeText(row.value)

  const scheme = appScheme(row.type, row.value)
  if (scheme) {
    // 微信会被拉到前台，但搜索框要用户自己粘贴（没有带关键字进搜索的公开协议），这句提示不能省
    if (row.type === 'wechat') {
      ElMessage.info('微信号已复制，打开微信后粘贴到搜索框')
    }
    // 移动端能直接唤起对应 App；桌面端没有该 App 时浏览器会忽略，不影响复制
    window.location.href = scheme
  }

  try {
    await copying
    if (!scheme) {
      ElMessage.info(`${meta.label}已复制，到 App 里粘贴搜索`)
    }
  } catch {
    ElMessage.error('复制失败，请长按手动选择')
  }
}

/** 只复制，不拉起 App */
async function copyContact(row: ContactRow): Promise<void> {
  try {
    await navigator.clipboard.writeText(row.value)
    ElMessage.success('已复制')
  } catch {
    // 非 HTTPS 或浏览器不给剪贴板权限：只剩「长按选中」这条路
    ElMessage.error('复制失败，请长按手动选择')
  }
}

/**
 * 建议段位入口（PRD 5.1「重算」）。
 *
 * 只在「算得出建议值」且「和当前段位不一样」时露出来。手不手动锁定都显示 ——
 * 锁定只代表系统不擅自改，用户主动点一下仍然可以采纳（那正是「重算」）。
 */
const suggestedRank = computed(() => {
  const current = spot.value
  if (!current) return null
  const suggested = current.suggestedRankLevel
  if (suggested === undefined || suggested === current.rankLevel) return null
  return rankOf(suggested)
})





function visitDate(iso: string): string {
  // 不用 replaceAll：tsconfig 的 lib 是 ES2020，用不上
  return iso.replace(/-/g, '.')
}

function visitScores(visit: VisitItem): string {
  // 「不适用」的项存的是 null，而全局 non_null 会把它整个字段省掉 → 收到的是 undefined，
  // 所以只能用 typeof 判断，不能写成 !== null
  return (visit.scores ?? [])
    .filter((item) => typeof item.value === 'number')
    .map((item) => `${item.label} ${item.value}`)
    .join(' · ')
}

/** 采纳建议段位要走整体提交（PUT 语义），所以先把详情翻成表单结构再打补丁 */
function toSaveRequest(current: SpotDetail, patch: Partial<SpotSaveRequest>): SpotSaveRequest {
  return {
    name: current.name,
    kind: current.kind,
    status: current.status,
    rankLevel: current.rankLevel,
    rankManual: current.rankManual,
    // 兜底值（没设头像/封面时取的第一张照片）不能回存，否则等于把用户没设过的东西写进库
    avatar: current.avatarFromPhotos ? null : current.avatar ?? null,
    cover: current.coverFromPhotos ? null : current.cover ?? null,
    description: current.description ?? null,
    lat: current.lat ?? null,
    lng: current.lng ?? null,
    address: current.address ?? null,
    city: current.city ?? null,
    // 联系方式是整体提交：不带上就等于把用户填的全清了
    contacts: (current.contacts ?? []).map((item) => ({ type: item.type, value: item.value })),
    packages: current.packages ?? [],
    parentSpotId: current.parentSpotId ?? null,
    tagIds: (current.tags ?? []).map((tag) => tag.id),
    ...patch,
  }
}

async function adoptRank(): Promise<void> {
  const current = spot.value
  const suggested = current?.suggestedRankLevel
  if (!current || suggested === undefined || adopting.value) return
  adopting.value = true
  try {
    // rankManual 交回 false：采纳的是「系统的建议」，之后到访均分变了还能继续更新
    await updateSpot(current.id, toSaveRequest(current, { rankLevel: suggested, rankManual: false }))
    ElMessage.success('已按到访记录更新段位')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof HttpError ? error.message : '更新失败，请重试')
  } finally {
    adopting.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  spot.value = null
  visits.value = []
  try {
    const [detail, visitList] = await Promise.all([
      getSpot(spotId.value),
      listVisits(spotId.value),
    ])
    spot.value = detail
    visits.value = visitList
  } catch (error) {
    errorText.value = error instanceof HttpError ? error.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

/**
 * 返回：人物回它挂靠的场所（数据关系），其余回「上一次入口」——
 * 列表页来的回列表、统计页来的回统计，没有来历才回首页地图，不要无脑回首页。
 */
function goBack(): void {
  const parentId = spot.value?.parentSpotId
  if (parentId) {
    void router.replace(`/spot/${parentId}`)
    return
  }
  leaveSpotPage(router)
}

/* ---------------- 照片灯箱 ---------------- */
const lightboxOpen = ref(false)
const lightboxStart = ref(0)

function openPhoto(index: number): void {
  lightboxStart.value = index
  lightboxOpen.value = true
}

// 从「挂靠人物」点进另一个详情时组件会被复用，只挂 onMounted 不会重新拉数据
watch(spotId, load, { immediate: true })
</script>

<template>
  <div class="detail">
    <div v-if="loading" class="detail__state">
      <p class="meta">加载中…</p>
    </div>

    <div v-else-if="errorText" class="detail__state">
      <p class="state__title">加载失败</p>
      <p class="state__desc">{{ errorText }}</p>
      <button class="btn state__retry" type="button" @click="load">重试</button>
      <button class="state__link" type="button" @click="goBack">返回</button>
    </div>

    <template v-else-if="spot">
      <header v-if="spot.cover" class="hero">
        <img class="hero__img" :src="spot.cover" :alt="spot.name" />
        <button class="icon-btn hero__back" type="button" aria-label="返回" @click="goBack">
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M15 18 9 12l6-6" />
          </svg>
        </button>

        <div class="hero__caption">
          <div class="hero__id">
            <img
              v-if="spot.avatar"
              class="hero__avatar"
              :src="spot.avatar"
              :alt="`${spot.name} 的头像`"
              decoding="async"
            />
            <div class="hero__text">
              <div class="nameline">
                <h1 class="hero__title">{{ spot.name }}</h1>
                <span v-if="statusBadge" class="hero__status">
                  <i :style="{ background: statusBadge.background }" />
                  {{ statusBadge.label }}
                </span>
              </div>
              <p v-if="heroMeta" class="hero__meta">{{ heroMeta }}</p>
            </div>
          </div>
        </div>
      </header>

      <div class="detail__body">
        <div v-if="!spot.cover" class="topbar">
          <button class="icon-btn" type="button" aria-label="返回" @click="goBack">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M15 18 9 12l6-6" />
            </svg>
          </button>
        </div>

        <section class="card summary" :class="{ 'summary--float': !!spot.cover }">
          <!-- 没封面时标题落在卡里，状态胶囊反成墨黑实底 -->
          <div v-if="!spot.cover" class="summary__head">
            <div class="nameline">
              <h2 class="summary__title">{{ spot.name }}</h2>
              <span v-if="statusBadge" class="status-pill">
                <i />
                {{ statusBadge.label }}
              </span>
            </div>
            <p v-if="heroMeta" class="meta">{{ heroMeta }}</p>
          </div>

          <div class="stats">
            <div class="stats__cell">
              <span class="stats__val">
                <strong class="num stats__num" :class="{ 'stats__num--empty': avgRatingText === '—' }">
                  {{ avgRatingText }}
                </strong>
              </span>
              <span class="meta">整体评分</span>
            </div>

            <div class="stats__cell">
              <span class="stats__val">
                <strong class="stats__rank" :style="{ color: rank ? rank.color : undefined }">
                  {{ rank ? rank.name : '未定级' }}
                </strong>
              </span>
              <span class="meta">段位</span>
            </div>

            <div class="stats__cell">
              <span class="stats__val">
                <strong class="num stats__num">{{ spot.visitCount }}</strong>
              </span>
              <span class="meta">到访次数</span>
            </div>
          </div>

          <!-- 7 段通栏条：宽度跟卡片走，段位数靠长度差读，不靠颜色 -->
          <div v-if="rank" class="rankline">
            <RankBar :level="spot.rankLevel" size="fluid" />
          </div>

          <div v-if="suggestedRank" class="suggest-wrap">
            <button class="suggest" type="button" :disabled="adopting" @click="adoptRank">
              按到访记录建议：{{ suggestedRank.name }} →
            </button>
          </div>

          <!-- 标签最多 8 个，左对齐自然换行；不截断（AGENTS.md 第 19 条） -->
          <div v-if="spot.tags?.length" class="chips">
            <span
              v-for="tag in spot.tags"
              :key="tag.id"
              class="chip chip--color"
              :style="{ '--tag-color': resolveTagColor(tag) }"
            >
              <i class="chip__dot" />
              {{ tag.name }}
            </span>
          </div>
        </section>

        <section v-if="hasLocation" class="card block">
          <div class="block__title">
            <h2 class="section-title">位置</h2>
            <span v-if="regionLine" class="meta loc__region">{{ regionLine }}</span>
          </div>
          <p v-if="addressLine" class="loc__addr">{{ addressLine }}</p>
          <p v-else-if="!regionLine" class="meta">未填写详细地址</p>
          <a v-if="hasCoords" class="loc__map" :href="amapUrl" target="_blank" rel="noopener"
             @click="openInAmap">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M12 21s7-5.6 7-11a7 7 0 1 0-14 0c0 5.4 7 11 7 11z" />
              <circle cx="12" cy="10" r="2.4" />
            </svg>
            <span>在地图中查看</span>
            <svg class="loc__map__go" width="16" height="16" viewBox="0 0 24 24" fill="none"
                 stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="m9 18 6-6-6-6" />
            </svg>
          </a>
        </section>

        <section v-if="photos.length" class="card block">
          <div class="block__title">
            <h2 class="section-title">照片</h2>
            <span class="meta meta--dim">{{ photos.length }} 张</span>
          </div>
          <div class="photogrid" :class="`photogrid--${photoLayout}`">
            <button
              v-for="(photo, index) in photos"
              :key="photo.id"
              class="photogrid__item"
              type="button"
              :aria-label="`查看第 ${index + 1} 张照片`"
              @click="openPhoto(index)"
            >
              <img :src="photo.thumb" :alt="spot.name" loading="lazy" decoding="async" />
            </button>
          </div>
        </section>

        <section v-if="spot.description" class="card block">
          <h2 class="section-title">描述</h2>
          <p class="desc">{{ spot.description }}</p>
        </section>

        <section v-if="spot.packages?.length" class="card block">
          <div class="block__title">
            <h2 class="section-title">套餐</h2>
            <span class="meta meta--dim">{{ spot.packages.length }} 项</span>
          </div>
          <div v-for="(item, index) in spot.packages" :key="index" class="price-row">
            <span class="price-row__name">{{ item.name }}</span>
            <span class="price-row__leader" />
            <span v-if="item.price === null || item.price === undefined" class="meta price-row__na">未标价</span>
            <span v-else class="num price-row__num">¥{{ item.price }}</span>
          </div>
        </section>

        <!-- 全部在卡内铺开：按平台分组，每条后面跟一个「复制」，不做折叠也不跳页 -->
        <section v-if="contacts.length" class="card block">
          <div class="block__title">
            <h2 class="section-title">联系方式</h2>
            <span class="meta meta--dim">{{ contacts.length }} 项</span>
          </div>
          <p class="meta meta--dim contact-hint">点一条，在底部选「打开」或「复制」</p>

          <div v-for="group in contactGroups" :key="group.type" class="cgroup">
            <div class="cgroup__head">
              <span class="cgroup__badge" :class="`cgroup__badge--${group.type}`">{{ group.badge }}</span>
              <span class="cgroup__label">{{ group.label }}</span>
            </div>

            <!-- 点一条弹底部选项框（打开 App / 仅复制），不在这里分成两个按钮 -->
            <button
              v-for="row in group.items"
              :key="row.key"
              class="crow"
              type="button"
              :aria-label="`${row.value}，选择操作`"
              @click="openSheet(row)"
            >
              <span class="crow__dot" aria-hidden="true" />
              <span class="crow__value">{{ row.value }}</span>
              <span class="crow__chevron" aria-hidden="true">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="m9 18 6-6-6-6" />
                </svg>
              </span>
            </button>
          </div>
        </section>

        <section v-if="visits.length" class="card block">
          <div class="block__title">
            <h2 class="section-title">到访记录</h2>
            <span class="meta meta--dim">{{ visits.length }} 次</span>
          </div>
          <RouterLink
            v-for="visit in visits"
            :key="visit.id"
            class="visit-row"
            :to="`/visit/${visit.id}/edit`"
          >
            <div class="visit-row__head">
              <span class="meta">{{ visitDate(visit.visitedAt) }}</span>
              <span class="num visit-row__rating">{{ visit.rating }} 星</span>
            </div>
            <p v-if="visit.note" class="visit-row__note">{{ visit.note }}</p>
            <div class="visit-row__foot">
              <span v-if="visitScores(visit)" class="meta">{{ visitScores(visit) }}</span>
              <span v-if="visit.cost !== undefined" class="meta">¥{{ visit.cost }}</span>
              <span v-if="visit.companions" class="meta">与 {{ visit.companions }}</span>
            </div>
          </RouterLink>
        </section>

        <section v-if="spot.parentSpotName" class="card block">
          <RouterLink class="parent" :to="`/spot/${spot.parentSpotId}`">
            <span class="meta">所属场所</span>
            <span class="parent__name">{{ spot.parentSpotName }} →</span>
          </RouterLink>
        </section>

        <section v-if="spot.children?.length" class="card block">
          <h2 class="section-title">挂靠的人物</h2>
          <RouterLink v-for="child in spot.children" :key="child.id" class="child" :to="`/spot/${child.id}`">
            <img v-if="child.avatar" class="child__avatar" :src="child.avatar" :alt="child.name" />
            <span v-else class="child__avatar child__avatar--text">{{ child.name.slice(0, 1) }}</span>
            <span class="child__name">{{ child.name }}</span>
            <RankBar :level="child.rankLevel" />
          </RouterLink>
        </section>
      </div>

      <footer class="detail__actions">
        <RouterLink class="btn btn--ghost detail__edit" :to="`/spot/${spot.id}/edit`">编辑</RouterLink>
        <RouterLink class="btn btn--hot detail__visit" :to="`/spot/${spot.id}/visit/new`">
          记一次到访
        </RouterLink>
      </footer>

      <PhotoLightbox
        :photos="photos"
        :visible="lightboxOpen"
        :start="lightboxStart"
        @close="lightboxOpen = false"
      />

      <!-- 联系方式：点一条在底部弹出「打开 / 复制 / 取消」 -->
      <ActionSheet
        :visible="!!sheetRow"
        :actions="sheetActions"
        @select="onSheetSelect"
        @close="sheetRow = null"
      />
    </template>
  </div>
</template>

<style scoped>
.detail {
  min-height: 100%;
  /* 给底部固定操作栏留位置，否则最后一张卡被压住 */
  padding-bottom: 96px;
}

/* 关键标注提到 12px / --ink2：全局 .meta 是 11.5px / --ink3，对比只有 2.59:1 */
.detail .meta {
  font-size: 12px;
  color: var(--ink2);
}

/* 真正可省的辅助信息（照片张数、套餐项数）才留在 --ink3 */
.detail .meta--dim {
  color: var(--ink3);
}

.detail__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 70vh;
  padding: 24px;
}

.state__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.state__desc {
  margin: 0;
  font-size: 13px;
  color: var(--ink2);
  text-align: center;
}

.state__retry {
  width: 160px;
  margin-top: 6px;
}

.state__link {
  padding: 10px;
  border: 0;
  background: transparent;
  color: var(--ink2);
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.detail__body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  max-width: 720px;
  margin: 0 auto;
}

/* ---- 无封面时的返回行 ---- */
.topbar {
  display: flex;
}

/* 视觉 38，点击区 48（::after 外扩 5px） */
.icon-btn {
  position: relative;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: var(--sh);
  color: var(--ink);
  cursor: pointer;
}

.icon-btn::after {
  content: '';
  position: absolute;
  inset: -5px;
}

/* ---- hero：只在有封面时出现 ---- */
.hero {
  position: relative;
  max-width: 720px;
  margin: 0 auto;
  height: 248px;
  overflow: hidden;
  background: var(--line2);
}

/* 比原来更深：封面偏亮（天空、雪景）时白字标题也要压得住 */
.hero::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    to top,
    rgba(17, 19, 24, 0.88) 0%,
    rgba(17, 19, 24, 0.58) 30%,
    rgba(17, 19, 24, 0) 64%
  );
}

.hero__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.hero__back {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 3;
}

.hero__caption {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 54px;
  z-index: 2;
}

.hero__id {
  display: flex;
  align-items: center;
  gap: 12px;
}

.hero__avatar {
  flex: none;
  width: 54px;
  height: 54px;
  border-radius: 50%;
  object-fit: cover;
  background: rgba(255, 255, 255, 0.2);
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.9), 0 6px 16px -10px rgba(17, 19, 24, 0.6);
}

.hero__text {
  min-width: 0;
}

.nameline {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.hero__title {
  margin: 0;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.035em;
  line-height: 1.15;
  color: #fff;
  text-shadow: 0 1px 12px rgba(17, 19, 24, 0.4);
}

/*
 * 状态压在封面上，跟返回按钮共用「白色浮层」这套语言。
 * 状态色只留在 6px 圆点上 —— 整颗按状态色上色的话，
 * 「想打卡」会变成橙底白字，概览卡里就多出第二块主色。
 */
.hero__status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: none;
  padding: 3px 9px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.94);
  color: var(--ink);
  font-size: 11px;
  font-weight: 700;
  line-height: 1.45;
  white-space: nowrap;
  box-shadow: 0 2px 8px -4px rgba(17, 19, 24, 0.5);
}

.hero__status i {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.hero__meta {
  margin: 6px 0 0;
  font-size: 11.5px;
  font-weight: 500;
  letter-spacing: 0.01em;
  color: rgba(255, 255, 255, 0.82);
}

/* ---- 概览卡 ---- */
.summary {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
}

/* 有封面时压住 hero 底部，制造层次 */
.summary--float {
  margin-top: -30px;
  z-index: 1;
}

.summary__head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.summary__head p {
  margin: 0;
}

.summary__title {
  margin: 0;
  min-width: 0;
  font-size: 24px;
  font-weight: 800;
  letter-spacing: -0.035em;
  line-height: 1.2;
  word-break: break-word;
}

/* 无封面时没有白色可依托，胶囊反成墨黑实底 */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: none;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--ink);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.45;
  white-space: nowrap;
}

.status-pill i {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #fff;
}

/* 三格：四格会压到 54px 一格，「至尊星耀」四字几乎没有余量 */
.stats {
  display: flex;
  align-items: stretch;
  gap: 8px;
}

.stats__cell {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}

/* 值带：三种内容（数字 / 段位名 / 破折号）光学中心统一到一条线 */
.stats__val {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 40px;
}

.stats__num {
  font-size: 24px;
  line-height: 1.1;
}

/* 没有到访记录时是「—」：800 字重下会被读成一条分隔线，必须弱化 */
.stats__num--empty {
  font-weight: 600;
  color: var(--ink3);
}

/* 15 → 20px：设计稿里段位名与 24px 数字是同一个量级 */
.stats__rank {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.2;
}

/* 通栏条与上面三格的标签行隔开一点，免得贴住「整体评分」 */
.rankline {
  display: flex;
  margin-top: 2px;
}

/* 建议段位：橙色只做小面积点缀（一个可点的下一步），不抢底部主 CTA */
.suggest-wrap {
  align-self: flex-start;
}

.suggest {
  padding: 7px 12px;
  border: 0;
  border-radius: 999px;
  background: var(--hotbg);
  color: var(--hot);
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.suggest:disabled {
  opacity: 0.5;
  cursor: default;
}

/* ---- 标签 ---- */
/* 最多 8 个：左对齐自然换行，两端对齐会让除第一行外每行只剩右边一截 */
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* 彩底彩字，与列表 TagRow / 编辑页同一套（16% 底 / 72% 字 / 28% 内描边） */
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 6px 11px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

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

/* ---- 通用区块 ---- */
.block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
}

.block__title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

/* ---- 位置 ---- */
/* 去掉「左标签右值」：省市区降到卡头右侧，地址做主体，地图入口整行 44px */
.loc__region {
  flex: none;
  text-align: right;
}

.loc__addr {
  margin: 2px 0 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: -0.01em;
  word-break: break-word;
}

.loc__map {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  margin-top: 4px;
  padding: 0 12px;
  border-radius: 10px;
  background: var(--bg);
  color: var(--ink);
  font-size: 13px;
  font-weight: 700;
  text-decoration: none;
}

.loc__map__go {
  margin-left: auto;
  color: var(--ink3);
}

/* ---- 照片 ---- */
.photogrid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

/* 1 张占满一行；2~4 张两列；5 张以上走默认三列 */
.photogrid--single {
  grid-template-columns: 1fr;
}

.photogrid--pair {
  grid-template-columns: repeat(2, 1fr);
}

.photogrid__item {
  display: block;
  aspect-ratio: 1 / 1;
  padding: 0;
  border: 0;
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg);
  cursor: pointer;
}

/* 单张与两列都用 4:3：图源本来就是 3:2，1:1 裁掉太多，两行 1:1 还会把首屏吃掉 */
.photogrid--single .photogrid__item,
.photogrid--pair .photogrid__item {
  aspect-ratio: 4 / 3;
}

.photogrid__item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* ---- 描述 ---- */
.desc {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--ink2);
  white-space: pre-wrap;
}

/* ---- 套餐 ---- */
.price-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-height: 42px;
}

.price-row + .price-row {
  border-top: 1px solid var(--line);
}

.price-row__name {
  flex: none;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

/* 名字与价格之间的点状引导线，撑开空白、呼应价目表的「条目感」 */
.price-row__leader {
  flex: 1;
  min-width: 12px;
  border-bottom: 1px dotted var(--line2);
  transform: translateY(-3px);
}

.price-row__na {
  flex: none;
  color: var(--ink3);
}

/* 价格用墨黑：橙色留给分数、选中与主 CTA，价目表不靠颜色强调 */
.price-row__num {
  flex: none;
  font-size: 16px;
  font-weight: 800;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

/* ---- 联系方式（卡内按平台分组） ---- */
/* 一组 = 平台小方块 + 平台名，组内每条一行：▪ 值 ……… › */
.contact-hint {
  margin: 0;
}

.cgroup__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0 2px;
}

.cgroup__badge {
  flex: none;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  color: #fff;
  /* 「微信」「与你」两个汉字要塞进 28px，字号再大就挤成一片 */
  font-size: 10px;
  font-weight: 700;
  letter-spacing: -0.03em;
}

.cgroup__badge--qq {
  background: var(--brand-qq);
}

/* TG 与 QQ 的蓝几乎同色，压深一档才分得出 */
.cgroup__badge--telegram {
  background: var(--brand-tg);
}

.cgroup__badge--wechat {
  background: var(--brand-wx);
}

.cgroup__badge--phone {
  background: var(--brand-phone);
}

.cgroup__badge--youyu {
  background: var(--ink);
}

.cgroup__label {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink2);
}

/* 整行可点 → 底部选项框（打开 App / 仅复制）；行尾箭头说明「点开还有一步」 */
.crow {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 44px;
  /* 左缩进 = 平台徽标 28 + 间距 8：条目前的小圆点跟平台名对齐成一条线 */
  padding: 0 0 0 36px;
  border: 0;
  background: transparent;
  font: inherit;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.crow__dot {
  flex: none;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ink3);
}

.crow__value {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -0.01em;
  /* 「与你」可能填的是整条 yuni:// 链接，允许折行而不是被截掉 */
  word-break: break-all;
}

.crow__chevron {
  flex: none;
  display: grid;
  place-items: center;
  width: 16px;
  color: var(--ink3);
}

/* ---- 到访记录 ---- */
/* 整行可点 → 进「改这次到访」，所以不加额外的小按钮，避免列表变工具栏 */
.visit-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 0;
  text-decoration: none;
  color: inherit;
}

.visit-row + .visit-row {
  border-top: 1px solid var(--line);
}

.visit-row__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.visit-row__rating {
  font-size: 13px;
}

.visit-row__note {
  margin: 0;
  font-size: 13px;
  color: var(--ink2);
  white-space: pre-wrap;
}

.visit-row__foot {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* ---- 关联打卡点 ---- */
.parent {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  text-decoration: none;
  color: inherit;
}

.parent__name {
  font-size: 14px;
  font-weight: 700;
}

.child {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  text-decoration: none;
  color: inherit;
}

.child + .child {
  border-top: 1px solid var(--line);
}

.child__avatar {
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  object-fit: cover;
}

.child__avatar--text {
  display: grid;
  place-items: center;
  background: var(--bg);
  color: var(--ink2);
  font-size: 12px;
  font-weight: 700;
}

.child__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13.5px;
  font-weight: 600;
}

/* ---- 底部固定操作栏 ---- */
.detail__actions {
  position: fixed;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%);
  width: 100%;
  max-width: 720px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  background: var(--card);
  /* 一条 1px 线不够：滚动时卡片边缘像贴在按钮上 */
  box-shadow: 0 -1px 0 var(--line), 0 -6px 20px -14px rgba(17, 24, 39, 0.18);
  display: flex;
  gap: 10px;
}

.detail__edit {
  flex: none;
  padding: 0 22px;
}

.detail__edit,
.detail__visit {
  display: flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
}

.detail__visit {
  flex: 1;
}
</style>
