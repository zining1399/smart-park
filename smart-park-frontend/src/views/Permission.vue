<template>
  <div class="page-card">
    <!-- 条件搜索 -->
    <el-form :inline="true" :model="query" class="search-bar">
      <el-form-item label="目标类型">
        <el-select v-model="query.targetType" placeholder="全部" clearable style="width: 110px">
          <el-option v-for="t in dict.targetTypes" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="目标编号">
        <el-input v-model="query.targetNo" placeholder="工号或车牌号" clearable style="width: 150px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="区域">
        <el-select v-model="query.area" placeholder="全部" clearable style="width: 120px">
          <el-option v-for="a in dict.areas" :key="a" :label="a" :value="a" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 110px">
          <el-option label="生效" value="生效" />
          <el-option label="临期" value="临期" />
          <el-option label="失效" value="失效" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-toolbar">
      <span></span>
      <!-- 权限口径（后端冻结，见 api-contract §七）：POST/PUT /permissions 对 ADMIN+GATE 开放，DELETE 仅 ADMIN -->
      <el-button v-permission="['ADMIN', 'GATE']" type="primary" @click="openDialog()">新增权限</el-button>
    </div>

    <!-- 分页表格 -->
    <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" style="margin-bottom: 12px">
      <el-button link type="primary" @click="loadData">重试</el-button>
    </el-alert>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="targetType" label="目标类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.targetType === 'PERSON' ? 'primary' : 'warning'">
            {{ row.targetType === 'PERSON' ? '人员' : '车辆' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetNo" label="目标编号" width="130" />
      <el-table-column prop="targetName" label="目标名称" width="120" />
      <el-table-column prop="area" label="区域" width="100" />
      <el-table-column prop="startTime" label="生效时间" width="165" />
      <el-table-column prop="endTime" label="失效时间" width="165" />
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <!-- 权限口径：PUT 对门岗放开（访客临时延期需现场改有效期）；DELETE 收归管理员（撤销风险高，到期自动失效） -->
          <el-button v-permission="['ADMIN', 'GATE']" link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button v-permission="['ADMIN']" link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑权限' : '新增权限'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="目标类型" prop="targetType">
          <el-select v-model="form.targetType" style="width: 100%">
            <el-option v-for="t in dict.targetTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标编号" prop="targetNo">
          <el-input v-model="form.targetNo" placeholder="工号或车牌号" />
        </el-form-item>
        <el-form-item label="目标名称">
          <el-input v-model="form.targetName" placeholder="姓名或车牌（冗余展示用，可空）" />
        </el-form-item>
        <el-form-item label="区域" prop="area">
          <el-select v-model="form.area" style="width: 100%">
            <el-option v-for="a in dict.areas" :key="a" :label="a" :value="a" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效时间" prop="startTime">
          <el-date-picker
            v-model="form.startTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择生效时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="失效时间" prop="endTime">
          <el-date-picker
            v-model="form.endTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择失效时间"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { pagePermissions, createPermission, updatePermission, deletePermission } from '../api/access'
import { useDictStore } from '../stores/dict'

const dict = useDictStore()

const query = reactive({ targetType: '', targetNo: '', area: '', status: '' })
const page = reactive({ pageNum: 1, pageSize: 10 })
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const loadError = ref('')

// 状态 tag 着色：生效绿 / 临期橙 / 失效灰
function statusTagType(status) {
  if (status === '生效') return 'success'
  if (status === '临期') return 'warning'
  return 'info'
}

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await pagePermissions({ ...query, ...page })
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
  query.targetType = ''
  query.targetNo = ''
  query.area = ''
  query.status = ''
  handleSearch()
}

function handleSizeChange() {
  page.pageNum = 1
  loadData()
}

// ---------- 新增/编辑弹窗 ----------
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const emptyForm = {
  id: null,
  targetType: 'PERSON',
  targetNo: '',
  targetName: '',
  area: '',
  startTime: '',
  endTime: ''
}
const form = reactive({ ...emptyForm })

const rules = computed(() => ({
  targetType: [{ required: true, message: '请选择目标类型', trigger: 'change' }],
  targetNo: [{ required: true, message: '请输入目标编号', trigger: 'blur' }],
  area: [{ required: true, message: '请选择区域', trigger: 'change' }],
  startTime: [{ required: true, message: '请选择生效时间', trigger: 'change' }],
  endTime: [
    { required: true, message: '请选择失效时间', trigger: 'change' },
    {
      validator: (rule, value, callback) => {
        if (value && form.startTime && value <= form.startTime) {
          callback(new Error('失效时间必须晚于生效时间'))
        } else {
          callback()
        }
      },
      trigger: 'change'
    }
  ]
}))

function openDialog(row) {
  Object.assign(form, emptyForm, row ? { ...row } : {})
  dialogVisible.value = true
  if (formRef.value) formRef.value.clearValidate()
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (form.id) {
      await updatePermission(form.id, form)
      ElMessage.success('修改成功')
    } else {
      await createPermission(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row) {
  const ok = await ElMessageBox.confirm(`确认删除该权限（${row.targetNo} / ${row.area}）吗？`, '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await deletePermission(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>
