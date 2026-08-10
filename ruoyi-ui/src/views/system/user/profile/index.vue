<template>
  <main v-loading="loading" class="app-container profile-page">
    <header class="profile-page__header">
      <div>
        <p class="profile-page__eyebrow">ACCOUNT</p>
        <h1>个人中心</h1>
      </div>
      <div class="profile-page__account">
        <span class="profile-page__status-dot" aria-hidden="true"></span>
        <span>{{ displayValue(state.user.userName) }}</span>
      </div>
    </header>

    <div class="profile-layout">
      <aside class="identity-panel" aria-label="个人信息">
        <div class="identity-panel__accent" aria-hidden="true"></div>
        <div class="identity-panel__portrait">
          <userAvatar />
          <p class="identity-panel__label">PROFILE</p>
          <h2>{{ displayValue(state.user.nickName || state.user.userName) }}</h2>
          <p class="identity-panel__role">{{ displayValue(state.roleGroup) }}</p>
        </div>

        <dl class="identity-list">
          <div class="identity-list__item">
            <dt><el-icon><User /></el-icon><span>用户名称</span></dt>
            <dd>{{ displayValue(state.user.userName) }}</dd>
          </div>
          <div class="identity-list__item">
            <dt><el-icon><Iphone /></el-icon><span>手机号码</span></dt>
            <dd>{{ displayValue(state.user.phonenumber) }}</dd>
          </div>
          <div class="identity-list__item">
            <dt><el-icon><Message /></el-icon><span>用户邮箱</span></dt>
            <dd>{{ displayValue(state.user.email) }}</dd>
          </div>
          <div class="identity-list__item">
            <dt><el-icon><OfficeBuilding /></el-icon><span>所属部门</span></dt>
            <dd>{{ departmentLabel }}</dd>
          </div>
          <div class="identity-list__item">
            <dt><el-icon><UserFilled /></el-icon><span>所属角色</span></dt>
            <dd>{{ displayValue(state.roleGroup) }}</dd>
          </div>
          <div class="identity-list__item">
            <dt><el-icon><Calendar /></el-icon><span>创建日期</span></dt>
            <dd>{{ displayValue(state.user.createTime) }}</dd>
          </div>
        </dl>
      </aside>

      <section class="profile-workspace" aria-label="账户设置">
        <el-tabs v-model="selectedTab" class="profile-tabs">
          <el-tab-pane name="userinfo">
            <template #label>
              <span class="profile-tabs__label"><el-icon><EditPen /></el-icon>基本资料</span>
            </template>
            <userInfo :user="state.user" @updated="handleProfileUpdated" />
          </el-tab-pane>
          <el-tab-pane name="resetPwd">
            <template #label>
              <span class="profile-tabs__label"><el-icon><Lock /></el-icon>修改密码</span>
            </template>
            <resetPwd />
          </el-tab-pane>
        </el-tabs>
      </section>
    </div>
  </main>
</template>

<script setup name="Profile">
import userAvatar from "./userAvatar"
import userInfo from "./userInfo"
import resetPwd from "./resetPwd"
import { getUserProfile } from "@/api/system/user"

const route = useRoute()
const { proxy } = getCurrentInstance()
const selectedTab = ref("userinfo")
// loading 表示个人资料接口是否仍在加载，用于阻止数据未回显时误操作表单。
const loading = ref(true)
const state = reactive({
  user: {},
  roleGroup: "",
  postGroup: ""
})

const departmentLabel = computed(() => {
  const departmentName = state.user.dept?.deptName
  const departmentParts = [departmentName, state.postGroup].filter(Boolean)
  return departmentParts.length ? departmentParts.join(" / ") : "未设置"
})

/**
 * 将接口字段转换为适合页面回显的文本，避免空值破坏资料布局。
 * @param {unknown} value 待展示的接口字段值。
 * @returns {string} 可直接展示的文本，空值统一返回“未设置”。
 */
function displayValue(value) {
  if (value === null || value === undefined || String(value).trim() === "") {
    return "未设置"
  }
  return String(value)
}

/**
 * 从真实个人资料接口加载当前登录用户、角色组和岗位组信息。
 * @returns {Promise<void>} 资料加载完成后的 Promise。
 */
async function getUser() {
  loading.value = true
  try {
    const response = await getUserProfile()
    state.user = response.data || {}
    state.roleGroup = response.roleGroup || ""
    state.postGroup = response.postGroup || ""
  } catch (error) {
    // 请求层负责输出后端错误详情，这里补充页面级提示并保留可恢复的空状态。
    proxy.$modal.msgError("个人资料加载失败，请稍后重试")
  } finally {
    loading.value = false
  }
}

/**
 * 接收资料表单保存后的真实接口结果，并同步当前页面的资料回显。
 * @param {{ nickName?: string, phonenumber?: string, email?: string, sex?: string }} profile 已保存的个人资料字段。
 * @returns {void}
 */
function handleProfileUpdated(profile) {
  Object.assign(state.user, profile)
}

onMounted(() => {
  // 密码到期等系统入口会通过路由参数直接打开密码页签。
  const activeTab = route.params?.activeTab
  if (["userinfo", "resetPwd"].includes(activeTab)) {
    selectedTab.value = activeTab
  }
  getUser()
})
</script>

