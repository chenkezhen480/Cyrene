# Cyrene 实验结果

以下统计包含失败任务；Token 均值仅纳入全部模型调用都有对应 API 用量的任务。缺失或部分用量保持未知，不按零计算。p50/p95 使用观测样本的最近秩。

| 条件 | 通过/总数 | 成功率 | 平均耗时 s | p95 s | LLM 输入 Token 均值 | LLM 输出 Token 均值 |
|---|---:|---:|---:|---:|---:|---:|
| bare_langchain4j | 16/16 | 100.0% | 4.4 | 11.9 | 12402.1 | 539.9 |
| wiki_off | 0/16 | 0.0% | 0.4 | 1.2 | 未观测 | 未观测 |
| wiki_on | 0/16 | 0.0% | 0.3 | 1.5 | 未观测 | 未观测 |

## 用量覆盖与失败样本

- bare_langchain4j：输入完整 16/16、部分 0、未知 0；输出完整 16/16、部分 0、未知 0；失败任务：无。
- wiki_off：输入完整 0/16、部分 0、未知 16；输出完整 0/16、部分 0、未知 16；失败任务：business-create-and-complete, business-create-task, business-idempotent-create, business-read-combined-filter, business-read-customer-detail, business-read-renewal-list, business-read-task-list, business-update-existing-task, denied-cross-owner-read, denied-cross-owner-update, knowledge-cross-document-hold, knowledge-current-discount, knowledge-current-renewal, knowledge-support-response, missing-create-information, mixed-policy-business-actions。
- wiki_on：输入完整 0/16、部分 0、未知 16；输出完整 0/16、部分 0、未知 16；失败任务：business-create-and-complete, business-create-task, business-idempotent-create, business-read-combined-filter, business-read-customer-detail, business-read-renewal-list, business-read-task-list, business-update-existing-task, denied-cross-owner-read, denied-cross-owner-update, knowledge-cross-document-hold, knowledge-current-discount, knowledge-current-renewal, knowledge-support-response, missing-create-information, mixed-policy-business-actions。

## 知识任务与知识结合业务任务

业务读写题不一定调用知识检索，Wiki 用量判断应单独看以下题类。

| 条件 | 题类 | 通过/总数 | 平均耗时 s | LLM 输入 Token 均值 | 输入完整观测 |
|---|---|---:|---:|---:|---:|
| bare_langchain4j | knowledge | 4/4 | 5.2 | 16530.8 | 4/4 |
| bare_langchain4j | mixed | 1/1 | 11.9 | 37736.0 | 1/1 |
| wiki_off | knowledge | 0/4 | 0.6 | 未观测 | 0/4 |
| wiki_off | mixed | 0/1 | 0.2 | 未观测 | 0/1 |
| wiki_on | knowledge | 0/4 | 0.5 | 未观测 | 0/4 |
| wiki_on | mixed | 0/1 | 0.3 | 未观测 | 0/1 |

## 解释边界

- 这是合成制度和受控宿主的预实验；有限任务和重复次数不支持生产泛化或同类项目胜负结论。
- 本轮没有 Jev 独立速度实验，也未把官方速度数据计为 Cyrene 实测。
- Token 来自逐次 API 用量；总量仅在输入、输出全部观测时相加。框架缺省总量零值不作为 API 观测。没有可核验价格或账单时不换算金额。
- LLM 用量与 Embedding/重排用量分别看待；缺失后者时不能据此宣称整个知识系统的总费用下降。
- 知识摄取与基础设施成本另见 setup.json；若未观测摄取用量，不计为零。
