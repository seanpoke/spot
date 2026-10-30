<script setup lang="ts">
import RankBar from '@/components/RankBar.vue'
import { RANKS, rankOf } from '@/config/ranks'

/**
 * 段位阶梯选择器：7 个并排格子 + 选中后下方的大号刻度条。
 *
 * 这是「段位只走刻度条」的**唯一例外**（PRD 5.2）：格子里的汉字首字只承担
 * 「点哪个」的选择动作，真正表达段位高低的仍是下面那条 40.2 × 9 的刻度条。
 * 别的页面不要照抄这个形态。
 */
defineProps<{ modelValue: number }>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: number): void
}>()
</script>

<template>
  <div class="picker">
    <div class="picker__cells">
      <button
        v-for="rank in RANKS"
        :key="rank.level"
        type="button"
        class="picker__cell"
        :class="{ 'picker__cell--on': rank.level === modelValue }"
        :style="rank.level === modelValue ? { background: rank.color, borderColor: rank.color } : undefined"
        :title="rank.name"
        @click="emit('update:modelValue', rank.level)"
      >
        {{ rank.short }}
      </button>
    </div>

    <div class="picker__confirm">
      <RankBar :level="modelValue" size="large" />
      <span class="picker__name">{{ rankOf(modelValue)?.name }}</span>
      <span class="meta">{{ rankOf(modelValue)?.meaning }}</span>
    </div>
  </div>
</template>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.picker__cells {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
}

.picker__cell {
  height: 44px;
  border: 1px solid var(--line2);
  border-radius: 10px;
  background: var(--card);
  color: var(--ink2);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 700;
  cursor: pointer;
}

.picker__cell--on {
  color: #fff;
}

.picker__confirm {
  display: flex;
  align-items: center;
  gap: 10px;
}

.picker__name {
  font-size: 13.5px;
  font-weight: 700;
}
</style>
