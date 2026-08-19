<template>
  <div>
    <el-popover
      ref="noticePopover"
      v-model:visible="noticeVisible"
      placement="bottom-end"
      :width="390"
      trigger="manual"
      popper-class="notice-popover"
    >
      <div class="notice-header">
        <div>
          <strong>消息中心</strong>
          <span>{{ totalUnread }} 条未读</span>
        </div>
        <div class="notice-tools">
          <el-tooltip v-if="canManageWorkflowPreference" content="通知偏好" placement="top">
            <el-button circle text icon="Setting" aria-label="通知偏好" @click="openPreference" />
          </el-tooltip>
          <el-tooltip content="刷新消息" placement="top">
            <el-button circle text icon="Refresh" aria-label="刷新消息" :loading="noticeLoading" @click="refreshAll" />
          </el-tooltip>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="notice-tabs">
        <el-tab-pane v-if="canReadWorkflowNotifications" name="workflow">
          <template #label>
            <span>审批通知 <el-badge v-if="workflowUnread" :value="workflowUnread" :max="99" /></span>
          </template>
          <div class="notice-filter">
            <el-segmented v-model="workflowReadStatus" :options="readOptions" size="small" @change="resetWorkflowNotices" />
            <el-button text type="primary" :disabled="workflowUnread === 0" @click="markWorkflowAllRead">全部已读</el-button>
          </div>
          <div v-if="noticeLoading" class="notice-state"><el-icon class="is-loading"><Loading /></el-icon>加载中</div>
          <div v-else-if="workflowNotices.length === 0" class="notice-state"><el-icon><Bell /></el-icon>暂无审批通知</div>
          <div v-else class="notice-list">
            <button
              v-for="item in workflowNotices"
              :key="item.notificationId"
              type="button"
              class="notice-item notice-item--workflow"
              :class="{ 'is-read': item.readStatus === 'READ' }"
              @click="openWorkflowNotice(item)"
            >
              <span class="notice-dot" />
              <span class="notice-copy">
                <strong>{{ item.title }}</strong>
                <span>{{ item.content }}</span>
                <time>{{ formatTime(item.createTime) }}</time>
              </span>
              <el-icon><ArrowRight /></el-icon>
            </button>
            <div v-if="workflowHasMore" class="notice-filter notice-filter--end">
              <el-button text type="primary" :loading="workflowLoadingMore" @click="loadMoreWorkflowNotices">加载更多</el-button>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane name="announcement">
          <template #label>
            <span>公告 <el-badge v-if="announcementUnread" :value="announcementUnread" :max="99" /></span>
          </template>
          <div class="notice-filter notice-filter--end">
            <el-button text type="primary" :disabled="announcementUnread === 0" @click="markAnnouncementAllRead">全部已读</el-button>
          </div>
          <div v-if="noticeLoading" class="notice-state"><el-icon class="is-loading"><Loading /></el-icon>加载中</div>
          <div v-else-if="announcements.length === 0" class="notice-state"><el-icon><Postcard /></el-icon>暂无公告</div>
          <div v-else class="notice-list">
            <button
              v-for="item in announcements"
              :key="item.noticeId"
              type="button"
              class="notice-item"
              :class="{ 'is-read': item.isRead }"
              @click="previewAnnouncement(item)"
            >
              <el-tag size="small" :type="item.noticeType === '1' ? 'warning' : 'success'">
                {{ item.noticeType === '1' ? '通知' : '公告' }}
              </el-tag>
              <span class="notice-copy">
                <strong>{{ item.noticeTitle }}</strong>
                <time>{{ formatTime(item.createTime) }}</time>
              </span>
            </button>
          </div>
        </el-tab-pane>
      </el-tabs>

      <template #reference>
        <div class="right-menu-item hover-effect notice-trigger" @mouseenter="onNoticeEnter" @mouseleave="onNoticeLeave">
          <svg-icon icon-class="bell" />
          <span v-if="totalUnread > 0" class="notice-badge">{{ totalUnread > 99 ? '99+' : totalUnread }}</span>
        </div>
      </template>
    </el-popover>

    <notice-detail-view ref="noticeViewRef" />

    <el-dialog v-model="preferenceVisible" title="审批通知偏好" width="420px" append-to-body>
      <el-form label-position="left" label-width="120px" v-loading="preferenceLoading">
        <el-form-item label="站内通知">
          <el-switch v-model="preference.inboxEnabled" />
        </el-form-item>
        <el-form-item label="邮件通知">
          <el-switch v-model="preference.emailEnabled" />
        </el-form-item>
        <el-form-item label="短信通知">
          <el-switch v-model="preference.smsEnabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="preferenceVisible = false">取消</el-button>
        <el-button type="primary" :loading="preferenceSaving" @click="savePreference">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import NoticeDetailView from './DetailView'
