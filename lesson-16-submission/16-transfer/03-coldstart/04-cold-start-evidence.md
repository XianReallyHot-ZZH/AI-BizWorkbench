# L16 冷启动证据（干净环境按文档命令链启动）

- 环境：git clone（本地路径，--branch lesson-16 @ 96bd601）+ submodule update --init（CodexFDE@406f7aa / flowERP@e0088d3 按 pin 落位）+ python3 -m venv .venv（3.13.5）——无作者会话残留、无 .runtime 携带。
- ①全量门：mvn test → Tests run: 281, Failures: 0, Errors: 0, BUILD SUCCESS（rc 0）——含 golden 六重放与 L07–L16 全部合同测试。
- ②只读自检：./bin/wb environment-check → ok:true，六面在场（java/compiled_classes/dependency_classpath/golden_resources/web_panel_assets/wb_wrapper），rc 0。
- ③服务冷启动：./bin/wb delivery-serve --runtime-dir .runtime/course/l16-coldstart --port 18017 → listening 127.0.0.1:18017（全新运行库，缺省 suite = 客户 purchase_requires_approval）。
- ④面板可见：GET / → HTTP 200（2967 字节，交付面板三件 serve 正常）；GET /api/v1/delivery/capabilities → 200（surface workbench / async_submission true）。
- 记忆迁移另见 02-backorder/06-chain.txt（L15 库文件副本 → 召回带引用命中 + 无关词如实为空）。
- 如实声明：「另一位同学」由用户一人分饰（干净克隆承载环境分离性，非真第二人）；克隆源为本地仓库路径（非跨机）。
