<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { onBackPress } from '@/platform'

/**
 * 根组件：路由出口 + 安卓物理返回键。
 *
 * 返回键规则：底部 Tab 的四页（地图 / 列表 / 统计 / 我的）按返回 = 退出 App，
 * 其余页面 = 回上一页。不接管的话 WebView 会直接把 App 关掉 —— 详情、编辑这种
 * 深一层的页面看到一半就没了。Web 上 onBackPress 是空操作，不影响浏览器行为。
 */
const route = useRoute()
const router = useRouter()

const TAB_PATHS = new Set(['/', '/list', '/stats', '/settings'])

let detach: (() => void) | null = null

onMounted(async () => {
  detach = await onBackPress(() => {
    if (TAB_PATHS.has(route.path)) {
      // 已经在 Tab 页，交给系统退出 App
      return false
    }
    if (route.path === '/gate') {
      return false
    }
    router.back()
    return true
  })
})

onBeforeUnmount(() => {
  detach?.()
})
</script>

<template>
  <router-view />
</template>