import { listNoticeTop, markNoticeRead, markNoticeReadAll } from '@/api/system/notice'
import {
  getWorkflowNotificationPreference,
  listWorkflowNotifications,
  markAllWorkflowNotificationsRead,
  markWorkflowNotificationRead,
  saveWorkflowNotificationPreference
} from '@/api/workflow/notification'

const router = useRouter()
const { proxy } = getCurrentInstance()
// 收件箱权限决定审批通知页签以及所有审批通知读写请求是否可用。
const canReadWorkflowNotifications = computed(() => proxy.$auth.hasPermi('workflow:notification:list'))
// 偏好接口与个人收件箱共用查询权限；服务端始终按当前登录用户隔离配置，不存在额外偏好权限。
const canManageWorkflowPreference = computed(() => canReadWorkflowNotifications.value)
const noticePopover = ref(null)
const noticeVisible = ref(false)
const noticeLoading = ref(false)
const activeTab = ref(canReadWorkflowNotifications.value ? 'workflow' : 'announcement')
const workflowReadStatus = ref('ALL')
const workflowNotices = ref([])
const workflowUnread = ref(0)
const workflowPageNum = ref(1)
const workflowTotal = ref(0)
const workflowLoadingMore = ref(false)
const workflowHasMore = computed(() => workflowNotices.value.length < workflowTotal.value)
const announcements = ref([])
const announcementUnread = ref(0)
const preferenceVisible = ref(false)
const preferenceLoading = ref(false)
const preferenceSaving = ref(false)
const preference = reactive({ inboxEnabled: true, emailEnabled: true, smsEnabled: false, revision: 0 })
const readOptions = [
  { label: '全部', value: 'ALL' },
  { label: '未读', value: 'UNREAD' },
  { label: '已读', value: 'READ' }
]
let noticeLeaveTimer = null
let refreshSequence = 0

const totalUnread = computed(() => workflowUnread.value + announcementUnread.value)

/**
 * 清空无权访问的审批通知状态，并使已经发出的旧请求结果失效。
 * @returns {void} 审批通知列表和未读数归零。
 */
function clearWorkflowNoticeState() {
  refreshSequence += 1
  workflowNotices.value = []
  workflowUnread.value = 0
  workflowPageNum.value = 1
  workflowTotal.value = 0
}

/**
 * 在当前用户具备审批通知查询权限时查询收件箱，并用序号淘汰旧响应。
 * @returns {Promise<void>} 完成后更新当前过滤列表和真实未读数。
 */
async function loadWorkflowNotices() {
  if (!canReadWorkflowNotifications.value) {
    clearWorkflowNoticeState()
    return
  }
  const sequence = ++refreshSequence
  const response = await listWorkflowNotifications(workflowReadStatus.value, workflowPageNum.value, 30)
  if (sequence !== refreshSequence || !canReadWorkflowNotifications.value) return
  const data = response.data || {}
  workflowNotices.value = workflowPageNum.value === 1
    ? (data.items || [])
    : [...workflowNotices.value, ...(data.items || [])]
  workflowTotal.value = Number(data.total || 0)
  workflowUnread.value = Number(data.unreadCount || 0)
}

/**
 * 切换阅读状态后从第一页重新读取，避免不同筛选条件的记录混在一起。
 * @returns {Promise<void>} 当前筛选条件第一页加载结果。
 */
async function resetWorkflowNotices() {
  workflowPageNum.value = 1
  workflowTotal.value = 0
  await loadWorkflowNotices()
}

/**
 * 追加下一页统一收件箱记录，服务端返回的 total 决定是否继续请求。
 * @returns {Promise<void>} 下一页加载完成后的列表状态。
 */
