# RAG 检索质量评测工具

离线评测 Argus RAG 检索与问答质量的自动评测脚本（阶段 4 交付物）。

## 组成

| 文件/目录 | 说明 |
|---|---|
| `eval_docs/` | 评测语料：8 篇公开事实文档 + 2 篇机密文档（权限泄漏检测用） |
| `rag-eval-set.json` | 评测问题集 v1：56 条（事实 30 / 术语 8 / 跨段落 8 / 无答案 4 / 权限 6） |
| `run_eval.py` | 自动评测脚本（仅 Python 3 标准库） |
| `results/` | 评测结果 JSON（按时间戳归档） |

## 使用

```bash
# 前置：后端已启动（profile=local），MySQL/PG+pgvector/MinIO/ES 可用
python run_eval.py setup   # 注册评测账号、建群、上传文档、等待 ETL（幂等）
python run_eval.py eval    # 执行 56 条问题评测并输出指标
python run_eval.py all     # 一步到位
```

评测账号：`evaladmin / Eval@2026`（公开群）、`evalhr / Secret@2026`（机密群）。

## 指标

- **Retrieval Hit Rate**：期望文档出现在检索证据中的比例
- **MRR**：期望文档在证据分组中的排名倒数均值
- **Answer Keyword Accuracy**：答案包含全部期望关键词的比例（代理指标，存在措辞性低估）
- **Refusal Correct Rate**：无答案问题被正确拒答的比例
- **Permission Leak Rate**：机密文档泄漏到证据/引用中的比例（期望 0）
- **延迟**：P50 / P95 / 均值

基线结果见 `docs/二次开发-阶段4-RAG评测基线.md`。

## 扩展评测集

在 `rag-eval-set.json` 的 `questions` 数组中追加条目即可：

```json
{"id": 57, "category": "fact", "question": "…",
 "expected_docs": ["某文档.md"], "expect_keywords": ["关键词1", "关键词2"]}
```

`category` 取值：`fact` / `term` / `cross` / `unanswerable` / `permission`
（permission 类需附 `secret_docs` 指明机密文档，用于泄漏检测）。
