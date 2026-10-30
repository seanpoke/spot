<script setup lang="ts">
/**
 * 底部 TabBar（四个入口，当前页高亮）。
 *
 * 为什么做成组件：地图页、列表页、统计页、设置页都要它，各写一遍迟早会漂移。
 * 这里统一用 `position: fixed` 贴视口底部 + 限宽居中，滚动页与满屏地图页表现一致。
 *
 * 四个入口：地图 `/`、列表 `/list`、统计 `/stats`、我的 `/settings`。
 * 「日志」不再是独立 tab —— 整页内容已经并到统计页里的一张卡（components/TimelineList.vue）。
 */
withDefaults(defineProps<{ active?: 'map' | 'list' | 'stats' | 'me' }>(), { active: 'map' })
</script>

<template>
  <nav class="tabbar">
    <RouterLink class="tab" :class="{ 'tab--on': active === 'map' }" to="/">
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 21s7-5.6 7-11a7 7 0 1 0-14 0c0 5.4 7 11 7 11z" />
        <circle cx="12" cy="10" r="2.6" />
      </svg>
      <span>地图</span>
    </RouterLink>

    <RouterLink class="tab" :class="{ 'tab--on': active === 'list' }" to="/list">
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <path d="M9 6.5h11M9 12h11M9 17.5h11" />
        <circle cx="4.4" cy="6.5" r="1.15" fill="currentColor" stroke="none" />
        <circle cx="4.4" cy="12" r="1.15" fill="currentColor" stroke="none" />
        <circle cx="4.4" cy="17.5" r="1.15" fill="currentColor" stroke="none" />
      </svg>
      <span>列表</span>
    </RouterLink>

    <RouterLink class="tab" :class="{ 'tab--on': active === 'stats' }" to="/stats">
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
        <path d="M6 19v-5M12 19V6M18 19v-8" />
      </svg>
      <span>统计</span>
    </RouterLink>

    <RouterLink class="tab" :class="{ 'tab--on': active === 'me' }" to="/settings">
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <circle cx="12" cy="8.2" r="3.6" />
        <path d="M5 20c1.4-3.4 4-5 7-5s5.6 1.6 7 5" />
      </svg>
      <span>我的</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.tabbar {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  display: flex;
  align-items: center;
  width: 100%;
  max-width: 720px;
  height: 66px;
  padding-bottom: env(safe-area-inset-bottom, 8px);
  transform: translateX(-50%);
  border-top: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.98);
  backdrop-filter: blur(10px);
}

.tab {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  padding: 0;
  border: 0;
  background: transparent;
  color: #a0a6ae;
  font-family: inherit;
  font-size: 10px;
  font-weight: 500;
  text-decoration: none;
  cursor: pointer;
}

.tab--on {
  color: var(--ink);
  font-weight: 700;
}
</style>
