"""R1 不变量探针（L15Checks R1_PROBE 同款，独立原件运行形态）：触发器中断后五表快照比对。

触发器逐字 = L12 l12_status_write_failure（purchase_approval_lab 内联同源）；
五表 = purchase_requests / stock / inventory_events / sales_orders / sales_order_lines。
"""
import json, sys, tempfile
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from flowerp.service import ERPService
from flowerp.store import ERPStore

TABLES = ("purchase_requests", "stock", "inventory_events", "sales_orders", "sales_order_lines")

def out(obj):
    print(json.dumps(obj, ensure_ascii=False))

with tempfile.TemporaryDirectory() as tmp:
    store = ERPStore(Path(tmp) / "flowerp.db")
    service = ERPService(store)
    service.add_product("SKU-R1-PROBE", "R1 探针产品", 100)
    service.receive_stock("SKU-R1-PROBE", 10, "opening")
    service.propose_purchase("SKU-R1-PROBE", 7, "R1 反馈改进验证", "PR-TARGET")
    service.approve_purchase("PR-TARGET", "TEACHING business reviewer")

    def snap():
        return {t: store.rows("SELECT * FROM %s ORDER BY 1" % t) for t in TABLES}

    before = snap()
    with store.connect() as conn:
        conn.execute("CREATE TRIGGER fail_target_status BEFORE UPDATE OF status ON purchase_requests WHEN NEW.id='PR-TARGET' AND NEW.status='received' BEGIN SELECT RAISE(ABORT,'TEACHING receipt status failure'); END")
    interrupted = False
    try:
        service.receive_purchase("PR-TARGET", "receipt:target")
    except Exception as exc:
        interrupted = True
    with store.connect() as conn:
        conn.execute("DROP TRIGGER fail_target_status")
    after = snap()
    out({"interrupted": interrupted, "invariant_ok": before == after})
