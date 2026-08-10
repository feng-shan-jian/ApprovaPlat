# DesignerSettingsDrawer

## 组件简介与作用

`DesignerSettingsDrawer` 编辑当前用户保存在 `wf_designer_preference` 中的正式偏好。组件维护可取消的本地草稿，但不会把草稿当成已保存配置；只有父页面真实 API 成功并回写 `preference` 后，设计器才采用新状态。

## 使用方式

```vue
<DesignerSettingsDrawer
  v-model="settingsVisible"
  :preference="designerPreference"
  :saving="preferenceSaving"
  @save="savePreference"
/>
```

## Props

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `modelValue` | `boolean` | 抽屉显示状态。 |
| `preference` | `object` | 主题、网格、小地图、Lint、Token 模拟和面板状态。 |
| `saving` | `boolean` | 真实后端保存加载状态。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `boolean` | 打开或关闭抽屉。 |
| `save` | `object` | 提交字段完整的偏好草稿。 |

## 公开方法

无。

## 关键设计思路

- 抽屉打开时总是从服务端回读状态重新创建草稿，取消不会改变已应用偏好。
- 不使用 `localStorage`、Cookie 或内存默认值冒充持久化结果。
- 父页面在 API 成功后回写偏好并关闭抽屉，失败时保留草稿供用户修正或重试。
