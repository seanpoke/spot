<script setup lang="ts">
import { computed } from 'vue'
import {
  RANK_BAR,
  RANK_BAR_FLUID,
  rankFillFluid,
  rankFillWidth,
  rankOf,
  rankSlitFluid,
  type RankBarSize,
} from '@/config/ranks'

/**
 * 段位刻度条（7 段）。
 *
 * 所有尺寸与颜色都从 config/ranks.ts 取，组件里不写死任何值 ——
 * 手写 width 的结果是 7 处宽度对不上、填充直接溢出没画出来（AGENTS.md 第 22 条）。
 *
 * 三套尺寸不能混用：
 *   mini  30.4×8   地图标记、卡片列表右列
 *   large 40.2×9   编辑页阶梯选择器下方
 *   fluid 100%×7   详情页概览卡的通栏条（7 段按卡片宽度等分）
 */
const props = withDefaults(
  defineProps<{
    level: number | null | undefined
    size?: RankBarSize
  }>(),
  { size: 'mini' },
)

const rank = computed(() => rankOf(props.level))

const barStyle = computed(() => {
  if (props.size === 'fluid') {
    return { width: '100%', height: `${RANK_BAR_FLUID.height}px` }
  }
  const spec = RANK_BAR[props.size]
  return { width: `${spec.width}px`, height: `${spec.height}px` }
})

const fillStyle = computed(() => ({
  width:
    props.size === 'fluid'
      ? rankFillFluid(props.level)
      : `${rankFillWidth(props.level, props.size)}px`,
  background: rank.value?.color ?? 'transparent',
}))

// 段间白缝用重复渐变画，避免在 DOM 里堆 7 个元素
const slitStyle = computed(() => {
  if (props.size === 'fluid') {
    return { backgroundImage: rankSlitFluid() }
  }
  const spec = RANK_BAR[props.size]
  const fill = spec.step - spec.gap
  return {
    backgroundImage: `repeating-linear-gradient(to right, transparent 0, transparent ${fill}px, var(--bar-gap) ${fill}px, var(--bar-gap) ${spec.step}px)`,
  }
})
const label = computed(() => (rank.value ? `段位：${rank.value.name}` : '段位：未定级'))
</script>

<template>
  <span class="rank-bar" :style="barStyle" role="img" :aria-label="label">
    <span class="rank-bar__fill" :style="fillStyle" />
    <span class="rank-bar__slits" :style="slitStyle" />
  </span>
</template>

<style scoped>
.rank-bar {
  position: relative;
  display: inline-block;
  vertical-align: middle;
  overflow: hidden;
  border-radius: 2px;
  background: var(--bar-empty);
}

.rank-bar__fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: 2px 0 0 2px;
}

.rank-bar__slits {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
</style>
