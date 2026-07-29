<template>
  <main class="workspace-home">
    <section class="welcome-strip" aria-labelledby="workspace-title">
      <div class="identity">
        <span class="identity-mark" aria-hidden="true">AP</span>
        <div>
          <p class="product-name">ApprovaPlat</p>
          <h1 id="workspace-title">{{ userStore.nickName || userStore.name || '用户' }}，欢迎回来</h1>
          <p class="workspace-name">审批管理平台工作台</p>
        </div>
      </div>

      <router-link class="profile-link" to="/user/profile">
        <el-icon><User /></el-icon>
        <span>个人中心</span>
        <el-icon><ArrowRight /></el-icon>
      </router-link>
    </section>

    <section class="quick-section" aria-labelledby="quick-title">
      <header class="section-heading">
        <p>ApprovaPlat</p>
        <h2 id="quick-title">快捷入口</h2>
      </header>

      <nav class="quick-grid" aria-label="快捷入口">
        <router-link class="quick-link" to="/user/profile">
          <span class="quick-icon quick-icon--teal"><svg-icon icon-class="user" /></span>
          <span class="quick-label">个人中心</span>
          <el-icon><ArrowRight /></el-icon>
        </router-link>

        <router-link v-hasPermi="['system:user:list']" class="quick-link" to="/system/user">
          <span class="quick-icon quick-icon--coral"><svg-icon icon-class="people" /></span>
          <span class="quick-label">用户管理</span>
          <el-icon><ArrowRight /></el-icon>
        </router-link>

        <router-link v-hasPermi="['system:role:list']" class="quick-link" to="/system/role">
          <span class="quick-icon quick-icon--gold"><svg-icon icon-class="peoples" /></span>
          <span class="quick-label">角色管理</span>
          <el-icon><ArrowRight /></el-icon>
        </router-link>

        <router-link v-hasPermi="['system:notice:list']" class="quick-link" to="/system/notice">
          <span class="quick-icon quick-icon--ink"><svg-icon icon-class="message" /></span>
          <span class="quick-label">通知公告</span>
          <el-icon><ArrowRight /></el-icon>
        </router-link>

        <router-link v-hasPermi="['monitor:operlog:list']" class="quick-link" to="/monitor/operlog">
          <span class="quick-icon quick-icon--teal"><svg-icon icon-class="log" /></span>
          <span class="quick-label">操作日志</span>
          <el-icon><ArrowRight /></el-icon>
        </router-link>
      </nav>
    </section>
  </main>
</template>

<script setup name="Index">
import useUserStore from '@/store/modules/user'

// 当前登录用户由后端 getInfo 接口加载，用于首页身份回显与权限入口控制。
const userStore = useUserStore()
</script>

<style scoped lang="scss">
.workspace-home {
  min-height: calc(100vh - 84px);
  padding: 28px 30px 34px;
  color: var(--el-text-color-primary);
  background: var(--app-page-bg);
}

.welcome-strip {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 184px;
  padding: 34px 38px;
  overflow: hidden;
  color: #f7fbf9;
  background: #16231e;
  border: 1px solid #2a3a34;
  border-radius: 8px;
  box-shadow: 0 14px 34px rgb(24 41 35 / 11%);
  animation: workspace-enter 360ms cubic-bezier(0.22, 1, 0.36, 1) both;

  &::after {
    position: absolute;
    right: 0;
    bottom: 0;
    width: 184px;
    height: 4px;
    content: "";
    background: #e36b4f;
  }
}

.identity {
  display: flex;
  gap: 22px;
  align-items: center;
  min-width: 0;
}

.identity-mark {
  display: inline-flex;
  flex: 0 0 56px;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  color: #173b32;
  font-size: 15px;
  font-weight: 750;
  letter-spacing: 0;
  background: #b9e4d7;
  border-radius: 7px;
}

.product-name,
.workspace-name,
.section-heading p {
  margin: 0;
  letter-spacing: 0;
}

.product-name {
  color: #b9e4d7;
  font-size: 13px;
  font-weight: 600;
}

h1 {
  margin: 8px 0 6px;
  overflow-wrap: anywhere;
  font-size: 29px;
  font-weight: 680;
  line-height: 1.35;
  letter-spacing: 0;
}

.workspace-name {
  color: #b7c3bf;
  font-size: 14px;
}

.profile-link {
  position: relative;
  z-index: 1;
  display: inline-flex;
  flex: 0 0 auto;
  gap: 8px;
  align-items: center;
  min-height: 40px;
  padding: 0 14px;
  color: #f7fbf9;
  font-size: 14px;
  text-decoration: none;
  border: 1px solid #53635d;
  border-radius: 6px;
  transition: border-color 160ms ease, background-color 160ms ease, transform 160ms ease;

  &:hover,
  &:focus-visible {
    background: #24342e;
    border-color: #b9e4d7;
    outline: none;
    transform: translateY(-1px);
  }
}

.quick-section {
  margin-top: 28px;
}

.section-heading {
  margin-bottom: 15px;
  animation: workspace-enter 360ms 70ms cubic-bezier(0.22, 1, 0.36, 1) both;

  p {
    color: #d85f45;
    font-size: 12px;
    font-weight: 700;
  }

  h2 {
    margin: 5px 0 0;
    font-size: 20px;
    font-weight: 680;
    letter-spacing: 0;
  }
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 13px;
}

.quick-link {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 18px;
  gap: 12px;
  align-items: center;
  min-height: 76px;
  padding: 16px;
  color: var(--el-text-color-primary);
  text-decoration: none;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 7px;
  box-shadow: var(--app-shadow-sm);
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
  animation: workspace-enter 360ms 110ms cubic-bezier(0.22, 1, 0.36, 1) both;

  &:nth-child(2) { animation-delay: 140ms; }
  &:nth-child(3) { animation-delay: 170ms; }
  &:nth-child(4) { animation-delay: 200ms; }
  &:nth-child(5) { animation-delay: 230ms; }

  &:hover,
  &:focus-visible {
    border-color: var(--el-color-primary-light-5);
    box-shadow: 0 10px 24px rgb(29 48 42 / 9%);
    outline: none;
    transform: translateY(-1px);
  }

  > .el-icon {
    color: var(--el-text-color-secondary);
  }
}

.quick-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 6px;

  .svg-icon {
    width: 18px;
    height: 18px;
  }
}

.quick-icon--teal {
  color: #0f6a5d;
  background: #d8eee8;
}

.quick-icon--coral {
  color: #a84430;
  background: #f6ddd6;
}

.quick-icon--gold {
  color: #7a5710;
  background: #f5e8bf;
}

.quick-icon--ink {
  color: #3e4d5a;
  background: #e2e8ec;
}

.quick-label {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 15px;
  font-weight: 650;
}

@keyframes workspace-enter {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

:global(html.dark) .quick-icon--teal {
  color: #8fd6c4;
  background: #19372f;
}

:global(html.dark) .quick-icon--coral {
  color: #f0a08d;
  background: #42271f;
}

:global(html.dark) .quick-icon--gold {
  color: #e5c66f;
  background: #3b321b;
}

:global(html.dark) .quick-icon--ink {
  color: #bcc9d2;
  background: #273038;
}

@media (max-width: 960px) {
  .quick-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .workspace-home {
    min-height: calc(100vh - 50px);
    padding: 16px;
  }

  .welcome-strip {
    align-items: flex-start;
    min-height: 220px;
    padding: 24px;
  }

  .identity {
    align-items: flex-start;
  }

  h1 {
    font-size: 24px;
  }

  .profile-link {
    position: absolute;
    bottom: 24px;
    left: 24px;
  }

  .quick-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
