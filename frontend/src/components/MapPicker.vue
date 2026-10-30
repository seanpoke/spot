<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import {
  DEFAULT_CENTER,
  amapStyle,
  cityOfComponent,
  loadAmap,
  readLngLat,
  type AmapAutoComplete,
  type AmapGeocoder,
  type AmapMap,
  type AmapNamespace,
  type AmapOverlay,
  type AmapPlaceSearch,
  type AmapTip,
} from '@/config/amap'

/**
 * 地图选点（编辑页的「地图选点」按钮打开的全屏浮层）。
 *
 * 三件事：搜地点名 → 落一个针 → 反查地址；或者直接点地图上任意一点。
 * 为什么做成浮层而不是独立路由：选完要回填表单，走路由会把编辑页整页销毁，
 * 用户正在填的其它字段就没了。
 *
 * 高德的 AutoComplete / Geocoder / PlaceSearch 都是插件，必须先 `AMap.plugin()` 加载，
 * 不加载时 `window.AMap.AutoComplete` 是 undefined —— 表现是「搜索框打字没反应」。
 */
const props = defineProps<{
  lat?: number | null
  lng?: number | null
  address?: string
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'confirm', value: { lat: number; lng: number; address: string; city: string; province: string; district: string }): void
}>()

const mapEl = ref<HTMLDivElement | null>(null)
const keyword = ref('')
const tips = ref<AmapTip[]>([])
const searching = ref(false)
const loading = ref(true)
const errorText = ref('')
const picked = ref<{ lng: number; lat: number } | null>(null)
const addressText = ref(props.address ?? '')
const cityText = ref('')
const provinceText = ref('')
const districtText = ref('')

const map = shallowRef<AmapMap | null>(null)
let marker: AmapOverlay | null = null
let geocoder: AmapGeocoder | null = null
let autocomplete: AmapAutoComplete | null = null
let placeSearch: AmapPlaceSearch | null = null
let searchTimer: number | undefined

const hasInitial = typeof props.lat === 'number' && typeof props.lng === 'number'

function ensurePlugins(amap: AmapNamespace): Promise<void> {
  if (amap.AutoComplete && amap.Geocoder && amap.PlaceSearch) {
    return Promise.resolve()
  }
  if (typeof amap.plugin !== 'function') {
    return Promise.resolve()
  }
  return new Promise<void>((resolve) => {
    amap.plugin!(['AMap.AutoComplete', 'AMap.Geocoder', 'AMap.PlaceSearch'], () => resolve())
  })
}

function createPin(): HTMLElement {
  const pin = document.createElement('div')
  pin.style.cssText =
    'width:30px;height:30px;border-radius:50%;background:#ff5a1e;border:3px solid #fff;' +
    'box-shadow:0 4px 14px -2px rgba(255,90,30,.55),0 0 0 1px rgba(17,24,39,.06)'
  return pin
}

function placeMarker(coords: { lng: number; lat: number }): void {
  const amap = window.AMap
  const instance = map.value
  if (!amap || !instance) {
    return
  }
  if (marker) {
    marker.setPosition([coords.lng, coords.lat])
    return
  }
  marker = new amap.Marker({
    position: [coords.lng, coords.lat],
    content: createPin(),
    anchor: 'center',
    zIndex: 120,
  })
  instance.add(marker)
}

function reverseGeocode(coords: { lng: number; lat: number }): void {
  geocoder?.getAddress([coords.lng, coords.lat], (status, result) => {
    if (status !== 'complete') {
      return
    }
    const regeocode = result.regeocode
    if (!regeocode) {
      return
    }
    if (regeocode.formattedAddress) {
      addressText.value = regeocode.formattedAddress
    }
    const component = regeocode.addressComponent
    const city = cityOfComponent(component)
    if (city) {
      cityText.value = city
    }
    if (component?.province) {
      provinceText.value = component.province
    }
    if (component?.district) {
      districtText.value = component.district
    }
  })
}

function pickPoint(coords: { lng: number; lat: number }, moveMap: boolean): void {
  picked.value = coords
  placeMarker(coords)
  if (moveMap) {
    map.value?.setCenter([coords.lng, coords.lat])
    map.value?.setZoom(16)
  }
  reverseGeocode(coords)
}

function onMapClick(event: { lnglat?: unknown }): void {
  const coords = readLngLat(event.lnglat as { getLng?(): number; getLat?(): number } | undefined)
  if (!coords) {
    return
  }
  tips.value = []
  pickPoint(coords, false)
}

