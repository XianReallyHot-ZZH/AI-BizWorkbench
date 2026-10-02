/* 个人研发工作台——交付面板（L14）。
 * 取数面六面且仅六面（五个路径前缀——闭集经 WebPanelChecks 机检，新增数据源必须过合同）：
 * /api/v1/delivery/capabilities、/api/v1/delivery/requests（提交）、
 * /api/v1/delivery/views（列表与详情）、/api/v1/tasks/{id}/verify 与 /review（动作）。
 * 页面数据来自 API 而非静态假数据；动态文本统一经 esc()；无凭据、无 CDN。 */
"use strict";

function esc(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

async function api(path, options) {
  const response = await fetch(path, Object.assign({ cache: "no-store" }, options || {}));
  if (!response.ok && response.status !== 202) {
    let message = "HTTP " + response.status;
    try {
      const body = await response.json();
      if (body && body.message) { message = message + "：" + body.message; }
    } catch (parseError) { /* 非 JSON 错误体保留状态码信息 */ }
    throw new Error(message);
  }
  return response.json();
}

const state = { capabilities: null, list: null, currentId: null };

/* ---- 身份区 / 合同区：capabilities ---- */
async function loadCapabilities() {
  const node = document.getElementById("capabilities");
  try {
    const caps = await api("/api/v1/delivery/capabilities");
    state.capabilities = caps;
    document.getElementById("cap-execution-modes").textContent =
      "执行模式：" + (caps.execution_modes || []).join("、");
    document.getElementById("cap-idempotency").textContent =
      caps.requires_idempotency_key ? "提交必须携带幂等键（受理可安全重试）" : "无幂等键要求";
    node.textContent = "工作台服务在线（受理异步、全绿停人审）。";
  } catch (error) {
    node.textContent = "工作台能力查询失败：" + error.message;
  }
}

/* ---- 任务列表区：views 列表 + 汇总 ---- */
async function refreshList() {
  const summaryNode = document.getElementById("task-summary");
  const listNode = document.getElementById("task-list");
  listNode.textContent = "加载中……";
  let body;
  try {
    body = await api("/api/v1/delivery/views?limit=20");
  } catch (error) {
    summaryNode.textContent = "";
    listNode.textContent = "加载失败：" + error.message;
    return;
  }
  state.list = body;
  const s = body.summary || {};
  summaryNode.innerHTML =
    "<span>共 " + esc(s.total) + "</span>" +
    "<span>进行中 " + esc(s.active) + "</span>" +
    "<span>等人审 " + esc(s.review) + "</span>" +
    "<span>已完成 " + esc(s.completed) + "</span>" +
    "<span>返工 " + esc(s.rework) + "</span>" +
    "<span>失败 " + esc(s.failed) + "</span>" +
    "<span>未知 " + esc(s.unknown) + "</span>" +
    "<span>待审反馈 " + esc(s.pending_feedback) + "</span>";
  const items = body.items || [];
  if (!items.length) {
    listNode.textContent = "暂无任务——从上方提交第一条需求。";
    return;
  }
  listNode.innerHTML = items.map(function (item) {
    const status = item.status || {};
    const owner = status.owner || {};
    const selected = item.task_id === state.currentId ? " selected" : "";
    return '<button type="button" class="task-card' + selected + '" data-task="' + esc(item.task_id) + '">' +
      '<span class="task-status status-' + esc(status.code) + '">' + esc(status.code) + "</span>" +
      '<span class="task-owner">' + esc(owner.label || owner.id || "") + "</span>" +
      "<span class=\"task-request\">" + esc((item.request || "").slice(0, 60)) + "</span>" +
      '<span class="task-next">' + esc(status.next_action || "") + "</span></button>";
  }).join("");
  Array.prototype.forEach.call(listNode.querySelectorAll(".task-card"), function (card) {
    card.addEventListener("click", function () { selectTask(card.getAttribute("data-task")); });
  });
}

/* ---- 详情区：view 下钻（事件链 + Eval 证据 + 完整性 + 动作） ---- */
async function selectTask(taskId) {
  state.currentId = taskId;
  document.getElementById("detail-empty").style.display = "none";
  const body = document.getElementById("detail-body");
  body.textContent = "加载中……";
  let view;
  try {
    view = await api("/api/v1/delivery/views/" + encodeURIComponent(taskId));
  } catch (error) {
    body.textContent = "加载失败：" + error.message;
    return;
  }
  const status = view.status || {};
  const owner = status.owner || {};
  const freshness = view.freshness || {};
  const evaluation = view.eval || {};
  const integrity = view.integrity || {};
  const review = view.review || {};
  const feedback = view.feedback || {};
  const actions = view.allowed_actions || [];

  let html = '<div class="detail-head">';
  html += '<span class="task-status status-' + esc(status.code) + '">' + esc(status.code) + "</span>";
  if (status.label) { html += '<span class="detail-label">' + esc(status.label) + "</span>"; }
  html += "</div>";
  html += "<dl>";
  html += "<dt>归属人</dt><dd>" + esc(owner.label || "") + "（" + esc(owner.id || "") + "）</dd>";
  html += "<dt>下一步</dt><dd>" + esc(status.next_action || "") + "</dd>";
  const ageText = freshness.age_seconds == null ? "未知" : freshness.age_seconds + "s";
  html += "<dt>新鲜度</dt><dd>" + esc(freshness.state || "") + "（age " + esc(ageText) + "）</dd>";
  html += "<dt>需求</dt><dd>" + esc(view.request || "") + "</dd>";
  html += "<dt>业务引用</dt><dd>" + esc((view.business_refs || []).join("、") || "无") + "</dd>";
  html += "<dt>Eval</dt><dd>" + esc(evaluation.decision == null ? "尚无报告" : evaluation.decision) +
    "（blocking 失败 " + esc(evaluation.blocking_failed) + "）</dd>";
  if (evaluation.report_path) {
    html += "<dt>报告</dt><dd>" + esc(evaluation.report_path) +
      "<br><code>" + esc(evaluation.report_sha256 || "") + "</code></dd>";
  }
  html += "<dt>审核</dt><dd>" + (review.reviewed_by
    ? esc(review.reviewed_by) + "：" + esc(review.decision) + "（" + esc(review.reviewed_at || "") + "）"
    : (review.required ? "等待具名人工审核" : "未到审核")) + "</dd>";
  html += "<dt>反馈</dt><dd>共 " + esc(feedback.total) + "，待审 " + esc(feedback.pending_review) + "</dd>";
  html += "<dt>完整性</dt><dd class=\"" + (integrity.truthful ? "truthful" : "untruthful") + "\">" +
    (integrity.truthful ? "诚实（无问题标记）" : "存在问题：" + esc((integrity.issues || []).join("、"))) + "</dd>";
  html += "</dl>";

  html += "<h3>事件链</h3><ol class=\"events\">";
  (view.events || []).forEach(function (event) {
    const eventStatus = event.status || {};
    html += "<li><span class=\"event-status status-" + esc(event.to_status) + "\">" +
      esc(event.to_status) + "</span> " + esc(event.detail || "") +
      " <span class=\"event-actor\">@" + esc(event.actor || "") + "</span></li>";
  });
  html += "</ol>";

  html += "<div class=\"actions\">";
  if (actions.indexOf("approve") >= 0 || actions.indexOf("reject") >= 0) {
    html += "<h3>具名终审</h3>";
    html += "<label>审核人<input type=\"text\" id=\"review-reviewer\" required maxlength=\"80\"></label>";
    html += "<label>理由<input type=\"text\" id=\"review-note\" required></label>";
    html += "<button type=\"button\" id=\"approve-button\" class=\"primary\">批准完成</button> ";
    html += "<button type=\"button\" id=\"reject-button\" class=\"danger\">打回返工</button>";
  }
  if (actions.indexOf("run") >= 0) {
    html += "<h3>复验</h3>";
    html += "<label>复验人<input type=\"text\" id=\"verify-actor\" required maxlength=\"80\"></label>";
    html += "<button type=\"button\" id=\"verify-button\">重新复验</button>";
  }
  html += "</div>";
  html += "<p id=\"detail-result\" class=\"form-result\" aria-live=\"polite\"></p>";
  body.innerHTML = html;

  const result = document.getElementById("detail-result");
  if (document.getElementById("approve-button")) {
    document.getElementById("approve-button").addEventListener("click", function () {
      reviewTask(taskId, "approve", result);
    });
    document.getElementById("reject-button").addEventListener("click", function () {
      reviewTask(taskId, "reject", result);
    });
  }
  if (document.getElementById("verify-button")) {
    document.getElementById("verify-button").addEventListener("click", function () {
      verifyAgain(taskId, result);
    });
  }
}

async function reviewTask(taskId, decision, resultNode) {
  const reviewer = document.getElementById("review-reviewer").value;
  const note = document.getElementById("review-note").value;
  try {
    await api("/api/v1/tasks/" + encodeURIComponent(taskId) + "/review", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reviewer: reviewer, decision: decision, note: note })
    });
    resultNode.textContent = "已" + (decision === "approve" ? "批准完成" : "打回返工") + "。";
  } catch (error) {
    resultNode.textContent = "操作失败：" + error.message;
  }
  await refreshList();
  await selectTask(taskId);
}