async function loadMoreWorkflowNotices() {
  if (!workflowHasMore.value || workflowLoadingMore.value) return
  workflowLoadingMore.value = true
  workflowPageNum.value += 1
  try {
    await loadWorkflowNotices()
  } catch (error) {
    workflowPageNum.value -= 1
    throw error
  } finally {
    workflowLoadingMore.value = false
  }
}

/**
 * 查询顶部系统公告及当前用户阅读状态。
 * @returns {Promise<void>} 完成后更新公告列表和未读数。
 */
async function loadAnnouncements() {
  const response = await listNoticeTop()
  announcements.value = response.data || []
  announcementUnread.value = Number(response.unreadCount || 0)
}

/**
 * 刷新系统公告，并仅在有权时并行刷新审批通知。
 * @returns {Promise<void>} 当前用户有权访问的正式 API 完成后解除加载状态。
 */
async function refreshAll() {
  noticeLoading.value = true
  try {
    workflowPageNum.value = 1
    workflowTotal.value = 0
    // 公告对所有已登录用户保持原行为，审批通知请求则按权限动态加入刷新任务。
    const refreshTasks = [loadAnnouncements()]
    if (canReadWorkflowNotifications.value) {
      refreshTasks.push(loadWorkflowNotices())
    } else {
      clearWorkflowNoticeState()
    }
    await Promise.all(refreshTasks)
  } finally {
    noticeLoading.value = false
  }
}

/**
 * 打开审批通知关联的受控流程路由并标记已读。
 * @param {object} item 服务端审批通知投影。
 * @returns {Promise<void>} 无权限时不产生副作用，有权限时写入已读并进入对象授权流程详情。
 */
async function openWorkflowNotice(item) {
  if (!canReadWorkflowNotifications.value) return
  if (item.readStatus !== 'READ') {
    await markWorkflowNotificationRead(item.notificationId)
    item.readStatus = 'READ'
    workflowUnread.value = Math.max(0, workflowUnread.value - 1)
  }
  noticeVisible.value = false
  await router.push(item.routePath)
}

/**
 * 预览系统公告并持久化当前用户阅读状态。
 * @param {object} item 公告列表项。
 * @returns {Promise<void>} 打开公告详情。
 */
async function previewAnnouncement(item) {
  if (!item.isRead) {
    await markNoticeRead(item.noticeId)
    item.isRead = true
    announcementUnread.value = Math.max(0, announcementUnread.value - 1)
  }
  proxy.$refs.noticeViewRef.open(item.noticeId)
}

/**
 * 在当前用户具备审批通知权限时，将全部审批通知标记已读并刷新过滤结果。
 * @returns {Promise<void>} 无权限时不调用审批通知 API，有权限时完成服务端写入和列表刷新。
 */
async function markWorkflowAllRead() {
  if (!canReadWorkflowNotifications.value) return
  await markAllWorkflowNotificationsRead()
  workflowUnread.value = 0
  workflowPageNum.value = 1
  await loadWorkflowNotices()
}

/** @returns {Promise<void>} 将当前顶部公告全部标记已读并刷新列表。 */
async function markAnnouncementAllRead() {
  const ids = announcements.value.map(item => item.noticeId).join(',')
  if (!ids) return
  await markNoticeReadAll(ids)
  announcements.value = announcements.value.map(item => ({ ...item, isRead: true }))
  announcementUnread.value = 0
}

/**
 * 在当前用户具备审批通知查询权限时，从正式 API 加载其个人通知偏好。
 * @returns {Promise<void>} 无权限时不打开弹窗且不调用偏好 API。
 */
async function openPreference() {
  if (!canManageWorkflowPreference.value) {
    preferenceVisible.value = false
    return
  }
  preferenceLoading.value = true
  preferenceVisible.value = true
  try {
    const response = await getWorkflowNotificationPreference()
    Object.assign(preference, response.data || {})
  } finally {
    preferenceLoading.value = false
  }
}

/**
 * 在当前用户具备审批通知查询权限时，以服务端 revision 保存其个人通知偏好。
 * @returns {Promise<void>} 无权限时不调用偏好 API，有权限时保存并刷新消息列表。
 */