async function initMap(): Promise<void> {
  if (!mapEl.value) {
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    await loadAmap()
    const amap = window.AMap
    if (!amap || !mapEl.value) {
      throw new Error('高德 JS API 未就绪')
    }
    await ensurePlugins(amap)

    const center: [number, number] = hasInitial
      ? [props.lng as number, props.lat as number]
      : DEFAULT_CENTER
    const instance = new amap.Map(mapEl.value, {
      zoom: hasInitial ? 16 : 12,
      center,
      viewMode: '2D',
      mapStyle: amapStyle(),
      features: ['bg', 'road', 'building'],
      showLabel: true,
      resizeEnable: true,
      zooms: [3, 19],
    })
    map.value = instance
    instance.on('click', onMapClick)

    if (hasInitial) {
      picked.value = { lng: props.lng as number, lat: props.lat as number }
      placeMarker(picked.value)
    }

    if (amap.Geocoder) {
      geocoder = new amap.Geocoder({ radius: 200 })
    }
    if (amap.AutoComplete) {
      // city 全国 + datatype all：既能搜到店名，也能搜到小区/路口
      autocomplete = new amap.AutoComplete({ city: '全国', citylimit: false, datatype: 'all' })
    }
    if (amap.PlaceSearch) {
      placeSearch = new amap.PlaceSearch({ pageSize: 1, extensions: 'base' })
    }
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : String(error)
  } finally {
    loading.value = false
  }
}

function onKeywordInput(): void {
  window.clearTimeout(searchTimer)
  const query = keyword.value.trim()
  if (!query) {
    tips.value = []
    return
  }
  searchTimer = window.setTimeout(() => searchTips(query), 220)
}

function searchTips(query: string): void {
  if (!autocomplete) {
    return
  }
  searching.value = true
  autocomplete.search(query, (status, result) => {
    // 建议为空是常态（比如只敲了一个字），保持静默，不弹提示打扰
    searching.value = false
    tips.value = status === 'complete' ? (result.tips ?? []).filter((tip) => !!tip.name) : []
  })
}

/** 商店类建议常常只有名字没有坐标，这时再用 PlaceSearch 查一次 */
function searchPoi(name: string): void {
  if (!placeSearch) {
    ElMessage.warning('这个地点没有坐标，换个说法搜搜')
    return
  }
  placeSearch.search(name, (status, result) => {
    const poi = result.poiList?.pois?.[0]
    const location = poi?.location
    if (status === 'complete' && location) {
      pickPoint(location, true)
      addressText.value = poi?.address || poi?.name || name
      return
    }
    ElMessage.warning('没找到这个地点的坐标，试试点地图选')
  })
}

function chooseTip(tip: AmapTip): void {
  tips.value = []
  keyword.value = tip.name ?? ''
  if (tip.location) {
    pickPoint(tip.location, true)
    addressText.value = [tip.district, tip.name].filter((part) => !!part).join(' ')
    // 搜索建议只带区，省/市靠反查回填；这里先把能拿到的区留住
    if (tip.district) {
      districtText.value = tip.district
    }
    return
  }
  if (tip.name) {
    searchPoi(tip.name)
  }
}

function confirm(): void {
  const coords = picked.value
  if (!coords) {
    ElMessage.warning('先在地图上点一个位置')
    return
  }
  // 6 位小数 ≈ 0.1m，够用且能塞进 DECIMAL(10,7)
  emit('confirm', {
    lat: Number(coords.lat.toFixed(6)),
    lng: Number(coords.lng.toFixed(6)),
    address: addressText.value.trim(),
    city: cityText.value.trim(),
    province: provinceText.value.trim(),
    district: districtText.value.trim(),
  })
}

onMounted(initMap)

onBeforeUnmount(() => {
  window.clearTimeout(searchTimer)
  marker = null
  map.value?.destroy()
  map.value = null
})
</script>

