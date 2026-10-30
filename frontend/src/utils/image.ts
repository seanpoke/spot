/**
 * 图片压缩 —— 全链路唯一的一次有损压缩（PRD 6.2）。
 *
 * 分工是硬约定，别在别处再压一遍：
 *   - 前端：归一尺寸 + 压一次，输出即最终 large（1280 / q0.85）
 *   - 后端：正常路径原样落盘，只派生 thumb(400) 与 avatar(96)
 * 两边都压会让画质损失叠加。
 *
 * 手机竖拍的照片靠 `createImageBitmap` 自动按 EXIF 转正（它默认 `from-image`），
 * 不要再自己读 EXIF —— 那是最容易写出方向反了的代码。
 */

const MAX_EDGE = 1280
const QUALITY = 0.85

export async function compressImage(file: File): Promise<File> {
  if (!file.type.startsWith('image/')) {
    throw new Error('只支持图片文件')
  }

  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    // HEIC 等浏览器解不了的格式会走到这里（PRD 6.3 的「四个容易翻车的点」之一）
    throw new Error('这个格式浏览器解不了，请先转成 JPEG')
  }

  try {
    const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height))
    const width = Math.max(1, Math.round(bitmap.width * scale))
    const height = Math.max(1, Math.round(bitmap.height * scale))

    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) {
      throw new Error('浏览器不支持画布，换个浏览器再试')
    }
    context.drawImage(bitmap, 0, 0, width, height)

    const blob = await new Promise<Blob | null>((resolve) => {
      canvas.toBlob(resolve, 'image/jpeg', QUALITY)
    })
    if (!blob) {
      throw new Error('图片压缩失败，请重试')
    }
    return new File([blob], `${file.name.replace(/\.[^.]+$/, '')}.jpg`, { type: 'image/jpeg' })
  } finally {
    bitmap.close()
  }
}
