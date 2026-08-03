# OpenAI 官方资料本地快照清单

获取时间：2026-07-30（Asia/Shanghai）

这些文件来自 OpenAI 官方 GitHub 组织，并固定到具体 commit。`developers.openai.com` 的 Codex 手册和 Docs MCP 在当前网络出口均被 Vercel `sin1` 边缘节点返回 `403`，因此没有把受阻网页或错误响应保存为文档。

| 本地文件 | 官方来源 | 固定 commit | SHA-256 |
| --- | --- | --- | --- |
| `codex-app-server.md` | `openai/codex` 仓库的 `codex-rs/app-server/README.md` | `88ec932e96e4d18c5701664e726b1c8b18454af1` | `0d6026afd5b4b219fa43cf18a3ca819c4dbb86ce58678d4e056d8f99f06e2f1a` |
| `openai-agents-sdk.md` | `openai/openai-agents-python` 仓库的 `docs/index.md` | `992abf763d24881bab55663de6a93cf58f1c6118` | `5a04b1b1846a55f6c7151e587cef65d970bb82e3c82c59dfb8a800397311bb5e` |
| `openai-openapi.yaml` | `openai/openai-openapi` 仓库的 `openapi.yaml` | `db14b6e1712aaf5265cf5a6871adff7a9c61d31c` | `2196f2374732fa8c70e506f37ee0123a13447e467badbb7ea71b59ad32048397` |

固定链接：

- https://github.com/openai/codex/blob/88ec932e96e4d18c5701664e726b1c8b18454af1/codex-rs/app-server/README.md
- https://github.com/openai/openai-agents-python/blob/992abf763d24881bab55663de6a93cf58f1c6118/docs/index.md
- https://github.com/openai/openai-openapi/blob/db14b6e1712aaf5265cf5a6871adff7a9c61d31c/openapi.yaml

验证结果：

- 三个文件均完整位于工作区内。
- 两个 Markdown 文件可按 UTF-8 严格解码。
- OpenAPI 文件可由 YAML 解析器完整加载。
- OpenAPI 顶层版本为 `3.1.0`，标题为 `OpenAI API`。
- OpenAPI `paths` 中存在 `POST /responses`。

更新时必须重新记录 commit 和 SHA-256，不允许用浮动分支内容覆盖后仍保留旧清单。
