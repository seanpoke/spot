<script setup lang="ts">
import { computed } from 'vue'
import RankBar from '@/components/RankBar.vue'
import TagRow from '@/components/TagRow.vue'
import { statusLabel } from '@/config/status'
import { formatDistance } from '@/utils/geo'
import type { SpotListItem } from '@/api/spots'

/**
 * 打卡点行卡片：地图页抽屉与列表页共用同一份（两边内容必须一致）。
 * 只负责展示，点击交给 RouterLink 进详情。
 */
const props = defineProps<{
  spot: SpotListItem
  /** 距离（km）。没有定位时为 null，副标题就只留状态 */
  distance?: number | null
}>()

const meta = computed(() => {
  const status = statusLabel(props.spot.status)
  return props.distance == null ? status : `${status} · ${formatDistance(props.distance)}`
})

/** 没评过分就显示破折号，别显示 0.0 —— 0 分和「没评」是两件事 */
const ratingText = computed(() => (props.spot.avgRating ? props.spot.avgRating.toFixed(1) : '—'))
</script>

<template>
  <RouterLink class="prow" :to="`/spot/${spot.id}`">
    <img
      v-if="spot.avatar"
      class="prow__ph"
      :src="spot.avatar"
      alt=""
      loading="lazy"
      decoding="async"
    />
    <span v-else class="prow__ph prow__ph--empty">{{ spot.name.slice(0, 1) }}</span>

    <div class="prow__m">
      <b>{{ spot.name }}</b>
      <span>{{ meta }}</span>
      <TagRow v-if="spot.tags?.length" class="prow__tags" :tags="spot.tags" />
    </div>

    <div class="prow__rt">
      <span v-if="spot.status === 'blacklist'" class="prow__x">避雷</span>
      <template v-else>
        <span class="prow__sc" :class="{ 'is-none': !spot.avgRating }">
          {{ ratingText }}<em v-if="spot.avgRating">分</em>
        </span>
        <RankBar :level="spot.rankLevel" />
      </template>
    </div>
  </RouterLink>
</template>

<style scoped>
.prow {
  display: flex;
  gap: 11px;
  align-items: flex-start;
  margin-bottom: 9px;
  padding: 10px;
  border-radius: var(--r);
  background: var(--card);
  box-shadow: var(--sh);
  color: inherit;
  text-decoration: none;
}

.prow__ph {
  flex: 0 0 auto;
  width: 72px;
  height: 72px;
  border-radius: 9px;
  object-fit: cover;
}

.prow__ph--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
  color: var(--ink3);
  font-size: 22px;
  font-weight: 800;
}

.prow__m {
  flex: 1;
  min-width: 0;
  padding-top: 1px;
}

.prow__m b {
  display: block;
  overflow: hidden;
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prow__m span {
  display: block;
  margin-top: 3px;
  color: var(--ink3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.prow__tags {
  margin-top: 6px;
}

.prow__rt {
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  gap: 7px;
  align-items: flex-end;
  padding-top: 1px;
}

.prow__sc {
  color: var(--hot);
  font-size: 15px;
  font-weight: 800;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}

.prow__sc em {
  margin-left: 1px;
  font-size: 10px;
  font-style: normal;
  font-weight: 600;
}

.prow__sc.is-none {
  color: var(--ink3);
}

.prow__x {
  padding: 3px 7px;
  border-radius: 5px;
  background: var(--bg);
  color: #8a9099;
  font-size: 10px;
  font-weight: 700;
}
</style>
