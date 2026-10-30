<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'

/**
 * 底部选项框（iOS ActionSheet 那种）。
 *
 * 为什么不引第三方库：这里只需要「遮罩 + 上滑面板 + 若干按钮」，
 * 自己写 60 行 CSS 比引一个依赖更可控（AGENTS.md 技术选型：依赖越少越好）。
 *
 * **不带标题栏**：面板里几个按钮就是几个选项，点的是哪一条用户刚刚才点过。
 *
 * 行为：点遮罩 / 点取消 / Esc 关闭，打开时锁背景滚动。
 * 样式：白面板 14px 圆角、按钮 52px 高、底部让出 iPhone 安全区。
 */
interface SheetAction {
  key: string
  label: string
  /** 行尾的补充说明，可选 */
  hint?: string
}

const props = withDefaults(
  defineProps<{
    visible: boolean
    actions: SheetAction[]
    cancelText?: string
  }>(),
  { cancelText: '取消' },
)

const emit = defineEmits<{ select: [key: string]; close: [] }>()

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
}

watch(
  () => props.visible,
  (open) => {
    if (open) {
      // 面板在底部，背景还能滚的话手指会带着整页动
      document.body.style.overflow = 'hidden'
      window.addEventListener('keydown', onKeydown)
    } else {
      document.body.style.overflow = ''
      window.removeEventListener('keydown', onKeydown)
    }
  },
)

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="sheet" role="dialog" aria-modal="true" aria-label="选项">
      <button class="sheet__mask" type="button" aria-label="关闭" @click="emit('close')" />

      <div class="sheet__panel">
        <div class="sheet__group">
          <button
            v-for="action in actions"
            :key="action.key"
            class="sheet__item"
            type="button"
            @click="emit('select', action.key)"
          >
            <span class="sheet__label">{{ action.label }}</span>
            <span v-if="action.hint" class="sheet__hint">{{ action.hint }}</span>
          </button>
        </div>

        <button class="sheet__cancel" type="button" @click="emit('close')">{{ cancelText }}</button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.sheet {
  position: fixed;
  inset: 0;
  z-index: 3100;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
}

.sheet__mask {
  position: absolute;
  inset: 0;
  width: 100%;
  padding: 0;
  border: 0;
  background: rgba(17, 19, 24, 0.42);
  animation: sheet-fade 0.16s ease-out;
  cursor: default;
}

@keyframes sheet-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}

.sheet__panel {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  /* 桌面端不铺满整屏，手机端左右各留 8px */
  max-width: 520px;
  padding: 0 8px calc(8px + env(safe-area-inset-bottom));
  animation: sheet-up 0.2s cubic-bezier(0.2, 0.8, 0.3, 1);
}

@keyframes sheet-up {
  from { transform: translateY(24px); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}

.sheet__group {
  background: var(--card);
  border-radius: 14px;
  overflow: hidden;
}

.sheet__item {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  min-height: 52px;
  padding: 0 16px;
  border: 0;
  border-top: 1px solid var(--line);
  background: transparent;
  font: inherit;
  color: var(--ink);
  cursor: pointer;
}

/* 首条不画分割线：否则面板顶部会多出一条毫无来由的细线 */
.sheet__item:first-child {
  border-top: 0;
}

.sheet__label {
  font-size: 15px;
  font-weight: 700;
}

.sheet__hint {
  font-size: 12px;
  font-weight: 500;
  color: var(--ink3);
}

.sheet__cancel {
  width: 100%;
  min-height: 52px;
  border: 0;
  border-radius: 14px;
  background: var(--card);
  color: var(--ink);
  font: inherit;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
}
</style>
