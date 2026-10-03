<template>
  <div class="app-container">
    <el-row :gutter="10">
      <el-col :span="8">
        <el-card style="height: calc(100vh - 125px)">
          <template #header>
            <Collection style="width: 1em; height: 1em; vertical-align: middle;" /> <span style="vertical-align: middle;">缓存列表</span>
            <el-button
              style="float: right; padding: 3px 0"
              link
              type="primary"
              icon="Refresh"
              @click="refreshCacheNames()"
            ></el-button>
          </template>
          <el-table
            v-loading="loading"
            :data="cacheNames"
            :height="tableHeight"
            highlight-current-row
            @row-click="getCacheKeys"
            style="width: 100%"
          >
            <el-table-column
              label="序号"
              width="60"
              type="index"
            ></el-table-column>

            <el-table-column
              label="缓存名称"
              align="center"
              prop="cacheName"
              :show-overflow-tooltip="true"
              :formatter="nameFormatter"
            ></el-table-column>

            <el-table-column
              label="备注"
              align="center"
              prop="remark"
              :show-overflow-tooltip="true"
            />
            <el-table-column
              label="操作"
              width="60"
              align="center"
              class-name="small-padding fixed-width"
            >
              <template #default="scope">
                <el-button
                  link
                  type="primary"
                  icon="Delete"
                  @click="handleClearCacheName(scope.row)"
                ></el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card style="height: calc(100vh - 125px)">
          <template #header>
            <Key style="width: 1em; height: 1em; vertical-align: middle;" /> <span style="vertical-align: middle;">键名列表</span>
            <el-button
              style="float: right; padding: 3px 0"
              link
              type="primary"
              icon="Refresh"
              @click="refreshCacheKeys()"
            ></el-button>
          </template>
          <el-table
            v-loading="subLoading"
            :data="cacheKeys"
            :height="tableHeight"
            highlight-current-row
            @row-click="handleCacheValue"
            style="width: 100%"
          >
            <el-table-column
              label="序号"
              width="60"
              type="index"
            ></el-table-column>
            <el-table-column
              label="缓存键名"
              align="center"
              :show-overflow-tooltip="true"
              :formatter="keyFormatter"
            >
            </el-table-column>
            <el-table-column
              label="操作"
              width="60"
              align="center"
              class-name="small-padding fixed-width"
            >
              <template #default="scope">
                <el-button
                  link
                  type="primary"
                  icon="Delete"
                  @click="handleClearCacheKey(scope.row)"
                ></el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card :bordered="false" style="height: calc(100vh - 125px)">
          <template #header>
            <Document style="width: 1em; height: 1em; vertical-align: middle;" /> <span style="vertical-align: middle;">缓存内容</span>
            <el-button
              style="float: right; padding: 3px 0"
              link
              type="primary"
              icon="Refresh"
              @click="handleClearCacheAll()"
              >清理全部</el-button
            >
          </template>
          <el-form :model="cacheForm">
            <el-row :gutter="32">
              <el-col :offset="1" :span="22">
                <el-form-item label="缓存名称:" prop="cacheName">
                  <el-input v-model="cacheForm.cacheName" :readOnly="true" />
                </el-form-item>
              </el-col>
              <el-col :offset="1" :span="22">
                <el-form-item label="缓存键名:" prop="cacheKey">
                  <el-input v-model="cacheForm.cacheKey" :readOnly="true" />
                </el-form-item>
              </el-col>
              <el-col :offset="1" :span="22">
                <el-form-item label="缓存内容:" prop="cacheValue">
                  <el-input
                    v-model="cacheForm.cacheValue"
                    type="textarea"
                    :rows="8"
                    :readOnly="true"
                  />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup name="CacheList">
import { useRequestSequence } from '@/utils/requestSequence'
const namesRequest = useRequestSequence()
const keysRequest = useRequestSequence()
const valueRequest = useRequestSequence()
import { listCacheName, listCacheKey, getCacheValue, clearCacheName, clearCacheKey, clearCacheAll } from "@/api/monitor/cache"

const { proxy } = getCurrentInstance()

const cacheNames = ref([])
const cacheKeys = ref([])
const cacheForm = ref({})
const loading = ref(true)
const subLoading = ref(false)
const nowCacheName = ref("")
const tableHeight = ref(window.innerHeight - 200)

/** 查询缓存名称列表 */
async function getCacheNames() {
  const isCurrent = namesRequest.begin()
  loading.value = true
  try {
    const response = await listCacheName({ isCurrentRequest: isCurrent })
    if (!isCurrent()) return
    cacheNames.value = response.data
  } catch {
    // The HTTP client reports current failures; superseded requests cannot change this view.
  } finally {
    if (isCurrent()) loading.value = false
  }
}

/** 刷新缓存名称列表 */
function refreshCacheNames() {
  getCacheNames()
  proxy.$modal.msgSuccess("刷新缓存列表成功")
}

/** 清理指定名称缓存 */
function handleClearCacheName(row) {
  clearCacheName(row.cacheName).then(() => {
    proxy.$modal.msgSuccess("清理缓存名称[" + row.cacheName + "]成功")
    getCacheKeys()
  })
}

/** 查询缓存键名列表 */
async function getCacheKeys(row) {
  const isCurrent = keysRequest.begin()
  const cacheName = row !== undefined ? row.cacheName : nowCacheName.value
  valueRequest.invalidate()
  cacheKeys.value = []
  cacheForm.value = {}
  nowCacheName.value = cacheName
  if (!cacheName) { subLoading.value = false; return }
  subLoading.value = true
  try {
    const response = await listCacheKey(cacheName, { isCurrentRequest: isCurrent })
    if (!isCurrent()) return
    cacheKeys.value = response.data
  } catch {
    // The HTTP client reports current failures; superseded requests cannot change this view.
  } finally {
    if (isCurrent()) subLoading.value = false
  }
}

/** 刷新缓存键名列表 */
function refreshCacheKeys() {
  getCacheKeys()
  proxy.$modal.msgSuccess("刷新键名列表成功")
}

/** 清理指定键名缓存 */
function handleClearCacheKey(cacheKey) {
  clearCacheKey(cacheKey).then(() => {
    proxy.$modal.msgSuccess("清理缓存键名[" + cacheKey + "]成功")
    getCacheKeys()
  })
}

/** 列表前缀去除 */
function nameFormatter(row) {
  return row.cacheName.replace(":", "")
}

/** 键名前缀去除 */
function keyFormatter(cacheKey) {
  return cacheKey.replace(nowCacheName.value, "")
}

/** 查询缓存内容详细 */
async function handleCacheValue(cacheKey) {
  const isCurrent = valueRequest.begin()
  const cacheName = nowCacheName.value
  cacheForm.value = {}
  try {
    const response = await getCacheValue(cacheName, cacheKey, { isCurrentRequest: isCurrent })
    if (!isCurrent()) return
    cacheForm.value = response.data
  } catch {
    // The HTTP client reports current failures; superseded requests cannot change this view.
  }
}

/** 清理全部缓存 */
function handleClearCacheAll() {
  keysRequest.invalidate()
  valueRequest.invalidate()
  subLoading.value = false
  cacheKeys.value = []
  cacheForm.value = {}
  clearCacheAll().then(() => {
    proxy.$modal.msgSuccess("清理全部缓存成功")
  })
}

getCacheNames()
</script>
