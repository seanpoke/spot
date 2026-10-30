<script setup lang="ts">
/**
 * 分段控件（场所/人物、未打卡/已打卡/想打卡/避雷）。
 *
 * 形态照 PRD 5.7：白胶囊容器 + 选中项反白。
 * **不要做成「灰底滑块」** —— 容器底色一旦等于页面底色，整个控件就消失了。
 * 也不要用 el-radio-group：EP 默认样式会把整页拉回后台模板味。
 *
 * 默认选中项是墨黑（场所/人物这种无状态语义的切换用它）。
 * 传了 `background` 的选项（如打卡状态）选中时改用自己的底色 ——
 * 四个状态必须一眼能分开，全是黑色就退化成「不知道切没切」。
 */
defineProps<{
  modelValue: string
  options: readonly {
    value: string
    label: string
    /** 选中时的底色；缺省用 --ink（墨黑） */
    background?: string
    /** 选中时的字色；缺省白色 */
    foreground?: string
  }[]
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
}>()

function activeStyle(option: { value: string; background?: string; foreground?: string }) {
  if (!option.background) {
    return undefined
  }
  return { background: option.background, color: option.foreground ?? '#fff' }
}
</script>

<template>
  <div class="seg" role="tablist">
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      role="tab"
      class="seg__item"
      :class="{ 'seg__item--on': option.value === modelValue }"
      :style="option.value === modelValue ? activeStyle(option) : undefined"
      :aria-selected="option.value === modelValue"
      @click="emit('update:modelValue', option.value)"
    >
      {{ option.label }}
    </button>
  </div>
</template>

<style scoped>
.seg {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border-radius: 999px;
  background: var(--card);
  box-shadow: var(--sh);
}

.seg__item {
  padding: 7px 15px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--ink2);
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.12s ease, color 0.12s ease;
}

.seg__item--on {
  background: var(--ink);
  color: #fff;
}
</style>
