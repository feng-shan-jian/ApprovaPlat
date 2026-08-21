# WorkflowAttachmentStorage

## 作用

`WorkflowAttachmentStorage` 负责工作流附件的私有物理存储。业务层只使用对象键、可信摘要和服务端计算的文件元数据，不直接拼接物理路径。

## 存储布局

私有根目录为 `${ruoyi.profile}/workflow-attachments`。对象键固定为 `yyyy/MM/dd/<32位小写十六进制>[.扩展名]`，原始文件名只用于展示和 MIME 辅助探测。临时上传文件位于私有根下的 `.tmp`，启动探针从该目录优先原子移动到当前 UTC 日期目录；文件系统不支持原子移动时使用普通同卷移动，随后回读并删除。

## 公共方法

| 方法 | 作用 |
| --- | --- |
| `store(MultipartFile)` | 先校验私有根，再流式写入临时文件，同时计算实际大小、SHA-256 和 MIME；优先使用 `ATOMIC_MOVE`，不支持时使用普通同卷移动，成功后回读校验并返回 `StoredAttachmentFile`。 |
| `openVerifiedForRead(storageKey, expectedSize, expectedSha256)` | 使用同一个 `NOFOLLOW_LINKS` 可定位只读通道读取、校验大小和 SHA-256，校验成功后复位位置并把同一通道包装为流。 |
| `verify(storageKey, expectedSize, expectedSha256)` | 重新校验物理正文与数据库元数据一致。 |
| `delete(storageKey)` | 对受控普通文件执行幂等删除。 |
| `usableSpace()` | 只读取私有根所在文件系统的可用空间，不创建文件。 |
| `verifyRuntimeReadiness(expectedStorageId, minFreeBytes)` | 应用启动时执行一次真实写入、跨目录移动、受控回读、清理和容量检查。 |

## 安全约束

- storageKey 严格匹配固定格式并经过私有根边界校验；所有目录和文件属性读取使用 `NOFOLLOW_LINKS`。
- 私有根、临时目录和日期目录必须是普通目录；创建或写入这些附件目录时应用 POSIX 私有权限，profile 祖先目录不修改权限。
- 上传前私有根必须已经存在且当前路径仍是普通目录，业务上传不会静默重建丢失的私有根；实现只承诺当前路径边界校验，不承诺跨进程目录替换检测。
- 上传大小由服务端实际读取字节限制；摘要始终由服务端计算，客户端 Content-Type 不作为可信元数据。
- 下载校验和响应读取共享同一打开通道，避免校验后文件被替换。
- `.storage-id` 必须是预置普通文件，大小受限，通过 `NOFOLLOW_LINKS` 通道流式读取，并且内容是稳定 ASCII 标识。
- 移动优先使用 `ATOMIC_MOVE`；仅在文件系统支持时具备原子语义，不支持时回退到普通同卷移动，发布后必须回读校验。
- 失败只清理当前操作拥有的文件；readiness 会在成功和失败路径同时尝试清理 `.tmp` 与日期目录探针文件，清理异常作为主异常的 suppressed 信息保留。
- 运行中指标只反映附件文件系统的可用空间；真实可写、移动、回读和清理能力由应用启动时 readiness 探针确认，不由周期性指标写探针保证。

附件记录的数据库状态和清理状态机仍由 `WorkflowAttachmentService` 管理；本组件只负责真实文件的安全读写和删除。
