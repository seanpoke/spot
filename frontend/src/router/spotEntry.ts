import type { Router } from 'vue-router'

/**
 * 「从哪来回哪去」的入口记忆。
 *
 * 详情页的返回按钮原来无脑回首页地图，从列表页 / 统计页点进来的用户会被打断。
 * 这里在路由跳转时记一条：**进入打卡点页面（详情 / 编辑 / 到访）之前，最近一次所在的非打卡点页面**。
 *
 * 为什么不用 `router.back()` 直接回退：历史栈在 `replace` 之后不可信（编辑页保存用的是 replace），
 * 更关键的是删除打卡点后**不能回退到刚被删掉的详情页**。所以入口单独记，回退只是「碰巧可行」时的优化。
 */
let entryPath = ''
const SPOT_PATH = /^\/spot\//

export function installSpotEntryTracker(router: Router): void {
  router.afterEach((to, from) => {
    if (!SPOT_PATH.test(to.path)) {
      return
    }
    // 打卡点页面之间互相跳（详情→编辑、人物→所属场所）不改写入口：
    // 入口始终是「最近一次离开的那个非打卡点页面」
    if (SPOT_PATH.test(from.path)) {
      return
    }
    entryPath = from.fullPath || '/'
  })
}

/** 记下的入口；直接开链接（没有来历）或入口就是打卡点页面本身时退回 fallback */
export function spotEntryPath(fallback = '/'): string {
  return entryPath || fallback
}

/**
 * 离开打卡点页面。
 *
 * 上一次历史正好就是入口时用 `router.back()`：列表页能保住滚动位置，历史栈也不膨胀。
 * 其余情况（刚删完点、从编辑页 replace 回来、入口被 replace 掉）用 `replace` 直达入口，
 * 免得把用户带回一个已经不存在的打卡点。
 */
export function leaveSpotPage(router: Router, fallback = '/'): void {
  const target = spotEntryPath(fallback)
  const back = (window.history.state as { back?: string | null } | null)?.back
  if (typeof back === 'string' && back === target) {
    router.back()
    return
  }
  void router.replace(target)
}
