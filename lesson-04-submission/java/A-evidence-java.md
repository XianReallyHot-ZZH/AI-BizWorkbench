# A-evidence-java（A 票：V0 受控执行建设，commit 2）

> 上游手册第 3 步字段体例（Python 时代 `A-evidence.md` 同形）；移植注记见 [L04-受控执行.md 附录 A](../../docs/lessons/L04-受控执行.md)。链三封条（同命令 `mvn test`，observed_at 严格递增）：red `20260929T124639108663Z-648136ff`（84 跑 22 红，rc 1）→ diff `20260929T131844422273Z-3d283277`（rc 0，暂存区全量 `git diff --cached`——新增件为主，Python 时代台账 #4 同口径如实披露）→ green `20260929T131849126124Z-34bbc6d0`（rc 0，**84/84**）。

## 字段

- **执行者**：Claude（本会话），实现授权 = 用户显式调用 `/mattpocock-skills:implement`（2026-09-29，配合点 2）
- **修改前缺口**：Java 线无受控执行能力——`workbench-task-run/-review/-show` invalid-choice（rc 2）；V0 行为面 21 断言落空（写集正反、五情境、失败/超时/launch-error 保真、review 门、自验拒绝、C9 正面、S-c1 摘要复核）；golden l04 47 场景重放红（s06 退出码 2≠0）
- **修改文件**：新增 `src/main/java/workbench/execution/`（ExecutionCommands / Shlex / ProcessRunner / WriteScope / WorkspaceInspector 五件）；`Main.java` 注册缝 +1；`Args` 可重复取值选项扩展（--write-scope nargs="+" 同形）；`PyJson.dumpsCompact`（DB JSON 列紧凑形，Python json.dumps 默认分隔符同形）；`Ledger` 读侧可见度放寬（latestExecution/latestReview/taskRow/EXECUTION_COMPLETE_STATUSES/connection——Python execution.py import bootstrap 单一来源的同形，零新存储抽象）
- **验证命令与退出码**：`mvn test` 全量 84/84（rc 0；红转绿过程中单类跑 3 轮，全量 2 轮）；采红前预检同形状（25 跑 22 红核对口径）
- **Diff 摘要**：+836/-6（10 文件，`git diff --cached` 全量在 diff 封条内）；Python 冻结实现面零触碰（`workbench/ tests/ pyproject.toml tools/capture_evidence.py` 零 diff——本 commit 改的 `src/test/` 是 Java 线写集）；pom.xml 零改动
- **越界失败超时处理**：合同测试逐类断言（out_of_scope eval 键缺位 + 不自动回滚；failed/timeout 双流与 Diff 保留；launch error 落 failed 记录）——替身 sh 进程驱动；golden l04 对应场景字节锁定
- **仍未证明事项 / 替身执行器证明边界（D7）**：合同测试证明 V0 机制（写集正反、记录保真、状态门、自验拒绝、前置绑定），**不证明真实 `claude -p` 行为**——后者由 Ticket B 受控执行实跑留证；launch error 的 errno 词面系语言绑定（Java 异常类名 ≠ Python FileNotFoundError），按 JD6 只断 JSON 形状 + rc，不伪装同形

## 红绿间测试修正披露（无断言放宽）

1. `codeRunRecordsManifestDiffAndStopsAtReview` 的 stdout 期望：初版按直觉写 `"created\n"`，实测红——冻结 Python 实测为 `"createdn"`（反斜杠在未引号 shell 词内转义 `n`，golden s06 同值）→ 期望改为冻结实测值（独立真值源，L03-java S-1 体例）。
2. `change_manifest_sha256` 键位：初版断言在 task-run 出账载荷，实测缺键——冻结 golden s06 证实该 sha 只入库、task-run 不出账，出账在 task-show/status 的 DB 投影（S-c1 摘要复核的入账位）→ 断言迁至 `taskShowSummarizesExecutionsAndReviews` 的投影侧并补 `change_manifest_json` 在场断言。

两处均为"期望对齐冻结实测"，不是放宽：断言对象未变，真值源从假设改为冻结 Python 行为。
