<template>
  <div class="page-card">
    <!-- 条件搜索 -->
    <el-form :inline="true" :model="query" class="search-bar">
      <el-form-item label="访客姓名">
        <el-input v-model="query.visitorName" placeholder="访客姓名" clearable style="width: 140px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="被访人">
        <el-input v-model="query.hostName" placeholder="被访人" clearable style="width: 140px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px">
          <el-option label="已预约" value="已预约" />
          <el-option label="在场" value="在场" />
          <el-option label="已签销" value="已签销" />
          <el-option label="滞留预警" value="滞留预警" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-toolbar">
      <span></span>
      <el-button type="primary" @click="openDialog()">预约登记</el-button>
    </div>

    <!-- 访客分页表格（身份证为后端脱敏结果，直接展示） -->
    <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" style="margin-bottom: 12px">
      <el-button link type="primary" @click="loadData">重试</el-button>
    </el-alert>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="visitorName" label="访客姓名" width="100" />
      <!-- 终稿 B05：列表返回的是服务端掩码字段 idCardMask，前端不做任何脱敏处理 -->
      <el-table-column prop="idCardMask" label="身份证" width="170">
        <template #default="{ row }">{{ row.idCardMask || '-' }}</template>
      </el-table-column>
      <!-- 裁决 17：访客手机号同属加密范围，列表只返回掩码字段，绝不渲染密文 phone -->
      <el-table-column prop="phoneMask" label="手机号" width="130">
        <template #default="{ row }">{{ row.phoneMask || row.phone_mask || '-' }}</template>
      </el-table-column>
      <el-table-column prop="hostName" label="被访人" width="90" />
      <el-table-column prop="hostDept" label="被访部门" width="110" />
      <el-table-column prop="area" label="访问区域" width="90" />
      <el-table-column prop="appointTime" label="预约时间" width="160" />
      <el-table-column prop="expireTime" label="失效时间" width="160" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === '已预约'" link type="success" @click="handleCheckIn(row)">进场登记</el-button>
          <el-button v-if="row.status === '在场' || row.status === '滞留预警'" link type="warning" @click="handleCheckOut(row)">离场签销</el-button>
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <!-- 预约登记/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑访客' : '预约登记'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="访客姓名" prop="visitorName">
              <el-input v-model="form.visitorName" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="form.phone" />
            </el-form-item>
          </el-col>
        </el-row>
        <!-- 终稿 B05 三层防护之①：编辑模式禁用身份证输入（列表值为掩码，回写会覆盖真实身份证） -->
        <el-form-item label="身份证号" prop="idCard">
          <el-input
            v-model="form.idCard"
            :disabled="!!form.id"
            :placeholder="
              form.id ? '身份证不支持在线修改，如需变更请联系管理员' : '18 位身份证号（提交后由服务端加密存储）'
            "
          />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="访客车牌">
              <el-input v-model="form.plateNo" placeholder="可空" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="访问区域" prop="area">
              <el-select v-model="form.area" style="width: 100%">
                <el-option v-for="a in dict.areas" :key="a" :label="a" :value="a" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="被访人" prop="hostName">
              <el-input v-model="form.hostName" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="被访部门" prop="hostDept">
              <el-input v-model="form.hostDept" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="来访事由" prop="reason">
          <el-input v-model="form.reason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="预约时间" prop="appointTime">
          <el-date-picker
            v-model="form.appointTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="预约到访时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="失效时间" prop="expireTime">
          <el-date-picker
            v-model="form.expireTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="授权失效时间"
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
import { pageVisitors, createVisitor, updateVisitor, deleteVisitor, checkInVisitor, checkOutVisitor } from '../api/visitor'
import { useDictStore } from '../stores/dict'
import { PHONE_RE, idCardRule } from '../utils/validators'

const dict = useDictStore()

const query = reactive({ visitorName: '', hostName: '', status: '' })
const page = reactive({ pageNum: 1, pageSize: 10 })
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const loadError = ref('')

