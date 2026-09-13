# 请求提交边界

`request.js` 的公共 Axios 调用方式不变。默认 POST、PUT 在 adapter 执行期间拒绝相同提交，完成后立即允许重试；页面仍可使用自己的提交态。业务幂等、权限、事务和 revision 冲突由后端负责。

- 防重状态只保存在当前模块的内存中，不写 sessionStorage、localStorage，也不打印请求正文或用于比较的值。模块加载时直接删除旧版 `sessionObj`，无需解析历史正文；存储被浏览器禁用时继续运行。
- 比较请求方法、包含 baseURL 和 params 的完整 URI、Content-Type、Authorization 和 Axios 转换后的正文。每个在途请求独立占位，A/B/A 中第二次 A 会被拒绝；拒绝重复请求不能释放第一个 A 的占位。
- multipart 固定字段顺序和值的快照，文件按 File/Blob 对象身份比较。同名同尺寸的不同文件可以并发；重新组装表单但复用同一个文件对象及相同字段仍受保护。此处不读取文件内容，也不承担跨文件对象的内容去重。
- 占位从 adapter 开始，成功、HTTP 失败、网络失败、取消和 adapter 异常均由该请求的 `finally` 释放。请求转换或预先取消发生于占位前，响应转换和业务错误发生于释放后，均不遗留锁。
- `headers.repeatSubmit: false` 保持原有绕过语义。原来的时间窗口和 5MB 缓存豁免已删除；前端没有 `headers.interval` 调用方。
- 鉴权、GET 参数映射、下载、稳定业务码和页面错误脱敏继续走原请求链；防重错误不携带原始请求配置。

## 验证

`tests/contracts/request-submission.test.js` 执行生产请求模块、真实 Axios 和实际密码/注册/解锁/OSS/SMS API，使用受控 deferred adapter 与合成凭据，不发送真实 HTTP。覆盖缓存清除、存储禁用、在途所有权、多文件、取消、转换异常、错误脱敏和下载行为。

```powershell
node --test tests/contracts/request-submission.test.js
npm run test:contracts
npm run lint
npm run build:prod
```
