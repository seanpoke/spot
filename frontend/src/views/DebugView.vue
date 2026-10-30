<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getHealth, type HealthReport } from '@/api/health'
import { HttpError } from '@/api/http'
import { logout, markAnonymous, me, type SessionInfo } from '@/api/auth'
import { listSpots, type SpotListItem } from '@/api/spots'
import RankBar from '@/components/RankBar.vue'
import { RANKS } from '@/config/ranks'
import { statusLabel } from '@/config/status'
import { getPositionWithFallback } from '@/utils/geolocation'

/**
 * 环境自检页（`/debug`，从地图页的「⋯」菜单进来）。
 *
 * 原先是临时首页，地图主页上线后退到这里。它验证四件事：
 * 前端能起、/api 代理通、后端能连上 MySQL 与 Redis、口令会话真的生效。
 * 设置页做出来后，其中「退出登录」会挪进设置页，这里只留诊断信息。
 */
const router = useRouter()

const report = ref<HealthReport | null>(null)
const session = ref<SessionInfo | null>(null)
const errorMessage = ref('')
const loading = ref(true)
const signingOut = ref(false)
const spots = ref<SpotListItem[]>([])

/* ---------------- 定位自检 ---------------- */
/** 当前页面地址：用来核对协议/主机是否与证书一致（http 或 IP 不匹配都会禁用定位） */
const pageUrl = window.location.href
const secureContext = window.isSecureContext
const geoSupported = 'geolocation' in navigator
type GeoPermission = 'unknown' | 'granted' | 'denied' | 'prompt' | 'unsupported'
const geoPermission = ref<GeoPermission>('unknown')
const testingGeo = ref(false)
const geoOk = ref(false)
const geoResult = ref('')

const permissionText = computed(() => {
  switch (geoPermission.value) {
    case 'granted':
      return '已允许'
    case 'denied':
      return '已拒绝'
    case 'prompt':
      return '未决定（会弹窗询问）'
    case 'unsupported':
      return '浏览器不提供查询'
    default:
      return '检测中…'
  }
})

/** 能查就查一下站点权限；Safari / 部分 WebView 不支持 geolocation 查询，返回 unsupported */
async function probePermission(): Promise<void> {
  if (!('permissions' in navigator) || !navigator.permissions?.query) {
    geoPermission.value = 'unsupported'
    return
  }
  try {
    const status = await navigator.permissions.query({ name: 'geolocation' })
    const sync = (): void => {
      geoPermission.value = status.state as GeoPermission
    }
    sync()
    status.onchange = sync
  } catch {
    geoPermission.value = 'unsupported'
  }
}

/** 真正要一次坐标：成功给经纬度，失败把 code 翻成人话，方便判断卡在哪一层 */
function testGeo(): void {
  if (!geoSupported) {
    geoOk.value = false
    geoResult.value = '这个浏览器没有 navigator.geolocation'
    return
  }
  testingGeo.value = true
  geoResult.value = ''
  getPositionWithFallback().then(
    (position) => {
      testingGeo.value = false
      geoOk.value = true
      geoResult.value = `成功：${position.coords.latitude.toFixed(5)}, ${position.coords.longitude.toFixed(5)}（精度约 ${Math.round(position.coords.accuracy)} 米）`
      void probePermission()
    },
    (error: GeolocationPositionError) => {
      testingGeo.value = false
      geoOk.value = false
      geoResult.value =
        error.code === 1
          ? 'PERMISSION_DENIED：系统或浏览器拒绝了定位权限'
          : error.code === 2
            ? 'POSITION_UNAVAILABLE：系统没返回位置（定位总开关没开，或浏览器 App 没有系统定位权限）'
            : 'TIMEOUT：超时（室内或信号弱）'
      void probePermission()
    },
  )
}

async function loadSpots(): Promise<void> {
  try {
    spots.value = await listSpots()
  } catch {
    // 列表失败不影响自检，静默留空
    spots.value = []
  }
}

async function check(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  report.value = null
  try {
    report.value = await getHealth()
  } catch (error) {
    if (error instanceof HttpError) {
      // 后端 503 时 body 里仍带着各依赖的状态，先尽量展示出来
      errorMessage.value = error.message
      if (error.data) {
        report.value = error.data as HealthReport
      }
    } else {
      errorMessage.value = error instanceof Error ? error.message : String(error)
    }
  } finally {
    loading.value = false
  }
}

async function loadSession(): Promise<void> {
  try {
    session.value = await me()
  } catch {
    // 守卫已经处理跳转，这里静默即可
    session.value = null
  }
}

async function signOut(): Promise<void> {
  if (signingOut.value) {
    return
  }
  signingOut.value = true
  try {
    await logout()
  } finally {
    markAnonymous()
    signingOut.value = false
    await router.replace('/gate')
  }
}

function stateText(value: string | undefined): string {
  if (loading.value) return '检测中'
  if (!value) return '未知'
  return value === 'up' ? '正常' : '不可用'
}

function sessionDays(): string {
  if (!session.value) return '—'
  return `${Math.max(1, Math.round(session.value.expiresInSeconds / 86400))} 天`
}

onMounted(() => {
  check()
  loadSession()
  loadSpots()
  void probePermission()
})
</script>

