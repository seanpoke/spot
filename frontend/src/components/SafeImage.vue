<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import defaultAvatar from '@/assets/default-avatar.png'
import defaultPhoto from '@/assets/default-photo.png'

/**
 * 带兜底的 `<img>`：**没配图**或**读取失败**时换成本地默认图。
 *
 * 为什么做成组件而不是每处写 `@error`：兜底图是打包进前端的本地资源（`src/assets/`），
 * 换一张图要改的只有这里；散在十几个地方写 `e.target.src = ...` 迟早漏一个，
 * 表现就是某页裂图。图片域名（`images.sean.ltd`）挂了的时候全站一起兜住。
 *
 * 用法上就是个 img：class / style 等属性会自动落到根节点上，原有样式不用改。
 */
const props = withDefaults(
  defineProps<{
    /** 图片地址；空串 / null 直接用默认图 */
    src?: string | null
    /** avatar = 默认头像（人像剪影）；photo = 默认图片（风景占位） */
    kind?: 'avatar' | 'photo'
    alt?: string
    /** 首屏大图用 eager，其余保持 lazy */
    loading?: 'eager' | 'lazy'
  }>(),
  { src: '', kind: 'photo', alt: '', loading: 'lazy' },
)

/** 已经失败过就不再换（默认图本身也失败时避免死循环） */
const failed = ref(false)

const fallback = computed(() => (props.kind === 'avatar' ? defaultAvatar : defaultPhoto))
const resolved = computed(() => (props.src && !failed.value ? props.src : fallback.value))

// 换了地址（比如编辑页重新上传）要给它一次重新加载的机会
watch(
  () => props.src,
  () => {
    failed.value = false
  },
)

function onError(): void {
  failed.value = true
}
</script>

<template>
  <img :src="resolved" :alt="alt" :loading="loading" decoding="async" @error="onError" />
</template>
