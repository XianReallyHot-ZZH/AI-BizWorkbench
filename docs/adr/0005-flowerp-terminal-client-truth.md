---
status: accepted
date: 2026-09-25
---

# 0005 - flowERP 终态即客户真理，不重交付 ERP 增量

L05-L16 课程里的"ERP 增量"（幂等入库、原子预占、审批入库等）在 vendors/flowERP 终态中已全部实现，且该仓库没有逐讲 tag。本决定：**vendors/flowERP 终态就是客户真理**——L04 起工作台登记并驱动它（进程边界、`FLOWERP_PROJECT_ROOT`/登记表），跑它自己的 19 项 blocking eval 作为客户侧结论；课程 ERP 增量不再重做，改读作"验证工作台能力时委托给哪个 flowERP 场景"；这 19 项归客户侧，**不进**工作台默认 blocking 集（照抄 PROJECT_CASES 隔离机制）。

**Why**: 复刻的终点是工作台（成功标准 A1）；在无逐讲标签的仓库里逐特性剥离重交付等于手工考古，功夫花在 ERP 而非工作台上；工作台绿灯 ≠ 客户业务通过正是课程要教的责任边界，照抄隔离机制即已复刻该教学点。

**Considered**: (a) fork flowERP 剥离特性重交付——保真但脆弱；(b) 自建替代客户项目——毁掉对照复刻。

**Consequences**: 未来换自己的客户项目时，进程边界天然支持；flowERP 自身更新同样走 ADR-0003 的检查点升级。
