<template>
  <section class="password-form-section" aria-labelledby="password-form-title">
    <header class="password-form-section__header">
      <span class="password-form-section__index">02</span>
      <div>
        <h2 id="password-form-title">修改密码</h2>
        <p>账户安全</p>
      </div>
    </header>

    <el-form ref="pwdRef" :model="user" :rules="rules" label-position="top" class="password-form">
      <el-form-item label="旧密码" prop="oldPassword">
        <el-input
          v-model="user.oldPassword"
          placeholder="请输入旧密码"
          type="password"
          autocomplete="current-password"
          show-password
        >
          <template #prefix><el-icon><Key /></el-icon></template>
        </el-input>
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword" :rules="infoPwdValidator">
        <el-input
          v-model="user.newPassword"
          placeholder="请输入新密码"
          type="password"
          autocomplete="new-password"
          show-password
        >
          <template #prefix><el-icon><Lock /></el-icon></template>
        </el-input>
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input
          v-model="user.confirmPassword"
          placeholder="请再次输入新密码"
          type="password"
          autocomplete="new-password"
          show-password
          @keyup.enter="submit"
        >
          <template #prefix><el-icon><CircleCheck /></el-icon></template>
        </el-input>
      </el-form-item>

      <div class="password-form__actions">
        <el-button type="primary" :loading="submitting" @click="submit">
          <el-icon v-if="!submitting"><Check /></el-icon>
          <span>更新密码</span>
        </el-button>
        <el-button @click="close">
          <el-icon><Close /></el-icon>
          <span>关闭</span>
        </el-button>
      </div>
    </el-form>
  </section>
</template>

<script setup>
import { usePasswordRule } from "@/utils/passwordRule"
import { updateUserPwd } from "@/api/system/user"

const { proxy } = getCurrentInstance()
const { infoPwdValidator } = usePasswordRule()
// submitting 表示密码更新请求正在执行，防止重复提交敏感操作。
const submitting = ref(false)
const user = reactive({
  oldPassword: "",
  newPassword: "",
  confirmPassword: ""
})

/**
 * 校验两次输入的新密码是否一致。
 * @param {unknown} rule Element Plus 传入的校验规则上下文。
 * @param {string} value 当前确认密码字段值。
 * @param {(error?: Error) => void} callback 校验结果回调。
 * @returns {void}
 */
function equalToPassword(rule, value, callback) {
  if (user.newPassword !== value) {
    callback(new Error("两次输入的密码不一致"))
  } else {
    callback()
  }
}

const rules = {
  oldPassword: [{ required: true, message: "旧密码不能为空", trigger: "blur" }],
  confirmPassword: [
    { required: true, message: "确认密码不能为空", trigger: "blur" },
    { validator: equalToPassword, trigger: ["blur", "change"] }
  ]
}

/**
 * 校验密码表单并调用真实密码更新接口，成功后立即清除页面中的敏感数据。
 * @returns {Promise<void>} 密码更新流程完成后的 Promise。
 */
async function submit() {
  const valid = await proxy.$refs.pwdRef.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  try {
    await updateUserPwd(user.oldPassword, user.newPassword)
    proxy.$modal.msgSuccess("密码修改成功")
    proxy.$refs.pwdRef.resetFields()
  } finally {
    submitting.value = false
  }
}

/**
 * 关闭当前个人中心页签并返回上一个可用页面。
 * @returns {void}
 */
function close() {
  proxy.$tab.closePage()
}
</script>

<style scoped lang="scss">
.password-form-section {
  max-width: 560px;
}

.password-form-section__header {
  display: flex;
  gap: 14px;
  align-items: center;
  margin-bottom: 30px;

  h2 {
    margin: 0;
    color: var(--app-text);
    font-size: 20px;
    font-weight: 680;
    line-height: 1.35;
    letter-spacing: 0;
  }

  p {
    margin: 4px 0 0;
    color: var(--app-text-secondary);
    font-size: 13px;
    line-height: 1.4;
  }
}

.password-form-section__index {
  display: inline-flex;
  flex: 0 0 42px;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  color: #176254;
  font-family: "Bahnschrift", "DIN Alternate", sans-serif;
  font-size: 12px;
  font-weight: 720;
  background: #d8eee8;
  border-radius: 6px;
}

:deep(.password-form .el-form-item) {
  margin-bottom: 23px;
}

:deep(.password-form .el-form-item__label) {
  height: auto;
  margin-bottom: 8px;
  line-height: 20px;
}

:deep(.password-form .el-input__wrapper) {
  min-height: 42px;
}

.password-form__actions {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-top: 12px;
  padding-top: 24px;
  border-top: 1px solid var(--app-border);

  .el-button {
    min-width: 112px;
    margin-left: 0;
  }
}

:global(html.dark) .password-form-section__index {
  color: #91d8c6;
  background: #1a382f;
}

@media (max-width: 640px) {
  .password-form-section__header {
    margin-bottom: 24px;
  }

  .password-form__actions {
    align-items: stretch;
    flex-direction: column;

    .el-button {
      width: 100%;
    }
  }
}
</style>
