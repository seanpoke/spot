<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { logout, markAnonymous } from '@/api/auth'
import { getStats, type StatsResponse } from '@/api/stats'
import { EXPORT_LINKS } from '@/api/export'
import TabBar from '@/components/TabBar.vue'

/**
 * 设置页（PRD 页面清单 `/settings`，视觉基线是原型「我的」屏）。
 *
 * 这个页面只做<b>真的能做成的事</b>，不做假开关：
 *   - 备份：三个真下载（JSON 全量 / 打卡点 CSV / 到访 CSV），后端直接返回文件流
 *   - 访问口令：<b>只读</b>。口令来自服务器 `ACCESS_PASSWORD`，页面里改不了；
 *     点开给的是「去哪儿改、改完会怎样」，不是装个输入框骗自己
 *   - 图片与流量：只陈述当前事实（JPEG 1280、视口外不加载），
 *     原型里的「图片质量 / 只在 Wi-Fi 加载大图」开关属于没有实现的功能，先不摆
 *
 * 「上次导出」只记在本机 localStorage：服务端没存备份记录，
 * 写成「上次备份」会与 PRD 7.11 的服务器侧 crontab 混淆。
 */

const router = useRouter()

const stats = ref<StatsResponse | null>(null)
const lastExportAt = ref<string | null>(null)

const LAST_EXPORT_KEY = 'spot:lastExport'

const lastExportText = computed(() => (lastExportAt.value ? formatStamp(lastExportAt.value) : ''))

onMounted(async () => {
  lastExportAt.value = readLastExport()
  try {
    stats.value = await getStats()
  } catch {
    // 概览数字只是锦上添花，取不到就不显示，不打断下载功能
    stats.value = null
  }
})

function readLastExport(): string | null {
  try {
    return window.localStorage.getItem(LAST_EXPORT_KEY)
  } catch {
    return null
  }
}

function formatStamp(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const time = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  const today = new Date()
  return date.toDateString() === today.toDateString()
    ? `今天 ${time}`
    : `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${time}`
}

/** 点了下载链接就把时间记下来：浏览器不会告诉我们「文件真的存好了」 */
function markExported(): void {
  const now = new Date().toISOString()
  lastExportAt.value = now
  try {
    window.localStorage.setItem(LAST_EXPORT_KEY, now)
  } catch {
    // 隐私模式下写不进去，忽略即可
  }
}

async function explainPassword(): Promise<void> {
  await ElMessageBox.alert(
    '口令来自服务器上的 ACCESS_PASSWORD（deploy/.env），页面不提供在线修改：' +
      '改了只存在内存里，重启就丢。正确的改法是改这个值，再 docker compose up -d 重启后端。' +
      '会话里带口令版本号，所以改完所有设备上的登录态会立即作废，重新输一次即可。',
    '访问口令',
    { confirmButtonText: '知道了' },
  )
}

async function signOut(): Promise<void> {
  try {
    await logout()
  } catch {
    // 网络失败也照样回口令门：本地记忆清掉，服务端那条到期自然消失
  }
  markAnonymous()
  await router.replace('/gate')
}
</script>

