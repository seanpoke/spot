import { createApp } from 'vue'
import ElementPlus from 'element-plus'
// EP 的日期面板默认是英文（October / Sun..Sat）。语言包必须在 use() 时给，
// 事后改 dayjs 也不管用：面板的月份与星期名都来自这份 locale。
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'
import { markAnonymous } from './api/auth'
import { setUnauthorizedHandler } from './api/http'

// 样式顺序不能变：EP 默认样式 -> 换肤覆盖 -> 全局样式与令牌
import 'element-plus/dist/index.css'
import './styles/tokens.css'
import './styles/element-theme.css'
import './styles/global.css'
import './styles/form-feedback.css'

/**
 * Element Plus 全量引入（不做按需引入）：配置简单优先，见 AGENTS.md 第二节。
 * 但视觉主体仍用手写组件，EP 只兜住日期选择、下拉、抽屉、消息这类交互复杂的部件。
 */
/**
 * 会话失效的统一处理：任何接口返回 401 都清掉本地登录记忆并回口令门。
 * 放在这里而不是每个页面里：写一次就够，页面不用关心会话。
 */
setUnauthorizedHandler(() => {
  markAnonymous()
  void router.replace('/gate')
})

createApp(App).use(ElementPlus, { locale: zhCn }).use(router).mount('#app')
