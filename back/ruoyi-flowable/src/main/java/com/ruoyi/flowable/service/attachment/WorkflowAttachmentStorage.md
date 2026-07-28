# WorkflowAttachmentStorage

## 作用

`WorkflowAttachmentStorage` 封装工作流附件的私有磁盘存储。业务代码只能通过它写入、解析和删除文件，不能拼接 `${ruoyi.profile}` 或把物理路径返回客户端。

## 存储布局

正式根目录为 `${ruoyi.profile}/workflow-attachments`。对象键由服务端生成，格式为日期目录、随机十六进制文件名和受控扩展名；原始文件名不参与路径。所有路径在读写前都会规范化并核对仍位于私有根目录内。

支持的公开操作：

| 方法 | 作用 |
| --- | --- |
| `store(MultipartFile)` | 流式写入文件，同时计算实际大小、MIME 和 SHA-256 |
| `openVerifiedForRead(storageKey, expectedSize, expectedSha256)` | 在同一文件通道校验路径、大小和 SHA-256，复位后返回一次性只读流 |
| `verify(storageKey, expectedSize, expectedSha256)` | 在流程发起或任务完成的绑定事务内重新核对完整物理正文 |
| `delete(storageKey)` | 幂等删除受控对象；文件已不存在按成功处理 |
| `usableSpace()` | 在固定私有根身份校验后读取附件卷当前可用字节数 |

## 安全约束

- 上传大小以服务端实际读取字节为准，不能信任客户端 `Content-Length`。
- 文件名会去除路径、控制字符和危险尾部字符，并限制为 255 字符。
- MIME 由服务端探测并规范化，客户端声明只作为受控回退信息。
- SHA-256 在写入过程中计算，数据库只接受 64 位小写十六进制摘要。
- 下载校验与响应读取复用同一个已打开通道，不把 `Path` 交给 Controller 二次打开。
- 构造阶段逐级创建并固定私有根真实路径和目录身份；每次读、写、删及容量查询都会重新核对根未被替换。
- 私有根到日期父目录的每一级都拒绝符号链接、junction 和特殊目录，并在操作前后复核目录身份。
- 文件系统支持 `SecureDirectoryStream` 时使用可信目录句柄相对打开、移动和删除；不支持时只有在前后身份链可证明未变化时才允许完成操作。
- 最终文件必须是拒绝符号链接的普通文件；真实路径必须仍位于固定私有存储根目录。
- 大小和 SHA-256 任一不一致都会拒绝下载或绑定，同长度正文替换不能绕过校验。
- 支持 POSIX 的系统会把目录收紧为仅所有者可读写执行、文件收紧为仅所有者可读写。
- 临时文件写入失败、元数据插入失败或上传事务回滚时都会补偿删除，不保留孤立文件。

删除 `EXPIRED` / `DELETED` 文件后，由 `WorkflowAttachmentService` 单独记录 `storage_deleted_time`；物理删除失败保留空值，定时清理可安全重试。
