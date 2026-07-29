<template>
  <div class="login">
    <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="login-form">
      <div class="login-brand">
        <span class="login-brand-mark" aria-hidden="true">AP</span>
        <h1 class="title">{{ title }}</h1>
      </div>
      <el-form-item prop="username">
        <el-input
          v-model="loginForm.username"
          type="text"
          size="large"
          auto-complete="off"
          placeholder="账号"
        >
          <template #prefix><svg-icon icon-class="user" class="el-input__icon input-icon" /></template>
        </el-input>
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="loginForm.password"
          type="password"
          size="large"
          auto-complete="off"
          placeholder="密码"
          @keyup.enter="handleLogin"
        >
          <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
        </el-input>
      </el-form-item>
      <el-form-item prop="code" v-if="captchaEnabled" class="captcha-item">
        <el-input
          v-model="loginForm.code"
          class="captcha-input"
          size="large"
          auto-complete="off"
          placeholder="验证码"
          @keyup.enter="handleLogin"
        >
          <template #prefix><svg-icon icon-class="validCode" class="el-input__icon input-icon" /></template>
        </el-input>
        <div class="login-code">
          <img
            :src="codeUrl"
            alt="点击刷新验证码"
            class="login-code-img"
            role="button"
            tabindex="0"
            @click="getCode"
            @keydown.enter="getCode"
            @keydown.space.prevent="getCode"
          />
        </div>
      </el-form-item>
      <el-checkbox v-model="loginForm.rememberMe" class="remember-option">记住密码</el-checkbox>
      <el-form-item class="submit-row">
        <el-button
          :loading="loading"
          class="login-button"
          size="large"
          type="primary"
          @click.prevent="handleLogin"
        >
          <span v-if="!loading">登录</span>
          <span v-else>登录中...</span>
        </el-button>
        <div class="register-link" v-if="register">
          <router-link class="link-type" :to="'/register'">立即注册</router-link>
        </div>
      </el-form-item>
    </el-form>
    <!--  底部  -->
    <div class="el-login-footer">
      <span>{{ footerContent }}</span>
    </div>
  </div>
</template>

<script setup>
import { getCodeImg } from "@/api/login"
import Cookies from "js-cookie"
import { encrypt, decrypt } from "@/utils/jsencrypt"
import useUserStore from '@/store/modules/user'
import defaultSettings from '@/settings'

const title = import.meta.env.VITE_APP_TITLE
const footerContent = defaultSettings.footerContent
const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()

const loginForm = ref({
  username: "",
  password: "",
  rememberMe: false,
  code: "",
  uuid: ""
})

const loginRules = {
  username: [{ required: true, trigger: "blur", message: "请输入您的账号" }],
  password: [{ required: true, trigger: "blur", message: "请输入您的密码" }],
  code: [{ required: true, trigger: "change", message: "请输入验证码" }]
}

const codeUrl = ref("")
const loading = ref(false)
// 验证码开关
const captchaEnabled = ref(true)
// 注册开关
const register = ref(false)
const redirect = ref(undefined)

watch(route, (newRoute) => {
    redirect.value = newRoute.query && newRoute.query.redirect
}, { immediate: true })

function handleLogin() {
  proxy.$refs.loginRef.validate(valid => {
    if (valid) {
      loading.value = true
      // 勾选了需要记住密码设置在 cookie 中设置记住用户名和密码
      if (loginForm.value.rememberMe) {
        Cookies.set("username", loginForm.value.username, { expires: 30 })
        Cookies.set("password", encrypt(loginForm.value.password), { expires: 30 })
        Cookies.set("rememberMe", loginForm.value.rememberMe, { expires: 30 })
      } else {
        // 否则移除
        Cookies.remove("username")
        Cookies.remove("password")
        Cookies.remove("rememberMe")
      }
      // 调用action的登录方法
      userStore.login(loginForm.value).then(() => {
        const query = route.query
        const otherQueryParams = Object.keys(query).reduce((acc, cur) => {
          if (cur !== "redirect") {
            acc[cur] = query[cur]
          }
          return acc
        }, {})
        router.push({ path: redirect.value || "/", query: otherQueryParams })
      }).catch(() => {
        loading.value = false
        // 重新获取验证码
        if (captchaEnabled.value) {
          getCode()
        }
      })
    }
  })
}

function getCode() {
  getCodeImg().then(res => {
    captchaEnabled.value = res.captchaEnabled === undefined ? true : res.captchaEnabled
    if (captchaEnabled.value) {
      codeUrl.value = "data:image/gif;base64," + res.img
      loginForm.value.uuid = res.uuid
    }
  })
}

