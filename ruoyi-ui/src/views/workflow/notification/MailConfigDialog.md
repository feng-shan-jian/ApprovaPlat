# MailConfigDialog

## 组件简介与作用

`MailConfigDialog` 是审批通知页面的 SMTP 单例配置弹窗。组件从正式后端读取脱敏配置，支持使用尚未保存的字段发送测试邮件，并通过 revision 乐观锁保存正式配置。

授权码输入框每次打开均为空；GET 响应只包含配置状态和非敏感字段，数据库密文、IV 与服务端密钥留在后端。表单草稿随当前对话框实例管理。

## 使用方式

```vue
<MailConfigDialog
  v-model="mailConfigVisible"
  @saved="handleMailConfigSaved"
/>
```

父页面必须先使用 `workflow:notification:mailManage` 控制组件入口；服务端接口继续执行相同权限校验。

## Props

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `modelValue` | `boolean` | `false` | 控制 SMTP 配置弹窗是否打开，支持 `v-model`。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `boolean` | 弹窗打开或关闭状态。 |
| `saved` | `{ configured: boolean, revision: number }` | 保存成功并重新查询正式配置后发出，只包含脱敏状态。 |

示例：

```js
function handleMailConfigSaved(result) {
  mailChannelAvailable.value = result.configured === true
}
```

## 公开方法

无。查询、测试、保存、冲突重载和敏感字段清理由组件内部管理。

## 字段与请求语义

- 未配置时 `revision=0`，首次保存和测试必须填写 `credential`。
- 已配置时授权码输入框始终为空；只有 SMTP 服务器、端口、加密方式和登录账号均未变化时，留空才表示沿用原授权码。
- 认证身份任一字段变化时，组件会立即提示并要求重新填写授权码；发件邮箱和发件人名称可以在授权码留空时单独修改。
- 保存和测试都提交当前弹窗中的 SMTP 字段及 `expectedRevision`。
- 测试请求额外提交 `testRecipient`；测试成功证明当前输入可用，正式配置仍以 PUT 保存结果为准。
- 保存成功后组件重新调用 GET，读取新 revision 后才关闭弹窗并触发 `saved`。
- PUT 已成功但随后 GET 回读失败时，组件明确提示“配置已保存但刷新失败”，关闭当前数据已失效的弹窗并要求重新打开或刷新页面；保存结果以已提交 PUT 为准。
- 只有稳定子码 `MAIL_CONFIG_REVISION_CONFLICT` 会触发冲突重载；`MAIL_CREDENTIAL_REENTRY_REQUIRED` 会明确引导重新输入授权码，其他错误继续展示后端分类提示。

## 安全设计

- GET 响应按字段白名单映射，表单状态仅接收主机、端口、用户名、发件信息、TLS 模式、超时、revision 和配置状态。
- 密码框使用 `type="password"` 与 `autocomplete="new-password"`，每次打开均初始化为空值。
- 用户输入的新授权码在测试后保留到当前弹窗内，确保“测试成功后立即保存”写入的是同一授权码；保存完成、弹窗关闭、冲突重载及组件重置时都会清空。
- 最近一次 GET 返回的公开认证身份仅保存在组件内存中，用于前端即时提示；服务端仍会在解密、测试连接和保存之前重新执行同一凭据绑定校验。
- SMTP 保存和测试 API 都设置 `headers.repeatSubmit=false`，敏感请求体只在当前内存请求生命周期中存在。
- 测试请求使用 30 秒客户端超时，以便接收后端已分类的连接、认证和 TLS 错误。
- 测试结果优先读取真实 HTTP 响应中经过后端分类的 `msg`，页面截取短提示并采用响应消息白名单。

## 关键设计思路

- 表单和测试区分层：正式字段位于主体，测试收件人与按钮位于底部分隔区。
- 弹窗顶部直接展示当前配置状态、编辑表单和测试入口，业务操作保持聚焦。
- 请求进行中弹窗进入锁定状态，响应完成后再允许关闭并清理敏感表单。
- 保存按钮与测试按钮互斥，前端 loading 负责交互防重，后端 revision、限流和事务负责最终一致性。

## 最小接入示例

```vue
<script setup>
import MailConfigDialog from './MailConfigDialog.vue'

const visible = ref(false)
const mailState = reactive({ configured: false, revision: 0 })

function onSaved({ configured, revision }) {
  Object.assign(mailState, { configured, revision })
}
</script>

<template>
  <el-button @click="visible = true">邮件服务</el-button>
  <MailConfigDialog v-model="visible" @saved="onSaved" />
</template>
```
