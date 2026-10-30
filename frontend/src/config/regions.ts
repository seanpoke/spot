/**
 * 省 / 市 / 区的选项与归一化 —— 全部基于内置静态数据（`chinaRegions.ts`）。
 *
 * 三条边界（都很容易踩）：
 *   1) **不联网**：选项不来自高德，也不来自 `GET /api/spots/regions`（那是「已录入过的组合」，
 *      没录过的城市永远选不到）。省市区纯本地查表，断网也能改。
 *   2) **三个都不是必填**：空串就是没填，提交时由 `emptyToNull` 变 null。
 *   3) **名字要归一化**：逆地理编码回的是「武汉」还是「武汉市」不由我们决定，
 *      而表里存的是全称。`resolveRegion` 负责把简称/全称对齐，都对不上就原样保留（不能丢信息）。
 */

import { CHINA_REGIONS } from './chinaRegions'

/** 省 / 直辖市 / 自治区（内地 31 项，不含港澳台） */
export const PROVINCES: string[] = CHINA_REGIONS.map(([name]) => name)

/** 行政后缀：比对「武汉」与「武汉市」时剥掉再比 */
const ADMIN_SUFFIX = /(特别行政区|自治区|自治州|自治县|自治旗|地区|林区|新区|省|市|区|县|旗|盟)$/u

function shorten(name: string): string {
  return name.replace(ADMIN_SUFFIX, '')
}

/** 在一组名字里找最匹配的那个；找不到返回空串 */
function match(list: string[], name?: string): string {
  const raw = name?.trim() ?? ''
  if (!raw) {
    return ''
  }
  const exact = list.find((item) => item === raw)
  if (exact) {
    return exact
  }
  const short = shorten(raw)
  if (!short) {
    return ''
  }
  return list.find((item) => item === short || shorten(item) === short) ?? ''
}

/** 某省下的市；省名不认识就返回空数组 */
export function citiesOf(province: string): string[] {
  const node = CHINA_REGIONS.find(([name]) => name === province)
  return node ? node[1].map(([city]) => city) : []
}

/** 某市下的区县；直辖市传省名当市名 */
export function districtsOf(province: string, city: string): string[] {
  const node = CHINA_REGIONS.find(([name]) => name === province)
  const found = node?.[1].find(([name]) => name === city)
  return found ? found[1] : []
}

export interface RegionTriple {
  province: string
  city: string
  district: string
}

/**
 * 把外部来的省市区（地图选点反查、老数据、手填）对齐到内置表里的标准名。
 *
 * 对不上就**原样返回**，不要退回空串：反查回「某某新区」这类表里没有的名字时，
 * 丢掉它比留着更糟（用户在地图上点的位置信息就没了）。
 */
export function resolveRegion(input: {
  province?: string
  city?: string
  district?: string
}): RegionTriple {
  const rawProvince = input.province?.trim() ?? ''
  const rawCity = input.city?.trim() ?? ''
  const rawDistrict = input.district?.trim() ?? ''

  const province = match(PROVINCES, rawProvince)
  if (!province) {
    return { province: rawProvince, city: rawCity, district: rawDistrict }
  }

  const cities = citiesOf(province)
  let city = match(cities, rawCity)
  if (!city && cities.length === 1) {
    // 直辖市 / 省直辖县：反查的 city 可能是空数组，只有市一级就是省本身
    city = cities[0]
  }
  if (!city) {
    city = match(cities, province)
  }

  const district = match(city ? districtsOf(province, city) : [], rawDistrict) || rawDistrict
  return { province, city, district }
}

/** 剥掉一层前缀：全称优先；省 / 市 允许用简称（「武汉」对「武汉市」） */
function stripLeading(text: string, name: string, allowShort: boolean): string {
  const value = name.trim()
  if (!value) {
    return text
  }
  if (text.startsWith(value)) {
    return text.slice(value.length).trim()
  }
  if (!allowShort) {
    return text
  }
  const short = shorten(value)
  if (short && short !== value && text.startsWith(short)) {
    return text.slice(short.length).trim()
  }
  return text
}

/**
 * 把地址里的省市区前缀剥掉，详细地址只留「xxx路xxx号」。
 *
 * 逆地理编码给的是「湖北省武汉市江夏区佛祖岭街道施耐德大厦」，而省市区已经单独存了，
 * 地址里再带一遍就是重复。
 *
 * 区县只认全称：简称容易误伤 —— 区名「江汉区」、路名「江汉路」，
 * 按简称剥会把「江汉路 1 号」切成「路 1 号」。
 */
export function stripRegionPrefix(
  address: string,
  province: string,
  city: string,
  district: string,
): string {
  let rest = address.trim()
  rest = stripLeading(rest, province, true)
  rest = stripLeading(rest, city, true)
  rest = stripLeading(rest, district, false)
  return rest
}
