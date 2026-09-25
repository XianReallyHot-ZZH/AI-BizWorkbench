# AI-BizWorkbench 复刻域

本仓库是 CodexFDE 的渐进式复刻：以 `vendors/` 下的参考仓库为对照，重建一个属于自己的、日常可用的个人研发工作台。本文件是复刻域的词汇表，只定义概念，不记录实现。

## Language

### 复刻与上游

**复刻 (Replication)**:
以参考仓库为对照、逐讲重建个人研发工作台的过程。复刻的是方法与能力，不是克隆参考仓库的终态。
_Avoid_: 跟跑、抄袭、fork

**上游 (Upstream)**:
`vendors/` 下的两个 submodule 参考仓库（CodexFDE 与 flowERP）。只读对照物，复刻产物从不写回上游。
_Avoid_: 原仓库、参考实现（参考实现特指上游里的代码，可保留）

**冻结基线 (Frozen baseline)**:
复刻所对照的上游合同快照版本。上游后续变更不自动生效，进入复刻需经显式采纳。
_Avoid_: 同步点

**检查点 (Checkpoint)**:
阶段边界上显式升级上游、比对合同差异、重评被推迟决策的固定时机。
_Avoid_: 同步、更新、升级日

**显式采纳 (Explicit adoption)**:
把上游的某项变更吸收进冻结基线的决定，须留下记录；未采纳的变更保持冻结。
_Avoid_: 合并、跟进

### 推进结构

**讲 (Lesson)**:
复刻的推进切片单位（L00-L16），与课程讲次对齐；每讲绑定一组合同验收项。
_Avoid_: 课、章节

**阶段 (Stage)**:
多个讲组成的里程碑，带退出门槛；阶段边界即检查点。
_Avoid_: 周期、迭代

**主线 (Mainline)**:
工作台能力逐讲增长的那条推进线。
_Avoid_: 主分支（那叫 master）

**支线 (Side branch)**:
记忆系统最小闭环的推进线，在主线 L04 完成后串行插入一次。
_Avoid_: 并行线

**自举 (Bootstrap)**:
L01-L04 期间用"直接指挥 AI"的方式造出工作台 V0，L04 起换挡为"通过工作台协同"的过渡期。

### 交付与验收

**候选分支 (Candidate branch)**:
每讲一个的 `lesson-NN` 分支，承载本讲起始红与实现，经具名验收后合入 master。
_Avoid_: feature 分支、工作分支

**起始红 (Starting red)**:
每讲最先落地的失败合同测试；红转绿是本讲完成的机器证据。
_Avoid_: 红灯测试

**具名验收 (Named acceptance)**:
用户对候选的显式接受决定，记录在合并里。没有它，一切绿灯只构成待审核。
_Avoid_: approve、sign-off

**待审核 ≠ 已接受**:
候选通过全部门槛也只是待审核状态，直到具名验收。此短语逐字使用。

**证据 (Evidence)**:
前红、范围内 Diff、后绿、退出码、失败后权威状态的机器可验证记录。AI 自述与截图不算证据。
_Avoid_: 记录（太泛）、日志

**合同 fixture (Contract fixture)**:
vendor 进本仓库的上游 16 讲合同数据，作为每讲 TDD 的测试起点与验收清单来源。
_Avoid_: 测试基线、考纲

**验收门 (Gate)**:
候选合入前必须通过的检查集合：合同测试、blocking eval、证据完整性。
_Avoid_: 检查项

**blocking / observing**:
blocking 失败即整体失败并给出非零退出码；observing 只记录不拦截。

### 工作台与客户

**工作台 (Workbench)**:
复刻的目标产物：Harness + 记忆系统 + 工作流蒸馏（三件套），组织人与 AI 协同开发客户项目。

**客户项目 (Client project)**:
工作台通过进程边界驱动的独立仓库（现为 vendors/flowERP）；工作台不在进程内导入它。
_Avoid_: 客户仓库以外的"业务代码"泛称

**客户真理 (Client truth)**:
客户仓库自己的 eval 结论是其业务状态的唯一权威；工作台绿灯不证明客户业务通过。
_Avoid_: ERP 绿灯

**执行器 (Executor)**:
受工作台合同约束、代为修改代码的 AI CLI。本仓库现为 Claude Code。
_Avoid_: Agent（保留给课程里的 agent/ 模块概念）、模型
