/**
 * 标签颜色解析 —— 全站唯一来源。
 *
 * 后端 `tag.color` 可能为空（老标签没选过颜色、或早期接口没传），
 * 这时按标签名做一个稳定哈希映射到固定色板：**同名永远同色**，
 * 避免同一标签在不同页面一会儿灰、一会儿换色。
 *
 * 色板本身也在这里导出：标签管理页（`/tags`）新建/编辑时挑的就是这 10 个，
 * 与兜底色一致 —— 否则「挑了一个色」和「没挑退回兜底」会得到两种视觉体系。
 */
export const TAG_PALETTE = [
  '#FF5A1E',
  '#12B7F5',
  '#07C160',
  '#8A5CF6',
  '#F5A623',
  '#E8413B',
  '#2AABEE',
  '#FF7BAC',
  '#23B09C',
  '#5A6069',
] as const

/** 解析标签颜色：优先用后端存的，空则按名称稳定映射到兜底色 */
export function resolveTagColor(tag: { name: string; color?: string | null }): string {
  if (tag.color && tag.color.trim()) {
    return tag.color.trim()
  }
  let hash = 0
  for (let index = 0; index < tag.name.length; index += 1) {
    hash = (hash * 31 + tag.name.charCodeAt(index)) >>> 0
  }
  return TAG_PALETTE[hash % TAG_PALETTE.length]
}