async function savePreference() {
  if (!canManageWorkflowPreference.value) {
    preferenceVisible.value = false
    return
  }
  preferenceSaving.value = true
  try {
    const response = await saveWorkflowNotificationPreference({
      inboxEnabled: preference.inboxEnabled,
      emailEnabled: preference.emailEnabled,
      smsEnabled: preference.smsEnabled,
      expectedRevision: Number(preference.revision || 0)
    })
    Object.assign(preference, response.data || {})
    preferenceVisible.value = false
    await refreshAll()
    proxy.$modal.msgSuccess('通知偏好已保存')
  } finally {
    preferenceSaving.value = false
  }
}

/** @returns {void} 打开消息面板并在每次进入时刷新真实状态。 */
function onNoticeEnter() {
  clearTimeout(noticeLeaveTimer)
  noticeVisible.value = true
  refreshAll()
  nextTick(() => {
    const popper = noticePopover.value?.popperRef?.contentRef
    if (!popper || popper._noticeBound) return
    popper._noticeBound = true
    popper.addEventListener('mouseenter', () => clearTimeout(noticeLeaveTimer))
    popper.addEventListener('mouseleave', () => {
      noticeLeaveTimer = setTimeout(() => { noticeVisible.value = false }, 120)
    })
  })
}

/** @returns {void} 延迟关闭面板，允许鼠标从铃铛移动到浮层。 */
function onNoticeLeave() {
  noticeLeaveTimer = setTimeout(() => { noticeVisible.value = false }, 180)
}

/**
 * 将服务端时间格式化为紧凑本地时间。
 * @param {string|Date|null} value 服务端时间值。
 * @returns {string} 可读时间或短横线。
 */
function formatTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(refreshAll)
</script>

<style lang="scss" scoped>
.notice-trigger { position: relative; transform: translateX(-6px); }
.notice-trigger .svg-icon { width: 1.2em; height: 1.2em; vertical-align: -0.2em; }
.notice-badge {
  position: absolute; top: 6px; right: -6px; min-width: 17px; height: 17px; padding: 0 4px;
  border: 2px solid var(--el-bg-color); border-radius: 9px; background: var(--el-color-danger);
  color: #fff; font-size: 10px; line-height: 13px; text-align: center; white-space: nowrap; pointer-events: none;
}
.notice-header { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px 8px; }
.notice-header > div:first-child { display: grid; gap: 2px; }
.notice-header strong { color: var(--el-text-color-primary); font-size: 14px; }
.notice-header span { color: var(--el-text-color-secondary); font-size: 11px; }
.notice-tools { display: flex; gap: 2px; }
.notice-filter { display: flex; min-height: 34px; align-items: center; justify-content: space-between; padding: 0 10px 8px; }
.notice-filter--end { justify-content: flex-end; }
.notice-list { max-height: 360px; overflow-y: auto; border-top: 1px solid var(--el-border-color-lighter); }
.notice-item {
  display: flex; width: 100%; min-height: 62px; align-items: flex-start; gap: 10px; padding: 10px 14px;
  border: 0; border-bottom: 1px solid var(--el-border-color-lighter); background: transparent;
  color: inherit; font: inherit; text-align: left; cursor: pointer;
}
.notice-item:hover { background: var(--el-fill-color-light); }
.notice-item.is-read { opacity: .58; }
.notice-item--workflow { min-height: 82px; align-items: center; }
.notice-copy { display: grid; min-width: 0; flex: 1; gap: 3px; }
.notice-copy strong, .notice-copy > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.notice-copy strong { color: var(--el-text-color-primary); font-size: 12px; font-weight: 600; }
.notice-copy > span { color: var(--el-text-color-regular); font-size: 11px; }
.notice-copy time { color: var(--el-text-color-placeholder); font-size: 10px; }
.notice-dot { width: 7px; height: 7px; flex: 0 0 7px; border-radius: 50%; background: var(--el-color-primary); }
.notice-item.is-read .notice-dot { background: var(--el-border-color); }
.notice-state { display: grid; min-height: 150px; place-items: center; align-content: center; gap: 8px; color: var(--el-text-color-placeholder); font-size: 12px; }
.notice-state .el-icon { font-size: 24px; }
</style>

<style lang="scss">
.notice-popover { padding: 0 !important; }
.notice-popover .notice-tabs > .el-tabs__header { margin: 0; padding: 0 14px; }
.notice-popover .el-badge__content { transform: scale(.78) translate(45%, -40%); }
</style>