<template>
  <div class="pk">
    <header class="pk__top">
      <div class="pk__search">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
          <circle cx="11" cy="11" r="6.6" />
          <path d="M16.2 16.2 20.5 20.5" />
        </svg>
        <input
          v-model="keyword"
          type="search"
          placeholder="搜地点名，如 光谷步行街"
          @input="onKeywordInput"
          @keyup.enter.prevent="searchTips(keyword.trim())"
        />
        <button v-if="keyword" type="button" class="pk__clear" @click="keyword = ''; tips = []">×</button>
      </div>
      <button type="button" class="pk__cancel" @click="emit('close')">取消</button>
    </header>

    <div ref="mapEl" class="pk__map" />

    <p v-if="loading" class="pk__tip">地图加载中…</p>
    <div v-else-if="errorText" class="pk__error card">
      <b>地图没加载出来</b>
      <p>{{ errorText }}</p>
      <button type="button" @click="initMap">重试</button>
    </div>
    <p v-else-if="!picked" class="pk__tip">点地图任意位置，或用上面的搜索框找地点</p>

    <ul v-if="tips.length" class="pk__tips card">
      <li v-for="(tip, index) in tips" :key="`${tip.name}-${index}`">
        <button type="button" @click="chooseTip(tip)">
          <b>{{ tip.name }}</b>
          <span v-if="tip.district || tip.address">{{ tip.district }} {{ tip.address }}</span>
        </button>
      </li>
      <li v-if="searching" class="pk__tips-state">搜索中…</li>
    </ul>

    <footer class="pk__foot card">
      <div class="pk__addr">
        <b>{{ picked ? '已选位置' : '还没选位置' }}</b>
        <span>{{ addressText || '选好后会带回地址与坐标，仍可再手动改' }}</span>
      </div>
      <button class="btn" type="button" :disabled="!picked" @click="confirm">用这个位置</button>
    </footer>
  </div>
</template>

<style scoped>
.pk {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  flex-direction: column;
  max-width: 720px;
  margin: 0 auto;
  background: var(--bg);
}

.pk__top {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: calc(env(safe-area-inset-top, 0px) + 12px) 12px 10px;
}

.pk__search {
  display: flex;
  flex: 1;
  gap: 7px;
  align-items: center;
  min-width: 0;
  height: 44px;
  padding: 0 12px;
  border-radius: 999px;
  background: var(--card);
  box-shadow: 0 2px 8px -2px rgba(17, 24, 39, 0.16);
  color: var(--ink3);
}

.pk__search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 500;
}

.pk__clear {
  flex: none;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--bg);
  color: var(--ink2);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.pk__cancel {
  flex: none;
  height: 44px;
  padding: 0 14px;
  border: 0;
  border-radius: 999px;
  background: var(--card);
  box-shadow: 0 2px 8px -2px rgba(17, 24, 39, 0.16);
  color: var(--ink2);
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.pk__map {
  flex: 1;
  min-height: 0;
  background: var(--map-bg);
}

.pk__tip {
  position: absolute;
  top: 96px;
  left: 50%;
  margin: 0;
  padding: 7px 13px;
  transform: translateX(-50%);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 2px 10px -4px rgba(17, 24, 39, 0.4);
  color: var(--ink2);
  font-size: 11.5px;
  font-weight: 600;
  pointer-events: none;
  white-space: nowrap;
}

.pk__error {
  position: absolute;
  top: 42%;
  left: 50%;
  width: min(300px, calc(100% - 48px));
  padding: 16px;
  transform: translate(-50%, -50%);
  text-align: center;
}

.pk__error b {
  display: block;
  margin-bottom: 6px;
  font-size: 14px;
}

.pk__error p {
  margin: 0 0 12px;
  color: var(--ink2);
  font-size: 11.5px;
  line-height: 1.7;
  word-break: break-word;
}

.pk__error button {
  padding: 8px 16px;
  border: 0;
  border-radius: 9px;
  background: var(--ink);
  color: #fff;
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
}

.pk__tips {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 66px);
  right: 12px;
  left: 12px;
  max-height: 46vh;
  padding: 4px;
  margin: 0;
  overflow-y: auto;
  list-style: none;
}

.pk__tips li button {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--ink);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.pk__tips li button:active {
  background: var(--bg);
}

.pk__tips b {
  font-size: 13px;
  font-weight: 700;
}

.pk__tips span {
  overflow: hidden;
  color: var(--ink3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pk__tips-state {
  padding: 10px 12px;
  color: var(--ink3);
  font-size: 11.5px;
}

.pk__foot {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 10px 12px calc(env(safe-area-inset-bottom, 0px) + 12px);
  padding: 12px 14px;
}

.pk__addr {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.pk__addr b {
  font-size: 13px;
  font-weight: 700;
}

.pk__addr span {
  overflow: hidden;
  color: var(--ink3);
  font-size: 11px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pk__foot .btn {
  flex: none;
  height: 44px;
  padding: 0 18px;
}
</style>
