import { createRouter, createWebHistory } from 'vue-router'
import { ensureSession } from '@/api/auth'
import { installSpotEntryTracker } from './spotEntry'

/**
 * 路由表。
 *
 * PRD 第三章的 8 个页面全部落地：
 *
 *   /                     地图主页      全屏地图 + 标记 + 三档底部面板
 *   /list                 列表          省市区 / 状态 / 等级 / 标签 / 价格 / 关键字 组合筛选
 *   /spot/:id             打卡点详情
 *   /spot/:id/visit/new   到访录入
 *   /timeline             → 重定向到 /stats（原「狂人日志」整页已并成统计页的一张卡）
 *   /stats                统计          总量 + 段位分布 + 每月到访 + 去得最多 + 狂人日志
 *   /settings             设置          全量导出（JSON / CSV）+ 口令说明 + 退出登录
 *   /gate                 口令门
 *   /spot/new、/spot/:id/edit、/visit/:id/edit  表单页
 *
 * 另有 `/debug` 环境自检（从地图页 ⋯ 菜单进入，不是正式页面）。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/gate',
      name: 'gate',
      component: () => import('@/views/GateView.vue'),
    },
    {
      path: '/spot/new',
      name: 'spot-new',
      component: () => import('@/views/SpotEditView.vue'),
    },
    {
      path: '/spot/:id/edit',
      name: 'spot-edit',
      component: () => import('@/views/SpotEditView.vue'),
    },
    {
      path: '/spot/:id/visit/new',
      name: 'visit-new',
      component: () => import('@/views/VisitView.vue'),
    },
    {
      // 与新建同一个页面：编辑态多一个「删除」，保存走 PUT
      path: '/visit/:id/edit',
      name: 'visit-edit',
      component: () => import('@/views/VisitView.vue'),
    },
    {
      // 必须排在 /spot/new 之后：动态段会吃掉静态路径
      path: '/spot/:id',
      name: 'spot-detail',
      component: () => import('@/views/SpotDetailView.vue'),
    },
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/MapHomeView.vue'),
    },
    {
      path: '/list',
      name: 'list',
      component: () => import('@/views/SpotListView.vue'),
    },
    {
      // 老链接与历史记录还会指到这里：日志已经不是独立页面了
      path: '/timeline',
      redirect: '/stats',
    },
    {
      path: '/stats',
      name: 'stats',
      component: () => import('@/views/StatsView.vue'),
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
    },
    {
      // 环境自检：不是正式页面，从地图页右上角「⋯」进来
      path: '/debug',
      name: 'debug',
      component: () => import('@/views/DebugView.vue'),
    },
  ],
  // 前进 / 打开新页面一律回到顶部；后退（含详情页返回列表）交还给浏览器记下的位置。
  // 列表页自己还有一层「按上次条数续载后再滚」的处理（SpotListView.restoreScroll），
  // 因为首次渲染只有 20 条，光靠这里滚不到深处。
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
})

// 记录「进入打卡点页面之前在哪」，详情页返回与删除后跳转都靠它（见 spotEntry.ts）
installSpotEntryTracker(router)

/**
 * 口令守卫：唯一的安全判断点，页面里不要再各写一遍。
 *
 * 真正的校验在服务端（AuthInterceptor），这里只决定「要不要跳口令门」。
 * 会话有效性的本地记忆由 api/auth.ts 维护，所以正常翻页不会反复打接口。
 */
router.beforeEach(async (to) => {
  if (to.path === '/gate') {
    // 已经登录还去口令门没有意义，直接送回首页
    return (await ensureSession()) ? { path: '/' } : true
  }

  if (await ensureSession()) {
    return true
  }
  // 记下原目标，登录后回到原处；未登录时也会被带回这里
  return { path: '/gate', query: { redirect: to.fullPath } }
})

export default router
