# userAvatar 组件

## 组件简介

`userAvatar.vue` 是个人中心内的头像查看与裁剪上传组件。组件从登录用户 Pinia 状态读取当前头像，使用 `vue-cropper` 完成方形裁剪，并通过 `/system/user/profile/avatar` 真实接口持久化头像。

## 使用方式

组件直接使用当前登录用户状态与后端接口：

```vue
<script setup>
import userAvatar from "./userAvatar"
</script>

<template>
  <userAvatar />
</template>
```

## Props

无。

## Emits

无。上传成功后组件直接更新 `useUserStore().avatar`，导航栏、锁屏页和个人中心会共享同一头像地址。

## 公开方法

无。

## 关键设计

- 接受 JPG、PNG、WebP 文件，前端单文件上限为 10 MB。
- 裁剪框固定为 320 x 320，上传时使用后端要求的 `avatarfile` 字段。
- 只有用户点击“保存头像”后才调用真实上传接口；关闭弹窗会丢弃未提交的裁剪状态。
- 远程头像加载失败时回退到项目内唯一默认资源 `default-avatar.png`；原 RuoYi 默认头像资源已删除。
- 弹窗支持放大、缩小、旋转和实时圆形预览，并适配窄屏布局。

## 最小接入示例

```vue
<template>
  <aside aria-label="个人信息">
    <userAvatar />
  </aside>
</template>

<script setup>
import userAvatar from "@/views/system/user/profile/userAvatar"
</script>
```
