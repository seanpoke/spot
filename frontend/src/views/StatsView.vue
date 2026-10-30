<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { HttpError } from '@/api/http'
import { getStats, type StatsResponse } from '@/api/stats'
import { getTimeline, type TimelineItem, type TimelineSummary } from '@/api/visits'
import { RANKS } from '@/config/ranks'
import TabBar from '@/components/TabBar.vue'
import TimelineList from '@/components/TimelineList.vue'

/**
 * 统计页（PRD 3.x `/stats`）。
 *
 * 五块内容：前四块来自 `GET /api/stats`，最后一块来自 `GET /api/visits/timeline`：
 *   ① 三个数字（打卡点 / 到访 / 城市）
 *   ② 段位分布：7 行，颜色与名称只从 `config/ranks.ts` 取（AGENTS.md 第 7 条）
 *   ③ 每月到访：12 根柱，当月橙色，其余灰 —— 这里唯一的橙色用法（「当前」）
 *   ④ 去得最多前 3：点进详情
 *   ⑤ 狂人日志：原 `/timeline` 整页并进来的一张卡（内容体见 components/TimelineList.vue），
 *      底部 tab 也因此从「地图/日志/统计/我的」变成「地图/列表/统计/我的」
 *
 * 两个刻意的做法：
 *   - 段位从高到低排（王者在上），和「段位阶梯」的阅读方向一致；原型即如此。
 *   - 条形长度按「本图内最大值」归一，不按总数百分比：7 段里最多的那段占满，
 *     差异才看得出来；按总数算会全部挤在 20% 以内。
 */

const loading = ref(true)
const errorText = ref('')
const stats = ref<StatsResponse | null>(null)

// 日志与统计两个接口各自独立加载：日志挂了不该把整页拖成错误态
const timelineLoading = ref(true)
const timelineError = ref('')
const timelineSummary = ref<TimelineSummary | null>(null)
const timelineItems = ref<TimelineItem[]>([])

const timelineMeta = computed(() => {
  const summary = timelineSummary.value
  if (!summary) {
    return ''
  }
  const avg = summary.avgRating == null ? '—' : summary.avgRating.toFixed(1)
  return `共 ${summary.total} 次 · 本月 ${summary.monthCount} 次 · 均分 ${avg}`
})

const currentYear = new Date().getFullYear()
const currentMonth = new Date().getMonth() + 1

interface RankRow {
  level: number
  name: string
  color: string
  count: number
  /** 条宽百分比（0~100），按本图最大值归一 */
  pct: number
}

/** 王者在上、青铜在下 */
const rankRows = computed<RankRow[]>(() => {
  const buckets = new Map((stats.value?.ranks ?? []).map((item) => [item.level, item.count]))
  const max = Math.max(...[...buckets.values()], 1)
  return [...RANKS]
    .reverse()
    .map((rank) => {
      const count = buckets.get(rank.level) ?? 0
      return {
        level: rank.level,
        name: rank.name,
        color: rank.color,
        count,
        pct: Math.round((count / max) * 100),
      }
    })
})

const maxMonthCount = computed(() => Math.max(...(stats.value?.monthly ?? []).map((m) => m.count), 1))

function monthBarHeight(count: number): string {
  if (count === 0) {
    return '0%'
  }
  // 至少留 8%：只有一次到访的月份也要看得见一根柱
  return `${Math.max(8, Math.round((count / maxMonthCount.value) * 100))}%`
}

function isCurrentMonth(month: number): boolean {
  return stats.value?.year === currentYear && month === currentMonth
}

function lastVisitText(value?: string): string {
  if (!value) {
    return '—'
  }
  const [, month, day] = value.split('-')
  return `${Number(month)}.${day}`
}

async function loadTimeline(): Promise<void> {
  timelineLoading.value = true
  timelineError.value = ''
  try {
    const data = await getTimeline()
    timelineSummary.value = data.summary
    timelineItems.value = data.items
  } catch (error) {
    timelineSummary.value = null
    timelineItems.value = []
    timelineError.value = error instanceof HttpError ? error.message : '加载失败，请重试'
  } finally {
    timelineLoading.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    stats.value = await getStats()
  } catch (error) {
    stats.value = null
    errorText.value = error instanceof HttpError ? error.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 两个接口并行，互不阻塞
  void load()
  void loadTimeline()
})
</script>

