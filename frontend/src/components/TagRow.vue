<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { TagBrief } from '@/api/spots'
import { resolveTagColor } from '@/utils/tagColor'

/**
 * 单行标签，按**可用宽度实测**截断，末尾补 `+N`（AGENTS.md 第 19 条 / PRD 5.6）。
 *
 * 为什么不能写死「显示前 3 个」：标签长短差很多。写死 3 个的结果是
 * 「店」「面」这类短标签白占一行，而「可带电脑久坐」这种长标签被无端切掉。
 *
 * 做法：容器里藏一条不可见的「标尺行」，把全部标签按同样样式渲染一遍，
 * 逐个量 offsetWidth，凑不下就整颗隐藏。宽度变化（转屏、字号、面板变宽）由 ResizeObserver 触发重算。
 */
const props = defineProps<{ tags: readonly TagBrief[] }>()

/** 与 .tagrow 的 gap 保持一致 */
const TAG_GAP = 4

const wrap = ref<HTMLElement | null>(null)
const ruler = ref<HTMLElement | null>(null)
const visibleCount = ref(props.tags.length)

const shown = computed(() => props.tags.slice(0, visibleCount.value))
const rest = computed(() => Math.max(0, props.tags.length - visibleCount.value))

/** 标签底色/字色由标签自身颜色推导；只改变颜色不改字号，不影响上面的宽度实测 */
function tagStyle(tag: TagBrief): Record<string, string> {
  const color = resolveTagColor(tag)
  return {
    background: `color-mix(in srgb, ${color} 16%, #fff)`,
    color: `color-mix(in srgb, ${color} 72%, #000)`,
  }
}

let observer: ResizeObserver | null = null

function fit(): void {
  const box = wrap.value
  const measure = ruler.value
  if (!box || !measure) {
    return
  }
  const available = box.clientWidth
  const items = Array.from(measure.querySelectorAll<HTMLElement>('[data-tag]'))
  if (items.length === 0 || available <= 0) {
    visibleCount.value = items.length
    return
  }
  // `+N` 的宽度也要计入：只要还要显示它，就得给它留位置
  const plusWidth = measure.querySelector<HTMLElement>('[data-more]')?.offsetWidth ?? 0
  let used = 0
  let count = 0
  for (let i = 0; i < items.length; i += 1) {
    const need = items[i].offsetWidth + (i > 0 ? TAG_GAP : 0)
    const tail = i < items.length - 1 ? TAG_GAP + plusWidth : 0
    if (used + need + tail > available) {
      break
    }
    used += need
    count = i + 1
  }
  visibleCount.value = count
}

onMounted(() => {
  void nextTick(fit)
  if (wrap.value) {
    observer = new ResizeObserver(() => fit())
    observer.observe(wrap.value)
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})

watch(
  () => props.tags,
  () => {
    void nextTick(fit)
  },
  { deep: true },
)
</script>

<template>
  <div ref="wrap" class="tagrow">
    <span v-for="tag in shown" :key="tag.id" class="tg" :style="tagStyle(tag)">{{ tag.name }}</span>
    <span v-if="rest > 0" class="tgmore">+{{ rest }}</span>

    <!-- 标尺：同款样式、不可见，只用来量宽度 -->
    <span ref="ruler" class="tagrow__ruler" aria-hidden="true">
      <span v-for="tag in tags" :key="tag.id" data-tag class="tg" :style="tagStyle(tag)">{{ tag.name }}</span>
      <span data-more class="tgmore">+{{ tags.length }}</span>
    </span>
  </div>
</template>

<style scoped>
.tagrow {
  position: relative;
  display: flex;
  gap: 4px;
  overflow: hidden;
  flex-wrap: nowrap;
}

/* 单个标签最长 94px（约 9 个汉字），超出自己省略 */
.tg {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  height: 19px;
  max-width: 94px;
  overflow: hidden;
  padding: 0 7px;
  background: #f4f6f8;
  border-radius: 5px;
  color: var(--ink2);
  font-size: 10.5px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tgmore {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  height: 19px;
  padding: 0 5px;
  color: var(--ink3);
  font-size: 10.5px;
  font-weight: 600;
  white-space: nowrap;
}

.tagrow__ruler {
  position: absolute;
  top: 0;
  left: 0;
  display: flex;
  gap: 4px;
  visibility: hidden;
  pointer-events: none;
}
</style>
