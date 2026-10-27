<template>
  <div ref="el" :style="{ height, width: '100%' }"></div>
</template>

<script setup>
// 通用 ECharts 容器组件：传入 option 自动渲染，窗口缩放自动 resize
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// 按需注册：仅看板用到的折线/饼图 + 三个组件，替代 echarts 全量引入（原 Dashboard chunk 1.04MB）
echarts.use([LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '320px' }
})

const el = ref(null)
let chart = null

const render = () => {
  if (chart && props.option) {
    chart.setOption(props.option, true)
  }
}

const resize = () => {
  if (chart) chart.resize()
}

// 容器尺寸变化（窗口缩放 + 侧栏折叠等）都触发 resize
let observer = null

onMounted(() => {
  chart = echarts.init(el.value)
  render()
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => resize())
    observer.observe(el.value)
  } else {
    window.addEventListener('resize', resize)
  }
})

onBeforeUnmount(() => {
  if (observer) {
    observer.disconnect()
    observer = null
  } else {
    window.removeEventListener('resize', resize)
  }
  if (chart) chart.dispose()
})

watch(() => props.option, render, { deep: true })
</script>
