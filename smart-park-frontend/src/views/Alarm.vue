<template>
  <div class="page-card">
    <!-- 条件搜索 -->
    <el-form :inline="true" :model="query" class="search-bar">
      <el-form-item label="告警类型">
        <el-select v-model="query.type" placeholder="全部" clearable style="width: 130px">
          <el-option v-for="t in dict.alarmTypes" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="级别">
        <el-select v-model="query.level" placeholder="全部" clearable style="width: 110px">
          <el-option v-for="l in dict.alarmLevels" :key="l" :label="l" :value="l" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 110px">
          <el-option label="未处理" value="未处理" />
          <el-option label="已处理" value="已处理" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 告警分页表格 -->
    <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" style="margin-bottom: 12px">
      <el-button link type="primary" @click="loadData">重试</el-button>
    </el-alert>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="type" label="类型" width="110" />
      <el-table-column prop="title" label="标题" width="200" />
      <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
      <el-table-column prop="level" label="级别" width="90">
        <template #default="{ row }">
          <el-tag :type="levelTagType(row.level)">{{ row.level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === '未处理' ? 'danger' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="产生时间" width="165" />
      <el-table-column prop="handleTime" label="处理时间" width="165">
        <template #default="{ row }">{{ row.handleTime || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === '未处理'" link type="primary" @click="handleAlarmClick(row)">处理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <el-pagination
        v-model:current-page="page.pageNum"
        v-model:page-size="page.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, prev, pager, next, sizes"
        @current-change="loadData"
        @size-change="handleSizeChange"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { pageAlarms, handleAlarm } from '../api/alarm'
import { useDictStore } from '../stores/dict'

const dict = useDictStore()

const query = reactive({ type: '', level: '', status: '' })
const page = reactive({ pageNum: 1, pageSize: 10 })
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const loadError = ref('')

// 级别 tag 着色：提示蓝 / 警告橙 / 严重红
function levelTagType(level) {
  if (level === '严重') return 'danger'
  if (level === '警告') return 'warning'
  return 'primary'
}

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await pageAlarms({ ...query, ...page })
    tableData.value = data.list || []
    total.value = data.total || 0
  } catch {
    loadError.value = '数据加载失败'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.pageNum = 1
  loadData()
}

function handleReset() {
  query.type = ''
  query.level = ''
  query.status = ''
  handleSearch()
}

function handleSizeChange() {
  page.pageNum = 1
  loadData()
}

async function handleAlarmClick(row) {
  const ok = await ElMessageBox.confirm(`确认将告警「${row.title}」标记为已处理吗？`, '处理告警', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await handleAlarm(row.id)
  ElMessage.success('处理成功')
  loadData()
}

onMounted(loadData)
</script>
