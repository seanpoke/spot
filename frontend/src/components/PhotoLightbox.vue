<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import SafeImage from '@/components/SafeImage.vue'
import type { PhotoRef } from '@/api/spots'

/**
 * 照片灯箱。
 *
 * 为什么不沿用 `target="_blank"`：手机浏览器里点缩略图会整页跳走（或开新标签页），
 * 3 Mbps 下返回时整页重下；而且 `large` 走的是 CDN 原图，没有「下一张」。
 *
 * 纯 CSS + 指针事件，不引第三方库（AGENTS.md 技术选型：依赖越少越好）。
 * 支持：点遮罩/Esc 关闭、左右按钮、左右滑动、← → 键切换。
 */
const props = withDefaults(
  defineProps<{
    photos: readonly PhotoRef[]
    visible: boolean
    start?: number
  }>(),
  { start: 0 },
)

const emit = defineEmits<{ close: [] }>()

const index = ref(props.start)
/** 拖动位移，用于跟手；松手超过阈值才翻页 */
const dragX = ref(0)
const dragging = ref(false)
let startX = 0

const total = computed(() => props.photos.length)
const current = computed<PhotoRef | undefined>(() => props.photos[index.value])

function clamp(value: number): number {
  return Math.min(Math.max(value, 0), Math.max(total.value - 1, 0))
}

function step(delta: number): void {
  index.value = clamp(index.value + delta)
}

function onPointerDown(event: PointerEvent): void {
  if (total.value < 2) return
  dragging.value = true
  startX = event.clientX
  dragX.value = 0
}

function onPointerMove(event: PointerEvent): void {
  if (!dragging.value) return
  dragX.value = event.clientX - startX
}

function onPointerUp(): void {
  if (!dragging.value) return
  dragging.value = false
  if (dragX.value < -56) step(1)
  else if (dragX.value > 56) step(-1)
  dragX.value = 0
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
  else if (event.key === 'ArrowLeft') step(-1)
  else if (event.key === 'ArrowRight') step(1)
}

watch(
  () => props.visible,
  (open) => {
    if (open) {
      index.value = clamp(props.start)
      dragX.value = 0
      // 灯箱盖住整页时锁住背景滚动，否则手指滑动会把详情页一起拖走
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
    <div v-if="visible && current" class="lb" role="dialog" aria-modal="true" aria-label="查看照片">
      <button class="lb__close" type="button" aria-label="关闭" @click="emit('close')">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M6 6l12 12M18 6 6 18" />
        </svg>
      </button>

      <div
        class="lb__stage"
        @click.self="emit('close')"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerUp"
      >
        <SafeImage
          class="lb__img"
          :class="{ 'lb__img--drag': dragging }"
          :src="current.large"
          :alt="`第 ${index + 1} 张照片`"
          :style="dragX ? { transform: `translateX(${dragX}px)` } : undefined"
          loading="eager"
          draggable="false"
        />
      </div>

      <button
        v-if="index > 0"
        class="lb__nav lb__nav--prev"
        type="button"
        aria-label="上一张"
        @click="step(-1)"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="m15 18-6-6 6-6" />
        </svg>
      </button>

      <button
        v-if="index < total - 1"
        class="lb__nav lb__nav--next"
        type="button"
        aria-label="下一张"
        @click="step(1)"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="m9 18 6-6-6-6" />
        </svg>
      </button>

      <p class="lb__count">{{ index + 1 }} / {{ total }}</p>
    </div>
  </Teleport>
</template>

<style scoped>
.lb {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(12, 14, 18, 0.94);
  animation: lb-in 0.16s ease-out;
}

@keyframes lb-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

.lb__stage {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  touch-action: pan-y;
}

.lb__img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
  transition: transform 0.18s ease-out;
}

/* 跟手时不能有过渡，否则指头像在追图片 */
.lb__img--drag {
  transition: none;
}

.lb__close {
  position: absolute;
  top: calc(12px + env(safe-area-inset-top));
  right: 12px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  cursor: pointer;
}

.lb__nav {
  position: absolute;
  top: 50%;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  transform: translateY(-50%);
  cursor: pointer;
}

.lb__nav--prev { left: 8px; }
.lb__nav--next { right: 8px; }

.lb__count {
  position: absolute;
  bottom: calc(18px + env(safe-area-inset-bottom));
  margin: 0;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  font-variant-numeric: tabular-nums;
}
</style>
