<template>
  <div class="page-card">
    <!-- 条件搜索 -->
    <el-form :inline="true" :model="query" class="search-bar">
      <el-form-item label="物资名称">
        <el-input v-model="query.materialName" placeholder="物资名称" clearable style="width: 140px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="query.type" placeholder="全部" clearable style="width: 110px">
          <!-- 裁决 15：后端已移除 RETURN 死枚举，出入库类型仅保留 IN / OUT -->
          <el-option label="入库" value="IN" />
          <el-option label="出库" value="OUT" />
        </el-select>
      </el-form-item>
      <el-form-item label="审批状态">
        <el-select v-model="query.approveStatus" placeholder="全部" clearable style="width: 120px">
          <el-option label="免审" value="免审" />
          <el-option label="待审批" value="待审批" />
          <el-option label="已批准" value="已批准" />
          <el-option label="已驳回" value="已驳回" />
        </el-select>
      </el-form-item>
      <el-form-item label="领用人">
        <el-input v-model="query.borrower" placeholder="领用人" clearable style="width: 120px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-toolbar">
      <span></span>
      <div>
        <el-button type="success" @click="openInDialog">入库</el-button>
        <el-button type="primary" @click="openOutDialog">申领出库</el-button>
      </div>
    </div>

    <!-- 出入库流水 -->
    <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" style="margin-bottom: 12px">
      <el-button link type="primary" @click="loadData">重试</el-button>
    </el-alert>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="materialCode" label="物资编码" width="120" />
      <el-table-column prop="materialName" label="物资名称" width="130" />
      <el-table-column prop="type" label="类型" width="80">
        <template #default="{ row }">
          <el-tag :type="typeTagType(row.type)">{{ typeText(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="borrower" label="领用人" width="90">
        <template #default="{ row }">{{ row.borrower || '-' }}</template>
      </el-table-column>
      <el-table-column prop="dept" label="领用部门" width="100">
        <template #default="{ row }">{{ row.dept || '-' }}</template>
      </el-table-column>
      <el-table-column prop="approveStatus" label="审批状态" width="95">
        <template #default="{ row }">
          <el-tag :type="approveTagType(row.approveStatus)">{{ row.approveStatus }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="expectReturnTime" label="预计归还" width="160">
        <template #default="{ row }">{{ row.expectReturnTime || '-' }}</template>
      </el-table-column>
      <el-table-column prop="outTime" label="出库时间" width="160">
        <template #default="{ row }">{{ row.outTime || '-' }}</template>
      </el-table-column>
      <el-table-column prop="returnTime" label="归还时间" width="160">
        <template #default="{ row }">{{ row.returnTime || '-' }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="申请/入库时间" width="160" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <template v-if="row.approveStatus === '待审批'">
            <el-button v-permission="['ADMIN']" link type="success" @click="handleApprove(row, true)">批准</el-button>
            <el-button v-permission="['ADMIN']" link type="danger" @click="handleApprove(row, false)">驳回</el-button>
          </template>
          <el-button
            v-if="row.type === 'OUT' && !row.returnTime && (row.approveStatus === '免审' || row.approveStatus === '已批准')"
            link
            type="primary"
            @click="handleReturn(row)"
          >归还</el-button>
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

    <!-- 入库弹窗 -->
    <el-dialog v-model="inVisible" title="物资入库" width="480px">
      <el-form ref="inFormRef" :model="inForm" :rules="inRules" label-width="90px">
        <el-form-item label="物资" prop="materialCode">
          <el-select v-model="inForm.materialCode" placeholder="选择入库物资" style="width: 100%" filterable>
            <el-option v-for="m in allMaterials" :key="m.code" :label="`${m.name}（${m.code}）`" :value="m.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="inForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleStockIn">入库</el-button>
      </template>
    </el-dialog>

    <!-- 申领出库弹窗 -->
    <el-dialog v-model="outVisible" title="申领出库" width="520px">
      <el-form ref="outFormRef" :model="outForm" :rules="outRules" label-width="100px">
        <el-form-item label="物资" prop="materialCode">
          <el-select v-model="outForm.materialCode" placeholder="仅可选择「在库」物资" style="width: 100%" filterable>
            <el-option
              v-for="m in inStockMaterials"
              :key="m.code"
              :label="`${m.name}（${m.code}）${m.valuable ? ' - 贵重需审批' : ''}`"
              :value="m.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="领用人" prop="borrower">
          <el-input v-model="outForm.borrower" />
        </el-form-item>
        <el-form-item label="领用部门" prop="dept">
          <el-input v-model="outForm.dept" />
        </el-form-item>
        <el-form-item label="预计归还" prop="expectReturnTime">
          <el-date-picker
            v-model="outForm.expectReturnTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="预计归还时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="outForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="outVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleStockOut">提交申领</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { pageStockRecords, stockIn, stockOut, approveStockRecord, returnStockRecord, pageMaterials } from '../api/stock'

const query = reactive({ materialName: '', type: '', approveStatus: '', borrower: '' })
const page = reactive({ pageNum: 1, pageSize: 10 })
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const loadError = ref('')

// 裁决 15：出入库类型仅 IN / OUT（后端已移除 RETURN 死枚举）
function typeText(type) {
  if (type === 'IN') return '入库'
  if (type === 'OUT') return '出库'
  return '-'
}
function typeTagType(type) {
  return type === 'IN' ? 'success' : 'warning'
}
function approveTagType(status) {
  if (status === '待审批') return 'warning'
  if (status === '已批准') return 'success'
  if (status === '已驳回') return 'danger'
  return 'info'
}

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await pageStockRecords({ ...query, ...page })
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
  query.materialName = ''
  query.type = ''
  query.approveStatus = ''
  query.borrower = ''
  handleSearch()
}

function handleSizeChange() {
  page.pageNum = 1
  loadData()
}

// ---------- 物资下拉数据 ----------
const allMaterials = ref([])
const inStockMaterials = ref([])

async function loadMaterialOptions() {
  // 入库下拉：全部物资；出库下拉：仅「在库」物资
  const [all, inStock] = await Promise.all([
    pageMaterials({ pageNum: 1, pageSize: 500 }),
    pageMaterials({ pageNum: 1, pageSize: 500, status: '在库' })
  ])
  allMaterials.value = all.list
  inStockMaterials.value = inStock.list
}

// ---------- 入库弹窗 ----------
const inVisible = ref(false)
const inFormRef = ref(null)
const inForm = reactive({ materialCode: '', remark: '' })
const inRules = {
  materialCode: [{ required: true, message: '请选择入库物资', trigger: 'change' }]
}

async function openInDialog() {
  inForm.materialCode = ''
  inForm.remark = ''
  inVisible.value = true
  if (inFormRef.value) inFormRef.value.clearValidate()
  await loadMaterialOptions()
}

// ---------- 申领出库弹窗 ----------
const outVisible = ref(false)
const outFormRef = ref(null)
const outForm = reactive({ materialCode: '', borrower: '', dept: '', expectReturnTime: '', remark: '' })
const outRules = {
  materialCode: [{ required: true, message: '请选择物资', trigger: 'change' }],
  borrower: [{ required: true, message: '请输入领用人', trigger: 'blur' }],
  dept: [{ required: true, message: '请输入领用部门', trigger: 'blur' }],
  expectReturnTime: [{ required: true, message: '请选择预计归还时间', trigger: 'change' }]
}

async function openOutDialog() {
  outForm.materialCode = ''
  outForm.borrower = ''
  outForm.dept = ''
  outForm.expectReturnTime = ''
  outForm.remark = ''
  outVisible.value = true
  if (outFormRef.value) outFormRef.value.clearValidate()
  await loadMaterialOptions()
}

const submitting = ref(false)

async function handleStockIn() {
  const valid = await inFormRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await stockIn(inForm)
    ElMessage.success('入库成功')
    inVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function handleStockOut() {
  const valid = await outFormRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const record = await stockOut(outForm)
    if (record && record.approveStatus === '待审批') {
      ElMessage.warning('贵重物资，已提交审批，待批准后出库')
    } else {
      ElMessage.success('出库成功')
    }
    outVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

// ---------- 审批 / 归还 ----------
async function handleApprove(row, approved) {
  const action = approved ? '批准' : '驳回'
  const ok = await ElMessageBox.confirm(`确认${action}「${row.materialName}」的出库申请吗？`, '审批确认', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await approveStockRecord(row.id, approved)
  ElMessage.success(approved ? '已批准，物资出库' : '已驳回')
  loadData()
}

async function handleReturn(row) {
  const ok = await ElMessageBox.confirm(`确认归还「${row.materialName}」吗？`, '归还确认', { type: 'info' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await returnStockRecord(row.id)
  ElMessage.success('归还成功，物资已回切在库')
  loadData()
}

onMounted(loadData)
</script>