<template>
  <div class="page">
    <div class="head">
      <RouterLink class="meta head__back" to="/">← 返回地图</RouterLink>
      <h1 class="page-title">环境自检</h1>
      <p class="meta">确认口令门、后端、MySQL、Redis 四条链路都通</p>
    </div>

    <section class="card spots">
      <div class="spots__head">
        <h2 class="section-title">打卡点</h2>
        <span class="meta">{{ spots.length }} 个 · 临时列表，地图主页完成后由地图取代</span>
      </div>

      <RouterLink
        v-for="spot in spots"
        :key="spot.id"
        class="spots__row"
        :to="`/spot/${spot.id}/edit`"
      >
        <span class="spots__name">{{ spot.name }}</span>
        <RankBar :level="spot.rankLevel" />
        <span class="meta spots__status">{{ statusLabel(spot.status) }}</span>
      </RouterLink>

      <p v-if="spots.length === 0" class="meta">还没有打卡点，先建一个试试</p>

      <RouterLink class="btn spots__new" to="/spot/new">新建打卡点</RouterLink>
    </section>

    <section class="card check">
      <h2 class="section-title">定位自检</h2>

      <div class="check__row">
        <span class="section-title">页面地址</span>
        <span class="check__value check__value--wrap">{{ pageUrl }}</span>
      </div>

      <div class="check__row">
        <span class="section-title">安全上下文</span>
        <span class="check__state" :class="{ 'check__state--bad': !secureContext }">
          {{ secureContext ? '是（允许定位）' : '否（浏览器禁用定位）' }}
        </span>
      </div>

      <div class="check__row">
        <span class="section-title">定位 API</span>
        <span class="check__state" :class="{ 'check__state--bad': !geoSupported }">
          {{ geoSupported ? '支持' : '不支持' }}
        </span>
      </div>

      <div class="check__row">
        <span class="section-title">浏览器权限</span>
        <span class="check__state" :class="{ 'check__state--bad': geoPermission === 'denied' }">
          {{ permissionText }}
        </span>
      </div>

      <p v-if="geoResult" class="check__error" :class="{ 'check__error--ok': geoOk }">
        {{ geoResult }}
      </p>

      <button class="btn" type="button" :disabled="testingGeo" @click="testGeo">
        {{ testingGeo ? '定位中…' : '测试定位' }}
      </button>

      <p class="check__hint">
        站点权限「已允许」不等于系统权限已允许：安卓还要在「设置 → 应用 → Edge → 权限 → 位置」
        放行，并打开系统定位总开关。若地址栏出现证书警告，Chromium 会禁用部分能力 ——
        建议把 mkcert 根证书装到手机并信任。
      </p>
    </section>

    <section class="card check">
      <div class="check__row">
        <span class="section-title">后端接口</span>
        <span class="check__state" :class="{ 'check__state--bad': !!errorMessage }">
          {{ loading ? '检测中' : errorMessage ? '不可用' : '正常' }}
        </span>
      </div>

      <div class="check__row">
        <span class="section-title">MySQL</span>
        <span class="check__state" :class="{ 'check__state--bad': report?.mysql === 'down' }">
          {{ stateText(report?.mysql) }}
        </span>
      </div>

      <div class="check__row">
        <span class="section-title">Redis</span>
        <span class="check__state" :class="{ 'check__state--bad': report?.redis === 'down' }">
          {{ stateText(report?.redis) }}
        </span>
      </div>

      <div class="check__row">
        <span class="section-title">当前库</span>
        <span class="check__value">{{ report?.database ?? '—' }}</span>
      </div>

      <p v-if="errorMessage" class="check__error">{{ errorMessage }}</p>

      <button class="btn" type="button" :disabled="loading" @click="check">
        {{ loading ? '检测中…' : '重新检测' }}
      </button>
    </section>

    <section class="card session">
      <div>
        <h2 class="section-title">口令会话</h2>
        <p class="meta">已通过口令门 · 有效期 {{ sessionDays() }}（每次访问自动续期）</p>
      </div>
      <button class="btn btn--ghost" type="button" :disabled="signingOut" @click="signOut">
        {{ signingOut ? '退出中…' : '退出登录' }}
      </button>
    </section>

    <section class="card ranks">
      <div class="ranks__head">
        <h2 class="section-title">段位刻度条</h2>
        <span class="meta">尺寸与配色来自 config/ranks.ts</span>
      </div>
      <div v-for="rank in RANKS" :key="rank.level" class="ranks__row">
        <span class="ranks__name">{{ rank.name }}</span>
        <RankBar :level="rank.level" />
        <span class="meta ranks__meaning">{{ rank.meaning }}</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.head__back {
  display: inline-block;
  margin-bottom: 6px;
  color: var(--ink2);
  text-decoration: none;
}

.spots {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
}

.spots__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.spots__row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-top: 1px solid var(--line);
  color: var(--ink);
  text-decoration: none;
}

.spots__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spots__status {
  flex: none;
}

.spots__new {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 4px;
  text-decoration: none;
}

.check {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}

.check__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

/* 状态值用字重区分，不靠加颜色；只有失败才允许用强调色 */
.check__state {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--ink);
}

.check__state--bad {
  color: var(--hot);
}

.check__value {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--ink2);
}

.check__error {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink3);
  word-break: break-all;
}

.check__error--ok {
  color: var(--ink2);
}

.check__value--wrap {
  max-width: 62%;
  text-align: right;
  word-break: break-all;
}

.check__hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink3);
}

.session {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 16px;
}

.session p {
  margin: 4px 0 0;
}

.ranks {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
}

.ranks__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 2px;
}

.ranks__row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ranks__name {
  min-width: 76px;
  font-size: 13.5px;
  font-weight: 700;
}

.ranks__meaning {
  color: var(--ink3);
}
</style>
