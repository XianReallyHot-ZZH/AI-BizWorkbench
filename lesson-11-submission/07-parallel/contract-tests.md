# L11 子任务合同·甲：测试补全（真实并行委派）

- 委派时间：2026-10-02；主 Agent：本会话（Claude）；载体：Claude Code Agent tool（general-purpose 子代理，fresh 上下文——不携带主会话历史，只持本合同）
- 共同输入（固定版本）：`.runtime/course/L11-candidate/`（净态拷贝树，git 起点封存 `53f77a8`；`flowerp/service.py` 字节级 == vendors/flowERP）
- 目标：把采购申请业务预期变成可重复执行的 unittest 断言——验证正常申请与五种非法输入拒绝
- 读集：`flowerp/service.py`、`flowerp/store.py`、`flowerp/models.py`（接口与数据结构约定）
- 写集（唯一）：`tests/test_l11_purchase.py`；运行数据一律 `tempfile.TemporaryDirectory`（独立临时库，不落拷贝树其它文件）
- 业务预期（共同依据，不得自行改写）：
  1. PR-TARGET（A×7，理由「低于补货点」带首尾空白、SKU 小写 `a` 输入）保存为 proposed：id=PR-TARGET、sku=A（规范化）、quantity=7、reason 去空白、approved_by 为空，可按编号再查
  2. 申请后库存三量 (on_hand, reserved, available)=(10,2,8)（走 `service.product()`）；库存流水、OTHER 订单、PR-OTHER 不变；purchase_requests 恰 +1
  3. 五拒绝：零数量/负数量/空白原因（ValueError）、未知 SKU（NotFound）、重复编号（IntegrityError——不是幂等成功）；拒绝后五表不变（purchase_requests/stock/inventory_events/sales_orders/sales_order_lines）
  4. 夹具：A 在库 10（`receive_stock("A",10,...)`）+ OTHER 订单预占 2；PR-OTHER 为固定夹具（SQL 直插，不经 propose——integration_lab 同形）
- 停止条件：需要修改实现/接口、超出读集、发现共享写入 → 交回主 Agent，不自行修改业务代码
- 验收标准：测试加载拷贝树真实服务（sys.path 指向拷贝树）；`python -m unittest` 实跑 `Ran N tests` N>0 全绿；含库存不变断言（premature 类副作用会使其失败）
- 交回：测试文件全文、实际命令与退出码、未覆盖项清单；开始/结束各留一条 `date -u` 时间戳
