/**
 * 全量导出的下载地址（设置页「备份」区块）。
 *
 * 刻意用「给 `<a href>` 直接用」的方式，而不是 fetch + Blob：
 *   - 同源导航会自动带上 HttpOnly 的会话 Cookie，不用手写请求头；
 *   - 文件名由后端 `Content-Disposition` 定，前端不必解析；
 *   - 3 Mbps 带宽下，浏览器原生下载比读进内存再落盘更省内存（导出文件可能几 MB）。
 * 代价是失败时页面会显示一段 JSON 错误体，而不是一句 toast —— 单用户可接受。
 */
export const EXPORT_LINKS = {
  /** 全量 JSON 备份：四张表 + 点与标签的关系 */
  json: '/api/export/json',
  /** 打卡点 CSV（含标签、到访次数、平均分），带 UTF-8 BOM 可直接进 Excel */
  spotsCsv: '/api/export/spots.csv',
  /** 到访 CSV（分项评分是原始 JSON 串，原样带走） */
  visitsCsv: '/api/export/visits.csv',
} as const