<template>
  <div class="stats">
    <header class="stats__head">
      <h1 class="page-title">统计<span class="stats__dot">·</span>{{ stats?.spotCount ?? 0 }}</h1>
      <p class="meta">你自己定的段位，比任何平台的评分都准。</p>
    </header>

    <div v-if="loading" class="stats__state">
      <p class="meta">加载中…</p>
    </div>

    <div v-else-if="errorText" class="stats__state">
      <p class="meta">{{ errorText }}</p>
      <button class="btn" type="button" @click="load">重新加载</button>
    </div>

    <div v-else-if="!stats || stats.spotCount === 0" class="stats__state">
      <p class="meta">还没有打卡点，先去地图上记一个。</p>
      <RouterLink class="btn" to="/">去地图</RouterLink>
    </div>

    <template v-else>
      <section class="card tally">
        <div class="tally__cell">
          <b class="num">{{ stats.spotCount }}</b>
          <span class="meta">打卡点</span>
        </div>
        <div class="tally__cell tally__cell--mid">
          <b class="num">{{ stats.visitCount }}</b>
          <span class="meta">到访</span>
        </div>
        <div class="tally__cell">
          <b class="num">{{ stats.cityCount }}</b>
          <span class="meta">城市</span>
        </div>
      </section>

      <section class="card block">
        <div class="block__head">
          <h2 class="section-title">段位分布</h2>
          <span class="meta">按你的定级</span>
        </div>
        <div v-for="row in rankRows" :key="row.level" class="rank">
          <span class="rank__dot" :style="{ background: row.color }" />
          <span class="rank__name">{{ row.name }}</span>
          <span class="rank__track">
            <span class="rank__fill" :style="{ width: `${row.pct}%`, background: row.color }" />
          </span>
          <b class="rank__num num">{{ row.count }}</b>
        </div>
      </section>

      <section class="card block">
        <div class="block__head">
          <h2 class="section-title">每月到访</h2>
          <span class="meta">{{ stats.year }}</span>
        </div>
        <div class="months">
          <div v-for="item in stats.monthly" :key="item.month" class="month">
            <div class="month__slot">
              <span
                class="month__bar"
                :class="{ 'month__bar--now': isCurrentMonth(item.month) }"
                :style="{ height: monthBarHeight(item.count) }"
              />
            </div>
            <span class="month__label">{{ item.month }}</span>
          </div>
        </div>
      </section>

      <section v-if="stats.topSpots.length" class="card block">
        <div class="block__head">
          <h2 class="section-title">去得最多</h2>
          <span class="meta">前 {{ stats.topSpots.length }}</span>
        </div>
        <RouterLink
          v-for="(item, index) in stats.topSpots"
          :key="item.id"
          class="top"
          :to="`/spot/${item.id}`"
        >
          <span class="top__rank num">{{ index + 1 }}</span>
          <img
            v-if="item.avatar"
            class="top__ph"
            :src="item.avatar"
            :alt="item.name"
            loading="lazy"
            decoding="async"
          />
          <span v-else class="top__ph top__ph--empty">{{ item.name.slice(0, 1) }}</span>
          <div class="top__main">
            <b>{{ item.name }}</b>
            <span class="meta">{{ item.visitCount }} 次 · 上次 {{ lastVisitText(item.lastVisitAt) }}</span>
          </div>
        </RouterLink>
      </section>

      <!-- 原「日志」整页：并成统计页的最后一张卡 -->
      <section class="card block">
        <div class="block__head">
          <h2 class="section-title">狂人日志</h2>
          <span class="meta">{{ timelineMeta }}</span>
        </div>
        <TimelineList
          :items="timelineItems"
          :loading="timelineLoading"
          :error-text="timelineError"
          @retry="loadTimeline"
        />
      </section>
    </template>

    <TabBar active="stats" />
  </div>
</template>

<style scoped>
.stats {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 720px;
  min-height: 100dvh;
  margin: 0 auto;
  padding: 18px 16px 92px;
}

.stats__head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stats__dot {
  margin: 0 2px;
  color: var(--ink3);
  font-weight: 600;
}

.stats__state {
  display: flex;
  flex-direction: column;
  gap: 14px;
  align-items: center;
  padding: 44px 16px;
  text-align: center;
}

.stats__state .meta {
  max-width: 260px;
  line-height: 1.8;
}

.stats__state .btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 44px;
  padding: 0 22px;
  text-decoration: none;
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

/* ---- 通用卡内布局 ---- */
.block {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}

.block__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

/* ---- 段位分布 ---- */
.rank {
  display: grid;
  grid-template-columns: 8px 56px 1fr 30px;
  gap: 8px;
  align-items: center;
}

.rank__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.rank__name {
  overflow: hidden;
  color: var(--ink2);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank__track {
  height: 8px;
  overflow: hidden;
  border-radius: 4px;
  background: var(--line);
}

.rank__fill {
  display: block;
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s ease;
}

.rank__num {
  color: var(--ink2);
  font-size: 12px;
  text-align: right;
}

/* ---- 每月到访 ---- */
.months {
  display: flex;
  gap: 4px;
  align-items: flex-end;
  height: 96px;
  padding-top: 4px;
}

.month {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  height: 100%;
}

.month__slot {
  display: flex;
  flex: 1;
  align-items: flex-end;
  width: 100%;
}

.month__bar {
  display: block;
  width: 100%;
  border-radius: 3px 3px 2px 2px;
  background: #dfe4ea;
  transition: height 0.3s ease;
}

/* 当月唯一的橙色：这是「当前」而不是分数，属于选中态 */
.month__bar--now {
  background: var(--hot);
}

.month__label {
  color: var(--ink3);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

/* ---- 去得最多 ---- */
.top {
  display: flex;
  gap: 10px;
  align-items: center;
  color: inherit;
  text-decoration: none;
}

.top__rank {
  flex: none;
  width: 12px;
  color: var(--ink3);
  font-size: 12px;
  text-align: center;
}

.top__ph {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  object-fit: cover;
}

.top__ph--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
  color: var(--ink3);
  font-size: 16px;
  font-weight: 800;
}

.top__main {
  flex: 1;
  min-width: 0;
}

.top__main b {
  display: block;
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 700;
  letter-spacing: -0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