<style scoped lang="scss">
.profile-page {
  min-height: calc(100vh - 84px);
  color: var(--app-text);
  background:
    linear-gradient(90deg, color-mix(in srgb, var(--app-border) 46%, transparent) 1px, transparent 1px),
    var(--app-page-bg);
  background-size: 72px 100%;
}

.profile-page__header,
.profile-layout {
  width: min(1180px, 100%);
  margin-inline: auto;
}

.profile-page__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding: 10px 0 22px;
  border-bottom: 1px solid var(--app-border-strong);
  animation: profile-enter 320ms cubic-bezier(0.22, 1, 0.36, 1) both;

  h1 {
    margin: 5px 0 0;
    font-size: 28px;
    font-weight: 720;
    line-height: 1.25;
    letter-spacing: 0;
  }
}

.profile-page__eyebrow {
  margin: 0;
  color: var(--app-accent);
  font-family: "Bahnschrift", "DIN Alternate", "Microsoft YaHei UI", sans-serif;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
}

.profile-page__account {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  max-width: 44%;
  overflow: hidden;
  color: var(--app-text-secondary);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-page__status-dot {
  flex: 0 0 7px;
  width: 7px;
  height: 7px;
  background: #2f967d;
  border-radius: 50%;
  box-shadow: 0 0 0 4px color-mix(in srgb, #2f967d 14%, transparent);
}

.profile-layout {
  display: grid;
  grid-template-columns: minmax(286px, 0.72fr) minmax(0, 1.72fr);
  gap: 22px;
  align-items: stretch;
  margin-top: 22px;
}

.identity-panel,
.profile-workspace {
  min-width: 0;
  border-radius: 8px;
  animation: profile-enter 360ms 60ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.identity-panel {
  position: relative;
  overflow: hidden;
  color: #f1f6f3;
  background: #18241f;
  border: 1px solid #2a3933;
  box-shadow: 0 16px 36px rgb(23 36 31 / 13%);
}

.identity-panel__accent {
  width: 82px;
  height: 4px;
  margin-left: auto;
  background: #df6a50;
}

.identity-panel__portrait {
  padding: 32px 28px 26px;
  text-align: center;

  h2 {
    margin: 7px 0 4px;
    overflow-wrap: anywhere;
    font-size: 22px;
    font-weight: 680;
    line-height: 1.35;
    letter-spacing: 0;
  }
}

.identity-panel__label {
  margin: 18px 0 0;
  color: #7fc4b3;
  font-family: "Bahnschrift", "DIN Alternate", sans-serif;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0;
}

.identity-panel__role {
  margin: 0;
  color: #a9b8b2;
  font-size: 13px;
  line-height: 1.6;
}

.identity-list {
  margin: 0;
  padding: 0 28px 24px;
}

.identity-list__item {
  display: grid;
  grid-template-columns: minmax(94px, auto) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
  padding: 14px 0;
  border-top: 1px solid rgb(255 255 255 / 9%);

  dt {
    display: inline-flex;
    gap: 8px;
    align-items: center;
    color: #91a39b;
    font-size: 12px;
    font-weight: 600;
    line-height: 20px;
  }

  dd {
    min-width: 0;
    margin: 0;
    overflow-wrap: anywhere;
    color: #eef4f1;
    font-size: 13px;
    font-weight: 560;
    line-height: 20px;
    text-align: right;
  }
}

.profile-workspace {
  min-height: 590px;
  padding: 4px 30px 30px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  box-shadow: var(--app-shadow-sm);
  animation-delay: 110ms;
}

.profile-tabs {
  height: 100%;
}

.profile-tabs__label {
  display: inline-flex;
  gap: 7px;
  align-items: center;
}

:deep(.profile-tabs > .el-tabs__header) {
  margin-bottom: 0;
}

:deep(.profile-tabs > .el-tabs__header .el-tabs__item) {
  height: 58px;
  padding-inline: 20px;
}

:deep(.profile-tabs > .el-tabs__content) {
  padding-top: 30px;
}

@keyframes profile-enter {
  from {
    opacity: 0;
    transform: translateY(7px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

:global(html.dark) .identity-panel {
  color: #f1f6f3;
  background: #111915;
  border-color: #2b3933;
}

@media (max-width: 920px) {
  .profile-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .identity-panel__portrait {
    padding-top: 28px;
  }

  .profile-workspace {
    min-height: 0;
  }
}

@media (max-width: 640px) {
  .profile-page {
    min-height: calc(100vh - 50px);
    background-size: 48px 100%;
  }

  .profile-page__header {
    padding-top: 4px;

    h1 {
      font-size: 24px;
    }
  }

  .profile-page__account {
    max-width: 48%;
  }

  .profile-layout {
    gap: 14px;
    margin-top: 14px;
  }

  .identity-panel__portrait {
    padding-inline: 20px;
  }

  .identity-list {
    padding-inline: 20px;
  }

  .profile-workspace {
    padding: 3px 18px 22px;
  }

  :deep(.profile-tabs > .el-tabs__header .el-tabs__item) {
    height: 54px;
    padding-inline: 12px;
  }

  :deep(.profile-tabs > .el-tabs__content) {
    padding-top: 24px;
  }
}
</style>
