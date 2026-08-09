<template>
  <section class="profile-form-section" aria-labelledby="profile-form-title">
    <header class="profile-form-section__header">
      <span class="profile-form-section__index">01</span>
      <div>
        <h2 id="profile-form-title">基本资料</h2>
        <p>{{ form.nickName || user.userName || "未设置昵称" }}</p>
      </div>
    </header>

    <el-form ref="userRef" :model="form" :rules="rules" label-position="top" class="profile-form">
      <div class="profile-form__grid">
        <el-form-item label="用户昵称" prop="nickName">
          <el-input v-model="form.nickName" maxlength="30" clearable>
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="手机号码" prop="phonenumber">
          <el-input v-model="form.phonenumber" maxlength="11" clearable>
            <template #prefix><el-icon><Iphone /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" maxlength="50" clearable>
            <template #prefix><el-icon><Message /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="性别" prop="sex">
          <el-segmented v-model="form.sex" :options="sexOptions" class="profile-form__segmented" />
        </el-form-item>
      </div>

      <div class="profile-form__actions">
        <el-button type="primary" :loading="submitting" @click="submit">
          <el-icon v-if="!submitting"><Check /></el-icon>
          <span>保存资料</span>
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
import { updateUserProfile } from "@/api/system/user"
import useUserStore from "@/store/modules/user"

const props = defineProps({
  user: {
    type: Object,
    default: () => ({})
  }
})
const emit = defineEmits(["updated"])

const { proxy } = getCurrentInstance()
const userStore = useUserStore()
// submitting 表示资料更新请求正在执行，防止用户重复提交相同资料。
const submitting = ref(false)
const form = reactive({
  nickName: "",
  phonenumber: "",
  email: "",
  sex: "2"
})
const sexOptions = [
  { label: "男", value: "0" },
  { label: "女", value: "1" },
  { label: "未设置", value: "2" }
]
const rules = {
  nickName: [{ required: true, message: "用户昵称不能为空", trigger: "blur" }],
  email: [
    { required: true, message: "邮箱地址不能为空", trigger: "blur" },
    { type: "email", message: "请输入正确的邮箱地址", trigger: ["blur", "change"] }
  ],
  phonenumber: [
    { required: true, message: "手机号码不能为空", trigger: "blur" },
    { pattern: /^1[3-9]\d{9}$/, message: "请输入正确的手机号码", trigger: "blur" }
  ]
}

/**
 * 校验并通过真实个人资料接口保存当前表单，同时同步全局昵称和页面回显。
 * @returns {Promise<void>} 保存流程完成后的 Promise。
 */
async function submit() {
  const valid = await proxy.$refs.userRef.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  try {
    // payload 仅包含后端个人资料接口允许修改的字段，避免提交只读账号数据。
    const payload = {
      nickName: form.nickName.trim(),
      phonenumber: form.phonenumber.trim(),
      email: form.email.trim(),
      sex: form.sex
    }
    await updateUserProfile(payload)
    userStore.nickName = payload.nickName
    emit("updated", payload)
    proxy.$modal.msgSuccess("个人资料已更新")
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

watch(
  () => props.user,
  (user) => {
    // 接口资料加载完成后再覆盖表单，避免用户输入被无关响应反复重置。
    if (user && Object.keys(user).length) {
      form.nickName = user.nickName || ""
      form.phonenumber = user.phonenumber || ""
      form.email = user.email || ""
      form.sex = user.sex === null || user.sex === undefined ? "2" : String(user.sex)
    }
  },
  { immediate: true }
)
</script>

<style scoped lang="scss">
.profile-form-section {
  max-width: 780px;
}

.profile-form-section__header {
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

.profile-form-section__index {
  display: inline-flex;
  flex: 0 0 42px;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  color: #a74632;
  font-family: "Bahnschrift", "DIN Alternate", sans-serif;
  font-size: 12px;
  font-weight: 720;
  background: #f5ddd6;
  border-radius: 6px;
}

.profile-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 20px;
}

.profile-form__segmented {
  width: 100%;
}

:deep(.profile-form__segmented .el-segmented__group) {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  width: 100%;
}

:deep(.profile-form__segmented .el-segmented__item) {
  min-width: 0;
}

:deep(.profile-form .el-form-item) {
  margin-bottom: 24px;
}

:deep(.profile-form .el-form-item__label) {
  height: auto;
  margin-bottom: 8px;
  line-height: 20px;
}

:deep(.profile-form .el-input__wrapper),
:deep(.profile-form .el-segmented) {
  min-height: 42px;
}

.profile-form__actions {
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

:global(html.dark) .profile-form-section__index {
  color: #ee9a86;
  background: #40271f;
}

@media (max-width: 640px) {
  .profile-form-section__header {
    margin-bottom: 24px;
  }

  .profile-form__grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }

  .profile-form__actions {
    align-items: stretch;
    flex-direction: column;

    .el-button {
      width: 100%;
    }
  }
}
</style>