async function verifyAgain(taskId, resultNode) {
  const actor = document.getElementById("verify-actor").value;
  try {
    await api("/api/v1/tasks/" + encodeURIComponent(taskId) + "/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: actor })
    });
    resultNode.textContent = "复验已跑完，状态已刷新。";
  } catch (error) {
    resultNode.textContent = "复验失败：" + error.message;
  }
  await refreshList();
  await selectTask(taskId);
}

/* ---- 提交区：提交并复验（Idempotency-Key 承载幂等键） ---- */
document.getElementById("submit-form").addEventListener("submit", async function (event) {
  event.preventDefault();
  const result = document.getElementById("submit-result");
  const request = document.getElementById("submit-request").value;
  const actor = document.getElementById("submit-actor").value;
  const refsRaw = document.getElementById("submit-refs").value;
  const refs = refsRaw.split(",").map(function (item) { return item.trim(); })
    .filter(function (item) { return item.length > 0; });
  if (request.length > 1000) { result.textContent = "需求不能超过 1000 个字符。"; return; }
  try {
    const accepted = await api("/api/v1/delivery/requests", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": "panel-" + Date.now() },
      body: JSON.stringify({ lesson: 14, request: request, actor: actor, business_refs: refs })
    });
    result.textContent = "已受理（202，任务 " + accepted.task_id + "）——受理不是完成，进度见列表。";
    state.currentId = accepted.task_id;
  } catch (error) {
    result.textContent = "提交失败：" + error.message;
  }
  await refreshList();
  await selectTask(state.currentId);
});

document.getElementById("refresh-button").addEventListener("click", async function () {
  await refreshList();
  if (state.currentId) { await selectTask(state.currentId); }
});

/* ---- 启动 ---- */
(async function init() {
  await loadCapabilities();
  await refreshList();
})();