<template>
  <div class="profile">
    <header class="profile__head">
      <span class="profile__chip">本地</span>
      <h1 class="page-title">我的</h1>
      <p class="meta">单口令、单用户，数据只存在这台服务器上。</p>
    </header>

    <!-- 统计取不到时也要留着这张卡：整块消失会让页面突然少一截，看着像排版塌了 -->
    <section class="card tally">
      <div class="tally__cell">
        <b class="num">{{ stats?.spotCount ?? '—' }}</b>
        <span class="meta">个点</span>
      </div>
      <div class="tally__cell tally__cell--mid">
        <b class="num">{{ stats?.visitCount ?? '—' }}</b>
        <span class="meta">次到访</span>
      </div>
      <div class="tally__cell">
        <b class="num">{{ stats?.cityCount ?? '—' }}</b>
        <span class="meta">座城市</span>
      </div>
    </section>

    <section class="card block">
      <div class="block__head">
        <h2 class="section-title">安全</h2>
        <span class="meta">口令在服务器上改</span>
      </div>
      <button class="row" type="button" @click="explainPassword">
        <span class="row__label">访问口令</span>
        <span class="row__value">已设置</span>
        <span class="row__chev" aria-hidden="true" />
      </button>
    </section>

    <section class="card block">
      <div class="block__head">
        <h2 class="section-title">备份</h2>
        <span class="meta">自己的数据，随时能拿走</span>
      </div>

      <a class="row" :href="EXPORT_LINKS.json" download @click="markExported">
        <span class="row__label">导出 JSON<em class="row__tag">全量</em></span>
        <span class="row__value">{{ stats ? `${stats.spotCount} 个点` : '全部表' }}</span>
        <span class="row__chev" aria-hidden="true" />
      </a>

      <a class="row" :href="EXPORT_LINKS.spotsCsv" download @click="markExported">
        <span class="row__label">导出打卡点 CSV<em class="row__tag row__tag--on">推荐</em></span>
        <span class="row__value">{{ stats ? `${stats.spotCount} 行` : 'CSV' }}</span>
        <span class="row__chev" aria-hidden="true" />
      </a>

      <a class="row" :href="EXPORT_LINKS.visitsCsv" download @click="markExported">
        <span class="row__label">导出到访 CSV</span>
        <span class="row__value">{{ stats ? `${stats.visitCount} 行` : 'CSV' }}</span>
        <span class="row__chev" aria-hidden="true" />
      </a>

      <div v-if="lastExportText" class="row row--plain">
        <span class="row__label">上次导出</span>
        <span class="row__value">{{ lastExportText }}</span>
      </div>
    </section>

    <section class="card block">
      <div class="block__head">
        <h2 class="section-title">图片与流量</h2>
        <span class="meta">3 Mbps 带宽下的取舍</span>
      </div>
      <div class="row row--plain">
        <span class="row__label">图片格式</span>
        <span class="row__value">JPEG · 最长边 1280</span>
      </div>
      <div class="row row--plain">
        <span class="row__label">图片加载</span>
        <span class="row__value">视口外不请求</span>
      </div>
    </section>

    <button class="btn btn--ghost profile__out" type="button" @click="signOut">退出登录</button>

    <TabBar active="me" />
  </div>
</template>

<style scoped>
/* 根节点**不要**叫 `.me`：styles/map.css 里有个全局 `.me`（46×46 的定位点），
   类名一撞，整页只会被压成 46px 宽、文字一个字一行。页面级容器一律用语义名。 */
.profile {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 720px;
  min-height: 100dvh;
  margin: 0 auto;
  /* 底部留白 = TabBar(66) + 安全区 + 18：写死 92px 在有 Home 条的机型上会压住退出按钮 */
  padding: 18px 16px calc(66px + env(safe-area-inset-bottom, 8px) + 18px);
}

.profile__head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.profile__chip {
  align-self: flex-start;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--card);
  box-shadow: 0 1px 3px rgba(17, 24, 39, 0.1);
  color: var(--ink2);
  font-size: 11px;
  font-weight: 700;
}

/* ---- 三个数字 ---- */
.tally {
  display: flex;
  padding: 16px 0;
}

.tally__cell {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  align-items: center;
}

.tally__cell--mid {
  border-right: 1px solid var(--line);
  border-left: 1px solid var(--line);
}

.tally__cell b {
  font-size: 24px;
  line-height: 1.1;
}

/* ---- 卡片 ---- */
.block {
  display: flex;
  flex-direction: column;
  /* 四边等距：原来下边只有 6px，最后一行看着被卡着 */
  padding: 14px;
}

.block__head {
  display: flex;
  gap: 10px;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: 8px;
}

.block > .section-title {
  padding-bottom: 8px;
}

/* ---- 行 ---- */
.row {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 13px 0;
  border: 0;
  border-top: 1px solid var(--line);
  background: transparent;
  color: inherit;
  font-family: inherit;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

/* 首行不画分割线：否则标题下面会多出一条毫无来由的细线 */
.block > .row:first-child,
.block > .section-title + .row,
.block > .block__head + .row {
  border-top: 0;
}

.row--plain {
  cursor: default;
}

.row__label {
  display: flex;
  flex: 1;
  gap: 6px;
  align-items: center;
  min-width: 0;
  font-size: 13.5px;
  font-weight: 600;
}

.row__tag {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg);
  color: var(--ink3);
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
}

/* 「推荐」用反白小签而不是橙色：橙色只留给分数/当前选中/主 CTA（AGENTS.md 第 20 条） */
.row__tag--on {
  background: var(--ink);
  color: #fff;
}

.row__value {
  flex: none;
  color: var(--ink3);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* 用两条边画箭头，不用「›」字符：字符的行高在不同字体下差异很大，
   右列会跟着上下偏移一两像素，整页看着就是「没对齐」 */
.row__chev {
  flex: none;
  width: 7px;
  height: 7px;
  margin-left: 2px;
  border-top: 1.6px solid #c8ccd2;
  border-right: 1.6px solid #c8ccd2;
  transform: rotate(45deg);
}

/* ---- 退出 ---- */
.profile__out {
  margin-top: 2px;
  height: 46px;
  color: var(--ink2);
}
</style>
