import { reactive } from 'vue'

/**
 * 表单字段校验反馈（与具体校验规则无关）。
 *
 * 页面负责判断「哪里错了、错什么」，这里只提供三件事：
 *   - 错误文案（模板里 v-if 显示）
 *   - 抖动开关（连续报错可重放，动画结束由模板 @animationend 复位）
 *   - 清除（用户把值改对后自动收起）
 *
 * 用法见 SpotEditView：外层 `:class="{ 'ff-field--error': has(f), 'ff-field--shake': isShaking(f) }"`，
 * 抖动用 `@animationend="stopShake(f)"` 收尾。
 */
export function useFieldFeedback<K extends string = string>() {
  const messages = reactive<Record<string, string>>({})
  /** 抖动中的字段集合：Set 也是响应式的，模板里 has() 能触发更新 */
  const shaking = reactive(new Set<string>())

  /** 标记出错并抖动一次；同一条错误连续触发也会重新抖 */
  function fail(field: K, message: string): void {
    messages[field] = message
    shaking.delete(field)
    requestAnimationFrame(() => shaking.add(field))
  }

  /** 值改对后收起提示（动画由 Transition 负责） */
  function clear(field: K): void {
    if (messages[field] !== undefined) {
      delete messages[field]
    }
  }

  /** 抖动动画结束 */
  function stopShake(field: K): void {
    shaking.delete(field)
  }

  function has(field: K): boolean {
    return messages[field] !== undefined
  }

  function messageOf(field: K): string {
    return messages[field] ?? ''
  }

  function isShaking(field: K): boolean {
    return shaking.has(field)
  }

  return { fail, clear, stopShake, has, messageOf, isShaking }
}