function getCookie() {
  const username = Cookies.get("username")
  const password = Cookies.get("password")
  const rememberMe = Cookies.get("rememberMe")
  loginForm.value = {
    username: username === undefined ? loginForm.value.username : username,
    password: password === undefined ? loginForm.value.password : decrypt(password),
    rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
  }
}

getCode()
getCookie()
</script>

<style lang='scss' scoped>
.login {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  min-height: 100%;
  padding: 48px clamp(48px, 9vw, 144px);
  overflow: hidden;
  background-image: url("../assets/images/login-background.jpg");
  background-position: center;
  background-size: cover;
  isolation: isolate;

  &::before {
    position: absolute;
    inset: 0;
    z-index: -1;
    content: "";
    background: rgb(12 22 18 / 48%);
  }
}

.login-brand {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 30px;
}

.login-brand-mark {
  display: inline-flex;
  flex: 0 0 38px;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  color: #173b32;
  font-size: 12px;
  font-weight: 750;
  background: #b9e4d7;
  border-radius: 6px;
}

.title {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--app-text);
  font-size: 23px;
  font-weight: 680;
  line-height: 1.25;
  letter-spacing: 0;
}

.login-form {
  z-index: 1;
  width: min(408px, 100%);
  padding: 36px 38px 30px;
  background: rgb(255 255 255 / 96%);
  border: 1px solid rgb(255 255 255 / 72%);
  border-radius: 8px;
  box-shadow: 0 24px 70px rgb(8 20 15 / 28%);
  backdrop-filter: blur(14px);
  animation: login-panel-in 420ms cubic-bezier(0.22, 1, 0.36, 1) both;

  :deep(.el-form-item) {
    margin-bottom: 18px;
  }

  .el-input {
    height: 44px;
  }

  :deep(.el-input__wrapper) {
    padding: 0 14px;
    background: color-mix(in srgb, var(--app-surface) 92%, transparent);
  }

  :deep(.el-input__inner) {
    height: 44px;
  }

  .input-icon {
    width: 15px;
    height: 44px;
    margin-left: 0;
    color: var(--app-text-muted);
  }
}

.captcha-item :deep(.el-form-item__content) {
  display: flex;
  flex-wrap: nowrap;
  gap: 10px;
  width: 100%;
}

.captcha-input {
  flex: 1 1 auto;
  min-width: 0;
}

.login-code {
  flex: 0 0 112px;
  width: 112px;
  height: 44px;
  overflow: hidden;
  background: var(--app-surface-soft);
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

.login-code-img {
  display: block;
  width: 100%;
  height: 100%;
  padding: 0;
  cursor: pointer;
  object-fit: contain;
}

.remember-option {
  margin: 0 0 24px;
  color: var(--app-text-secondary);
}

.submit-row {
  width: 100%;
  margin-bottom: 0 !important;
}

.login-button {
  width: 100%;
  height: 44px;
  font-size: 15px;
}

.register-link {
  width: 100%;
  margin-top: 14px;
  text-align: right;
}

.el-login-footer {
  position: fixed;
  bottom: 14px;
  left: 0;
  width: 100%;
  min-height: 24px;
  padding: 0 16px;
  text-align: center;
  color: rgb(255 255 255 / 76%);
  font-size: 12px;
  line-height: 24px;
  letter-spacing: 0;
  text-shadow: 0 1px 8px rgb(0 0 0 / 36%);
}

:global(html.dark) .login {
  &::before {
    background: rgb(0 0 0 / 62%);
  }

  .login-form {
    background: color-mix(in srgb, var(--app-surface) 94%, transparent);
    border-color: var(--app-border);
    box-shadow: 0 24px 70px rgb(0 0 0 / 46%);
  }
}

@keyframes login-panel-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 640px) {
  .login {
    justify-content: center;
    padding: 24px 16px 58px;
    background-position: 58% center;
  }

  .login-form {
    padding: 30px 24px 24px;
  }

  .login-brand {
    margin-bottom: 26px;
  }

  .title {
    font-size: 21px;
  }

  .login-code {
    flex-basis: 104px;
    width: 104px;
  }

  .el-login-footer {
    bottom: 8px;
    font-size: 11px;
  }
}

@media (max-height: 620px) {
  .login {
    align-items: flex-start;
    overflow-y: auto;
  }

  .el-login-footer {
    position: absolute;
  }
}
</style>
