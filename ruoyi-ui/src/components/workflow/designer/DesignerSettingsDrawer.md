# DesignerSettingsDrawer

## 组件简介与作用

`DesignerSettingsDrawer` 编辑当前用户保存在浏览器 `localStorage` 中的非业务界面偏好。组件维护可取消草稿；父页面按用户和协议版本写入白名单字段并回写 `preference` 后，设计器采用新状态。

## 使用方式

```vue
<DesignerSettingsDrawer
  v-model="settingsVisible"
  :preference="designerPreference"
  :saving="preferenceSaving"
  @save="savePreference"
  @reset="restoreDefaultPreference"
/>
```

## Props

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `modelValue` | `boolean` | 抽屉显示状态。 |
| `preference` | `object` | 主题、网格、小地图、Token 模拟和面板状态。 |
| `saving` | `boolean` | 浏览器存储写入期间的加载状态。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `boolean` | 打开或关闭抽屉。 |
| `save` | `object` | 提交字段完整的偏好草稿。 |
| `reset` | 无 | 请求父页面只删除当前用户键并恢复当前协议默认值。 |

## 公开方法

无。

## 关键设计思路

- 抽屉打开时从父页面当前偏好重新创建草稿，取消不会改变已应用偏好。
- 父页面使用 `workflow:designer:preference:v1:{userId}` 隔离用户，值固定包含 `schemaVersion: 1` 和六个白名单字段。
- 主题默认 `SYSTEM`；网格和小地图默认开启；Token 模拟和属性面板折叠默认关闭。损坏 JSON、旧协议或非法字段由父页面恢复为这些默认值。
- `reset` 不枚举或清空其他用户键；登出也不删除偏好。
