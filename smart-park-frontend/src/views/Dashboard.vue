<template>
  <div>
    <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" style="margin-bottom: 16px">
      <el-button link type="primary" @click="loadAll">重试</el-button>
    </el-alert>

    <!-- 统计卡片 -->
    <el-row :gutter="16">
      <el-col :span="4" v-for="card in cards" :key="card.label">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="14">
        <div class="page-card">
          <h3 class="chart-title">近 7 天通行趋势</h3>
          <Chart :option="trendOption" height="320px" />
        </div>
      </el-col>
      <el-col :span="10">
        <div class="page-card">
          <h3 class="chart-title">物资状态分布</h3>
          <Chart :option="pieOption" height="320px" />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import Chart from '../components/Chart.vue'
import { getDashboardSummary, getAccessTrend, getMaterialStatus } from '../api/dashboard'

// 统计卡片数据（接口字段：todayAccessCount / visitorOnSite / materialOut / materialOverdue / unhandledAlarm）
const summary = ref({})
const cards = computed(() => [
  { label: '今日通行', value: summary.value.todayAccessCount ?? '-', color: '#409EFF' },
  { label: '在场访客', value: summary.value.visitorOnSite ?? '-', color: '#67C23A' },
  { label: '在借物资', value: summary.value.materialOut ?? '-', color: '#E6A23C' },
  { label: '逾期物资', value: summary.value.materialOverdue ?? '-', color: '#F56C6C' },
  { label: '未处理告警', value: summary.value.unhandledAlarm ?? '-', color: '#F56C6C' }
])

// 近 7 天通行趋势折线图（接口字段：dates / inCounts / outCounts）
const trend = ref({ dates: [], inCounts: [], outCounts: [] })
const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['进场', '离场'] },
  grid: { left: 40, right: 20, top: 40, bottom: 30 },
  xAxis: { type: 'category', data: trend.value.dates },
  yAxis: { type: 'value' },
  series: [
    { name: '进场', type: 'line', smooth: true, data: trend.value.inCounts, areaStyle: { opacity: 0.15 } },
    { name: '离场', type: 'line', smooth: true, data: trend.value.outCounts, areaStyle: { opacity: 0.15 } }
  ]
}))

// 物资状态饼图（接口字段：[{ status, count }]）
const materialStatus = ref([])
const pieOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c}（{d}%）' },
  legend: { bottom: 0 },
  series: [
    {
      type: 'pie',
      radius: ['40%', '70%'],
      center: ['50%', '45%'],
      label: { formatter: '{b}: {c}' },
      data: materialStatus.value.map((item) => ({ name: item.status, value: item.count }))
    }
  ]
}))

const loading = ref(false)
const loadError = ref('')

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [summaryData, trendData, materialStatusData] = await Promise.all([
      getDashboardSummary(),
      getAccessTrend(),
      getMaterialStatus()
    ])
    summary.value = summaryData || {}
    trend.value = trendData || { dates: [], inCounts: [], outCounts: [] }
    materialStatus.value = materialStatusData || []
  } catch {
    // 三个接口任一失败即整体失败；具体文案由拦截器统一提示
    loadError.value = '看板数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.stat-card {
  text-align: center;
}

.stat-label {
  color: #909399;
  font-size: 14px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  margin-top: 8px;
}

.chart-title {
  font-size: 15px;
  color: #303133;
  margin-bottom: 12px;
}
</style>