// 状态 tag 着色：已预约蓝 / 在场绿 / 已签销灰 / 滞留预警红
function statusTagType(status) {
  if (status === '已预约') return 'primary'
  if (status === '在场') return 'success'
  if (status === '滞留预警') return 'danger'
  return 'info'
}

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await pageVisitors({ ...query, ...page })
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
  query.visitorName = ''
  query.hostName = ''
  query.status = ''
  handleSearch()
}

function handleSizeChange() {
  page.pageNum = 1
  loadData()
}

// ---------- 预约登记/编辑弹窗 ----------
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const emptyForm = {
  id: null,
  visitorName: '',
  idCard: '',
  phone: '',
  plateNo: '',
  hostName: '',
  hostDept: '',
  reason: '',
  area: '',
  appointTime: '',
  expireTime: ''
}
const form = reactive({ ...emptyForm })

// 动态规则：编辑模式下身份证输入框已禁用且值为掩码，不做必填与格式校验
const rules = computed(() => ({
  visitorName: [{ required: true, message: '请输入访客姓名', trigger: 'blur' }],
  idCard: form.id ? [] : [{ required: true, message: '请输入身份证号', trigger: 'blur' }, idCardRule],
  // 手机号规则容忍掩码值：编辑回显的是 138****8001，用户不改则跳过格式校验
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        const v = String(value || '')
        if (!v || v.includes('*') || PHONE_RE.test(v)) {
          callback()
        } else {
          callback(new Error('请输入正确的 11 位手机号'))
        }
      },
      trigger: 'blur'
    }
  ],
  hostName: [{ required: true, message: '请输入被访人', trigger: 'blur' }],
  hostDept: [{ required: true, message: '请输入被访部门', trigger: 'blur' }],
  reason: [{ required: true, message: '请输入来访事由', trigger: 'blur' }],
  area: [{ required: true, message: '请选择访问区域', trigger: 'change' }],
  appointTime: [{ required: true, message: '请选择预约时间', trigger: 'change' }],
  expireTime: [
    { required: true, message: '请选择失效时间', trigger: 'change' },
    {
      validator: (rule, value, callback) => {
        if (value && form.appointTime && value <= form.appointTime) {
          callback(new Error('失效时间必须晚于预约时间'))
        } else {
          callback()
        }
      },
      trigger: 'change'
    }
  ]
}))

function openDialog(row) {
  // 编辑回填用服务端掩码值（idCardMask / phoneMask，明文已不下发）；身份证输入框为禁用态
  Object.assign(
    form,
    emptyForm,
    row ? { ...row, idCard: row.idCardMask || '', phone: row.phoneMask || row.phone_mask || '' } : {}
  )
  dialogVisible.value = true
  if (formRef.value) formRef.value.clearValidate()
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (form.id) {
      // 终稿 B05 三层防护之②：编辑提交剔除 idCard 字段，掩码值绝不回传
      // 同理，phone 若仍是掩码值（未修改）也剔除，避免"改了却没生效"
      const payload = { ...form }
      delete payload.idCard
      if (String(payload.phone || '').includes('*')) delete payload.phone
      await updateVisitor(form.id, payload)
      ElMessage.success('修改成功')
    } else {
      await createVisitor(form)
      ElMessage.success('预约登记成功')
    }
    dialogVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function handleCheckIn(row) {
  const ok = await ElMessageBox.confirm(`确认为访客「${row.visitorName}」办理进场登记吗？`, '提示', { type: 'info' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await checkInVisitor(row.id)
  ElMessage.success('进场登记成功')
  loadData()
}

async function handleCheckOut(row) {
  const ok = await ElMessageBox.confirm(`确认为访客「${row.visitorName}」办理离场签销吗？`, '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await checkOutVisitor(row.id)
  ElMessage.success('离场签销成功')
  loadData()
}

async function handleDelete(row) {
  const ok = await ElMessageBox.confirm(`确认删除访客「${row.visitorName}」吗？`, '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await deleteVisitor(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>
