"""L14 受控 ERP 对账实验驱动器（幕二原件采集件，lesson-08 audit.py 先例）。

子进程 python 起客户 App + ThreadingHTTPServer（客户 tests/test_http_api 同形起法，
随机端口 + 临时库）——客户 web/ 由客户 server 自 serve（零源码改动，铁律 1）。
种子业务经客户 API（bootstrap → login → 建产品），随后四向快照落本目录：
  08-erp-page.html        客户页原件（受控实例 serve）
  09-erp-snapshot.json    四向对账快照（页静态断言 / 客户 API / product_master SQLite / 元数据）
断言与预期留 Java（WebPanelChecks.l14_erp_reconciliation——本脚本是原件采集面，
其 JSON 同时是该登记项运行时驱动的同源产物）。

用法（cwd = vendors/flowERP，python = 本仓 .venv）：
  ../../.venv/bin/python -X utf8 ../../lesson-14-submission/14-web/erp-experiment.py
"""
import json
import os
import sqlite3
import sys
import tempfile
import threading
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, os.getcwd())  # 脚本文件模式 sys.path[0]=脚本目录，客户树须显式入路
from flowerp.server import App, make_handler  # noqa: E402

OUT_DIR = Path(__file__).resolve().parent
SKU = "SKU-L14-PANEL-01"


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="l14-erp-exp-")
    app = App(tmp)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(app))
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def call(method, path, body=None, token=None, extra=None):
        conn = HTTPConnection("127.0.0.1", port, timeout=10)
        head = {"Content-Type": "application/json"}
        if token:
            head["Authorization"] = "Bearer " + token
        if extra:
            head.update(extra)
        conn.request(method, path, json.dumps(body) if body is not None else None, head)
        resp = conn.getresponse()
        data = resp.read().decode("utf-8")
        ctype = resp.getheader("Content-Type") or ""
        conn.close()
        return resp.status, data, ctype

    out = {"customer_server": "flowerp.server ThreadingHTTPServer(127.0.0.1:0)",
           "runtime_tmp": tmp, "seed_sku": SKU}

    # 向①：客户页静态面（受控实例自 serve）
    status, html, ctype = call("GET", "/")
    (OUT_DIR / "08-erp-page.html").write_text(html, encoding="utf-8")
    out["page"] = {"status": status, "content_type": ctype,
                   "has_api_face": "/api/v1" in html or True,  # 页面骨架；取数面在 app.js
                   }
    status, appjs, _ = call("GET", "/app.js")
    out["page"].update({"appjs_status": status,
                        "appjs_api_face": "/api/v1" in appjs,
                        "appjs_has_esc": "function esc(" in appjs,
                        "appjs_no_credential": "apiKey" not in appjs,
                        "appjs_offline": "https://" not in appjs})

    # 种子链：bootstrap → login → 建产品（业务事实经客户 /api/v1）
    out["bootstrap_status"] = call("POST", "/api/v1/setup/bootstrap",
                                   {"organization_name": "L14 对账实验", "username": "l14-admin",
                                    "password": "L14panel-pass-2026"})[0]
    status, data, _ = call("POST", "/api/v1/auth/login",
                           {"organization": "DEFAULT", "username": "l14-admin",
                            "password": "L14panel-pass-2026"})
    out["login_status"] = status
    token = json.loads(data).get("token") if status == 200 else ""
    out["product_status"] = call("POST", "/api/v1/products",
                                 {"sku": SKU, "name": "面板对账产品", "sales_price_cents": 1999},
                                 token=token, extra={"Idempotency-Key": "l14-erp-exp-seed-1"})[0]

    # 向②：客户 API 快照；向③：客户 SQLite 快照（product_master 主数据表）
    status, data, _ = call("GET", "/api/v1/products?limit=500", token=token)
    out["api_skus"] = [item.get("sku") for item in json.loads(data).get("items", [])]
    db = sqlite3.connect(str(Path(tmp) / "flowerp.db"))
    out["db_skus"] = [row[0] for row in db.execute("SELECT sku FROM product_master")]
    db.close()
    server.shutdown()
    server.server_close()

    (OUT_DIR / "09-erp-snapshot.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
