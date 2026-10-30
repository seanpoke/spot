import { request } from './http'

/**
 * 图片上传。
 *
 * 返回的三个地址**已经是可以直接塞进 `<img src>` 的完整字符串**：
 *   - 本地开发（`CDN_BASE_URL` 为空）→ `/uploads/2026/10/x_l.jpg`，由 Vite proxy 转到后端
 *   - 线上配了 CDN → `https://images.sean.ltd/visit/2026/10/x_l.jpg`
 *     （`/uploads` 段只在本地出现；线上由 `CDN_BASE_URL` 的项目前缀替代）
 *
 * 前端不要自己拼 `/uploads/` 前缀（AGENTS.md 第 17 条），
 * 也不要因为本地拿到的是相对路径就写死判断 —— 换环境时这里一行都不用改。
 */
export interface UploadedImage {
  id: string
  large: string
  thumb: string
  avatar: string
  width: number
  height: number
  size: number
}

export function uploadImage(file: File): Promise<UploadedImage> {
  const form = new FormData()
  form.append('file', file)
  return request<UploadedImage>('/upload/image', { method: 'POST', body: form })
}
