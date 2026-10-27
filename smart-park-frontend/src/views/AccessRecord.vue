<template>
  <div class="page-card">
    <!-- 多条件筛选 -->
    <el-form :inline="true" :model="query" class="search-bar">
      <el-form-item label="类型">
        <el-select v-model="query.passType" placeholder="全部" clearable style="width: 110px">
          <el-option v-for="t in dict.targetTypes" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="编号">
        <el-input v-model="query.targetNo" placeholder="工号或车牌" clearable style="width: 130px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="门岗">
        <el-select v-model="query.gate" placeholder="全部" clearable style="width: 110px">
          <el-option v-for="g in dict.gates" :key="g" :label="g" :value="g" />
        </el-select>
      </el-form-item>
      <el-form-item label="方向">
        <el-select v-model="query.direction" placeholder="全部" clearable style="width: 100px">
          <el-option v-for="d in dict.directions" :key="d.value" :label="d.label" :value="d.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="结果">
        <el-select v-model="query.result" placeholder="全部" clearable style="width: 100px">
          <el-option label="放行" value="放行" />
          <el-option label="拦截" value="拦截" />
        </el-select>
      </el-form-item>
      <el-form-item label="通行时间">
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 340px"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-toolbar">
      <span></span>
      <el-button type="primary" @click="openSimulateDialog">模拟通行</el-button>
    </div>

    <!-- 通行流水 -->
    <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" style="margin-bottom: 12px">
      <el-button link type="primary" @click="loadData">重试</el-button>
    </el-alert>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="passType" label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.passType === 'PERSON' ? 'primary' : 'warning'">
            {{ row.passType === 'PERSON' ? '人员' : '车辆' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetNo" label="编号" width="120" />
      <el-table-column prop="targetName" label="名称" width="110" />
      <el-table-column prop="gate" label="门岗" width="90" />
      <el-table-column prop="direction" label="方向" width="80">
        <template #default="{ row }">
          {{ row.direction === 'IN' ? '进场' : '离场' }}
        </template>
      </el-table-column>
      <el-table-column prop="passTime" label="通行时间" width="165" />
      <el-table-column prop="result" label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.result === '放行' ? 'success' : 'danger'">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="拦截原因">
        <template #default="{ row }">{{ row.reason || '-' }}</template>
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

    <!-- 模拟通行弹窗 -->
    <el-dialog v-model="simulateVisible" title="模拟通行" width="480px">
      <el-alert type="info" :closable="false" style="margin-bottom: 16px"
        title="模拟终端上报一次通行，服务端将校验白名单与权限后返回放行/拦截结果" />
      <el-form ref="simulateFormRef" :model="simulateForm" :rules="simulateRules" label-width="90px">
        <el-form-item label="类型" prop="passType">
          <el-select v-model="simulateForm.passType" style="width: 100%">
            <el-option v-for="t in dict.targetTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="编号" prop="targetNo">
          <el-input v-model="simulateForm.targetNo" placeholder="工号或车牌号" />
        </el-form-item>
        <el-form-item label="门岗" prop="gate">
          <el-select v-model="simulateForm.gate" style="width: 100%">
            <el-option v-for="g in dict.gates" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="方向" prop="direction">
          <el-radio-group v-model="simulateForm.direction">
            <el-radio value="IN">进场</el-radio>
            <el-radio value="OUT">离场</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="simulateVisible = false">取消</el-button>
        <el-button type="primary" :loading="simulating" @click="handleSimulate">上报</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { pageAccessRecords, simulateAccess } from '../api/access'
import { useDictStore } from '../stores/dict'

const dict = useDictStore()

const query = reactive({ passType: '', targetNo: '', gate: '', direction: '', result: '' })
const timeRange = ref(null)
const page = reactive({ pageNum: 1, pageSize: 10 })
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const loadError = ref('')

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const params = { ...query, ...page }
    if (timeRange.value && timeRange.value.length === 2) {
      params.startTime = timeRange.value[0]
      params.endTime = timeRange.value[1]
    }
    const data = await pageAccessRecords(params)
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
  query.passType = ''
  query.targetNo = ''
  query.gate = ''
  query.direction = ''
  query.result = ''
  timeRange.value = null
  handleSearch()
}

function handleSizeChange() {
  page.pageNum = 1
  loadData()
}

// ---------- 模拟通行 ----------
const simulateVisible = ref(false)
const simulating = ref(false)
const simulateFormRef = ref(null)
const simulateForm = reactive({ passType: 'PERSON', targetNo: '', gate: '', direction: 'IN' })

const simulateRules = {
  passType: [{ required: true, message: '请选择类型', trigger: 'change' }],
  targetNo: [{ required: true, message: '请输入工号或车牌号', trigger: 'blur' }],
  gate: [{ required: true, message: '请选择门岗', trigger: 'change' }],
  direction: [{ required: true, message: '请选择方向', trigger: 'change' }]
}

function openSimulateDialog() {
  simulateForm.passType = 'PERSON'
  simulateForm.targetNo = ''
  simulateForm.gate = ''
  simulateForm.direction = 'IN'
  simulateVisible.value = true
  if (simulateFormRef.value) simulateFormRef.value.clearValidate()
}

async function handleSimulate() {
  const valid = await simulateFormRef.value.validate().catch(() => false)
  if (!valid) return
  simulating.value = true
  try {
    const record = await simulateAccess(simulateForm)
    simulateVisible.value = false
    const directionText = record.direction === 'IN' ? '进场' : '离场'
    if (record.result === '放行') {
      ElMessageBox.alert(
        `${record.targetName || record.targetNo} 在「${record.gate}」${directionText}，已放行`,
        '通行结果：放行',
        { type: 'success', confirmButtonText: '知道了' }
      )
    } else {
      ElMessageBox.alert(
        `${record.targetName || record.targetNo} 在「${record.gate}」${directionText}被拦截。原因：${record.reason || '无'}`,
        '通行结果：拦截',
        { type: 'error', confirmButtonText: '知道了' }
      )
    }
    loadData()
  } finally {
    simulating.value = false
  }
}

onMounted(loadData)
</script>
