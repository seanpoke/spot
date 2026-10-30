<script setup lang="ts">
import { computed } from 'vue'
import type { TimelineItem } from '@/api/visits'

/**
 * 狂人日志的内容体（原 `/timeline` 整页，现在并进统计页的一张卡）。
 *
 * 三个取舍沿用原页面：
 *   - **不分页**。个人应用的总量是百级，一次拿全反而简单：按月分组不会出现
 *     「同一个月被翻页切成两段」。后端 `/visits/timeline` 已按此设计。
 *   - **不显示标签**。这一行讲的是「这一次到访」，不是「这个点」。
 *   - **行内图片用打卡点头像**。到访照片还没在录入里开放。
 *
 * 与原来的差异：行不再是「一张张独立卡片」（放在白卡里会白压白），
 * 改成卡内扁平行 + 发丝分隔线。
 */
const props = defineProps<{
  items: TimelineItem[]
  loading?: boolean
  errorText?: string
}>()

const emit = defineEmits<{ retry: [] }>()

interface MonthGroup {
  /** yyyy-MM，用作 key */
  key: string
  label: string
  items: TimelineItem[]
}

/** 最新一年不写年份：同一年里每段都挂「2026 年」纯属噪音 */
const headYear = computed(() => {
  const newest = props.items[0]?.visitedAt
  return newest ? newest.slice(0, 4) : String(new Date().getFullYear())
})

/** 后端已按 visited_at desc 排好，这里只按 yyyy-MM 切段，不重新排序 */
const groups = computed<MonthGroup[]>(() => {
  const buckets = new Map<string, TimelineItem[]>()
  for (const item of props.items) {
    const key = item.visitedAt.slice(0, 7)
    const bucket = buckets.get(key)
    if (bucket) {
      bucket.push(item)
    } else {
      buckets.set(key, [item])
    }
  }
  return [...buckets.entries()].map(([key, list]) => ({
    key,
    label: monthLabel(key),
    items: list,
  }))
})

function monthLabel(key: string): string {
  const [year, month] = key.split('-')
  const monthNumber = Number(month)
  return year === headYear.value ? `${monthNumber} 月` : `${year} 年 ${monthNumber} 月`
}

function dateText(visitedAt: string): string {
  const [, month, day] = visitedAt.split('-')
  return `${Number(month)}.${day}`
}
</script>

<template>
  <div v-if="loading" class="tlog__state">
    <p class="meta">加载中…</p>
  </div>

  <div v-else-if="errorText" class="tlog__state">
    <p class="meta">{{ errorText }}</p>
    <button class="tlog__retry" type="button" @click="emit('retry')">重新加载</button>
  </div>

  <div v-else-if="items.length === 0" class="tlog__state">
    <p class="meta">还没有到访记录，去地图上写第一次。</p>
    <RouterLink class="tlog__retry" to="/">去地图</RouterLink>
  </div>

  <div v-else class="tlog">
    <section v-for="group in groups" :key="group.key" class="tlog__month">
      <div class="tlog__mhead">
        <b>{{ group.label }}</b>
        <span class="meta">{{ group.items.length }} 次</span>
      </div>

      <RouterLink
        v-for="item in group.items"
        :key="item.id"
        class="tlog__row"
        :to="`/spot/${item.spotId}`"
      >
        <img
          v-if="item.spotAvatar"
          class="tlog__ph"
          :src="item.spotAvatar"
          :alt="item.spotName ?? ''"
          loading="lazy"
          decoding="async"
        />
        <span v-else class="tlog__ph tlog__ph--empty">{{ (item.spotName ?? '?').slice(0, 1) }}</span>

        <div class="tlog__main">
          <div class="tlog__top">
            <b class="tlog__name">{{ item.spotName ?? '已删除的打卡点' }}</b>
            <span class="stars" :aria-label="`${item.rating} 星`">
              <span
                v-for="n in 5"
                :key="n"
                class="stars__one"
                :class="{ 'stars__one--on': n <= item.rating }"
              >
                ★
              </span>
            </span>
            <span class="tlog__date num">{{ dateText(item.visitedAt) }}</span>
          </div>

          <p v-if="item.note" class="tlog__note">{{ item.note }}</p>

          <div v-if="item.cost !== undefined || item.companions" class="tlog__foot">
            <span v-if="item.cost !== undefined" class="meta">¥{{ item.cost }}</span>
            <span v-if="item.companions" class="meta">与 {{ item.companions }}</span>
          </div>
        </div>
      </RouterLink>
    </section>
  </div>
</template>

<style scoped>
.tlog__state {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  padding: 26px 8px;
  text-align: center;
}

.tlog__state .meta {
  max-width: 260px;
  line-height: 1.8;
}

.tlog__retry {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 38px;
  padding: 0 18px;
  border: 0;
  border-radius: 999px;
  background: var(--bg);
  color: var(--ink2);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
}

/* ---- 月份 ---- */
.tlog {
  display: flex;
  flex-direction: column;
}

.tlog__month + .tlog__month {
  margin-top: 16px;
}

.tlog__mhead {
  display: flex;
  align-items: baseline;
  gap: 7px;
  padding: 0 0 2px;
}

.tlog__mhead b {
  font-size: 13.5px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

/* ---- 一行一次到访（卡内扁平行） ---- */
.tlog__row {
  display: flex;
  gap: 11px;
  align-items: flex-start;
  padding: 10px 0;
  color: inherit;
  text-decoration: none;
}

.tlog__row + .tlog__row {
  border-top: 1px solid var(--line);
}

.tlog__ph {
  flex: 0 0 auto;
  width: 58px;
  height: 58px;
  border-radius: 9px;
  object-fit: cover;
}

.tlog__ph--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
  color: var(--ink3);
  font-size: 20px;
  font-weight: 800;
}

.tlog__main {
  flex: 1;
  min-width: 0;
  padding-top: 1px;
}

.tlog__top {
  display: flex;
  gap: 7px;
  align-items: baseline;
}

.tlog__name {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tlog__date {
  flex: none;
  margin-left: auto;
  color: var(--ink3);
  font-size: 11.5px;
  font-weight: 700;
}

.stars {
  display: flex;
  flex: none;
  gap: 1px;
  line-height: 1;
}

.stars__one {
  color: var(--bar-empty);
  font-size: 12px;
}

.stars__one--on {
  color: var(--ink);
}

.tlog__note {
  display: -webkit-box;
  margin: 5px 0 0;
  overflow: hidden;
  color: var(--ink2);
  font-size: 12px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.tlog__foot {
  display: flex;
  gap: 10px;
  margin-top: 5px;
}
</style>
