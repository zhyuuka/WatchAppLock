/* ============================================================
   app.js — WatchAppLock 设置页逻辑
   与 JsBridge(暴露为 window.Bridge)双向通信
   使用 Minimal Dashboard 官方组件 class（btn primary/secondary/ghost/danger）
   ============================================================ */
(function () {
  "use strict";

  var WAL = (window.__wal = window.__wal || {});
  var B = window.Bridge;
  var view = document.getElementById("view");
  var currentView = "apps";
  var installedCache = null;
  var iconCache = {};

  /* ---------------- SVG 图标加载 ---------------- */
  // 预加载常用图标，注入 [data-icon] 节点
  function loadIcon(name) {
    if (iconCache[name]) return Promise.resolve(iconCache[name]);
    return fetch("icons/" + name + ".svg")
      .then(function (r) { return r.ok ? r.text() : ""; })
      .then(function (t) { iconCache[name] = t; return t; })
      .catch(function () { iconCache[name] = ""; return ""; });
  }

  function injectIcons(root) {
    var nodes = (root || document).querySelectorAll("[data-icon]");
    var pending = [];
    nodes.forEach(function (n) {
      var name = n.getAttribute("data-icon");
      if (!name) return;
      if (iconCache[name] != null) {
        n.innerHTML = '<span class="icon">' + iconCache[name] + "</span>";
      } else {
        pending.push(loadIcon(name).then(function (t) {
          n.innerHTML = '<span class="icon">' + t + "</span>";
        }));
      }
    });
    return Promise.all(pending);
  }

  // 启动时预加载导航图标
  ["box", "circle-check", "circle-play", "circle-question-mark",
   "circle-alert", "check", "external-link", "pen-line", "trash-2",
   "triangle-alert", "user", "tag"].forEach(function (n) { loadIcon(n); });

  /* ---------------- 视口注入 ---------------- */
  window.updateViewport = function (d) {
    var r = document.documentElement.style;
    if (!d) return;
    if (d.shape) {
      r.setProperty("--screen-shape", d.shape);
      document.documentElement.setAttribute("data-shape", d.shape);
    }
    if (d.w) r.setProperty("--viewport-w", d.w);
    if (d.h) r.setProperty("--viewport-h", d.h);
    if (typeof d.top === "number") r.setProperty("--safe-inset-top", d.top + "px");
    if (typeof d.bottom === "number") r.setProperty("--safe-inset-bottom", d.bottom + "px");
    if (typeof d.left === "number") r.setProperty("--safe-inset-left", d.left + "px");
    if (typeof d.right === "number") r.setProperty("--safe-inset-right", d.right + "px");
  };

  /* ---------------- 生命周期回调 ---------------- */
  WAL.onReady = function () { renderAll(); refreshServiceStatus(); };
  WAL.onResume = function () { renderAll(); refreshServiceStatus(); };
  WAL.onPrefChanged = function (what) {
    if (currentView === "apps" && (what === "lockedApps")) renderApps();
    if (currentView === "lock" && (what === "lockMode" || what === "pin")) renderLock();
    if (currentView === "keep" && (what === "keepAlive" || what === "autoStart")) renderKeep();
    if (currentView === "about" && (what === "devMode" || what === "antiUninstall" || what === "fakeCrash")) renderAbout();
    refreshServiceStatus();
  };

  /* ---------------- 导航 ---------------- */
  var nav = document.getElementById("nav");
  nav.addEventListener("click", function (e) {
    var item = e.target.closest(".navbar__item");
    if (!item) return;
    Array.prototype.forEach.call(nav.children, function (c) { c.classList.remove("is-active"); });
    item.classList.add("is-active");
    currentView = item.dataset.view;
    renderView();
  });

  function renderView() {
    switch (currentView) {
      case "apps": renderApps(); break;
      case "lock": renderLock(); break;
      case "keep": renderKeep(); break;
      case "about": renderAbout(); break;
    }
  }

  function renderAll() {
    renderView();
    injectIcons(document);
  }

  /* ---------------- 工具 ---------------- */
  function el(tag, cls, html) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html != null) n.innerHTML = html;
    return n;
  }
  function parseJSON(s, fallback) {
    try { return JSON.parse(s); } catch (e) { return fallback; }
  }
  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c];
    });
  }
  function initial(name) { return (name || "?").trim().charAt(0).toUpperCase(); }

  /* ============ 视图：已锁应用 ============ */
  function renderApps() {
    view.innerHTML = "";
    view.appendChild(el("div", "section-head", "已锁应用"));

    var locked = parseJSON(B.getLockedApps(), []);
    if (!locked.length) {
      view.appendChild(el("div", "card", '<div class="card__title">暂无锁定应用</div>' +
        '<p class="card__desc">点击下方添加，选择需要锁定的第三方应用。</p>'));
    } else {
      var list = el("div", "list");
      locked.forEach(function (pkg) {
        var label = B.getAppLabel(pkg);
        var row = el("div", "list__row");
        var iconBox = el("div", "list__icon", escapeHtml(initial(label)));
        row.appendChild(iconBox);
        var main = el("div", "list__main");
        main.appendChild(el("div", "list__label", escapeHtml(label)));
        main.appendChild(el("div", "list__sub truncate", escapeHtml(pkg)));
        row.appendChild(main);
        var aside = el("div", "list__aside");
        aside.innerHTML = switchHtml("lk_" + pkg, true);
        row.appendChild(aside);
        list.appendChild(row);
        aside.querySelector("input").addEventListener("change", function (e) {
          B.toggleLock(pkg, e.target.checked);
        });
      });
      view.appendChild(list);
    }

    var add = el("button", "btn primary block", "＋ 添加应用");
    add.style.marginTop = "12px";
    add.addEventListener("click", openAppSheet);
    view.appendChild(add);
  }

  /* ============ 应用选择面板 ============ */
  function openAppSheet() {
    var box = document.getElementById("appList");
    box.innerHTML = '<div class="spinner"></div>';
    openSheet("sheetApps");
    setTimeout(function () {
      installedCache = parseJSON(B.getInstalledApps(), []);
      var locked = parseJSON(B.getLockedApps(), []);
      var lockedSet = {};
      locked.forEach(function (p) { lockedSet[p] = true; });
      box.innerHTML = "";
      if (!installedCache.length) {
        box.appendChild(el("div", "empty", "未找到可锁定的应用"));
        return;
      }
      installedCache.forEach(function (a) {
        var row = el("div", "app-row");
        row.appendChild(el("div", "app-row__avatar", escapeHtml(initial(a.label))));
        var name = el("div", "app-row__name");
        name.appendChild(el("div", "", escapeHtml(a.label)));
        name.appendChild(el("div", "app-row__pkg", escapeHtml(a.pkg)));
        row.appendChild(name);
        var aside = el("div", "list__aside");
        var on = !!lockedSet[a.pkg];
        aside.innerHTML = switchHtml("ap_" + a.pkg, on);
        row.appendChild(aside);
        box.appendChild(row);
      });
    }, 30);
  }

  document.getElementById("btnCloseApps").addEventListener("click", function () { closeSheet("sheetApps"); });
  document.getElementById("btnSaveApps").addEventListener("click", function () {
    var picks = [];
    var inputs = document.getElementById("appList").querySelectorAll("input[type=checkbox]");
    Array.prototype.forEach.call(inputs, function (inp) {
      if (inp.checked) picks.push(inp.id.replace(/^ap_/, ""));
    });
    B.setLockedApps(JSON.stringify(picks));
    closeSheet("sheetApps");
    renderApps();
  });

  /* ============ 视图：锁定方式 ============ */
  function renderLock() {
    view.innerHTML = "";
    view.appendChild(el("div", "section-head", "锁定方式"));
    var mode = B.getLockMode();

    var card = el("div", "card");
    card.appendChild(el("div", "card__title", "解锁方式"));
    card.appendChild(el("p", "card__desc", "进入被锁应用时需要验证。"));
    var seg = el("div", "segmented");
    seg.appendChild(segOpt("pin", "PIN 数字", mode));
    seg.appendChild(segOpt("pattern", "图案", mode));
    card.appendChild(seg);
    view.appendChild(card);

    seg.addEventListener("click", function (e) {
      var o = e.target.closest(".segmented__opt");
      if (!o) return;
      B.setLockMode(o.dataset.v);
      Array.prototype.forEach.call(seg.children, function (c) { c.classList.remove("is-active"); });
      o.classList.add("is-active");
    });

    var pinState = B.hasPin();
    var btn = el("button", "btn primary block", pinState ? "修改 PIN / 图案" : "设置 PIN / 图案");
    btn.style.marginTop = "12px";
    btn.addEventListener("click", function () { openPinSheet(pinState); });
    view.appendChild(btn);

    var note = el("p", "subtle text-center", "图案将以节点顺序校验，等效 PIN。");
    note.style.marginTop = "8px";
    view.appendChild(note);
  }

  function segOpt(v, label, current) {
    var o = el("div", "segmented__opt" + (v === current ? " is-active" : ""), label);
    o.dataset.v = v;
    return o;
  }

  /* ============ PIN 面板 ============ */
  function openPinSheet(isChange) {
    document.getElementById("pinSheetTitle").textContent = isChange ? "修改 PIN / 图案" : "设置 PIN / 图案";
    document.getElementById("pinSheetDesc").textContent =
      B.getLockMode() === "pattern"
        ? "为图案设置备用 PIN（4-8 位数字）"
        : "输入 4-8 位数字 PIN";
    document.getElementById("pinInput1").value = "";
    document.getElementById("pinInput2").value = "";
    openSheet("sheetPin");
  }
  document.getElementById("btnClosePin").addEventListener("click", function () { closeSheet("sheetPin"); });
  document.getElementById("btnSavePin").addEventListener("click", function () {
    var p1 = document.getElementById("pinInput1").value;
    var p2 = document.getElementById("pinInput2").value;
    if (!/^\d{4,8}$/.test(p1)) { toast("PIN 需为 4-8 位数字"); return; }
    if (p1 !== p2) { toast("两次输入不一致"); return; }
    B.setPin(p1);
    closeSheet("sheetPin");
    toast("已保存");
    renderLock();
  });

  /* ============ 视图：保活 ============ */
  function renderKeep() {
    view.innerHTML = "";
    view.appendChild(el("div", "section-head", "保活与自启"));

    view.appendChild(permit("usage", "使用情况访问", "锁核心必需，未开启则无法拦截",
      B.isUsageAccessGranted(), "去开启", function () { B.openUsageAccessSettings(); }));

    view.appendChild(permit("notif", "通知权限", "前台服务 8.1 必需，未开 5 秒后 ANR",
      B.isNotificationEnabled(), "去开启", function () { B.openNotificationSettings(); }));

    view.appendChild(permit("batt", "电池优化白名单", "避免 Doze 休眠杀死守护服务",
      !B.isBatteryOptimized(), "申请白名单", function () { B.requestBatteryOptimizationExemption(); }));

    view.appendChild(permit("a11y", "无障碍（可选增强）", "开启可近实时拦截，不开也能工作",
      B.isAccessibilityEnabled(), "去开启", function () { B.openAccessibilitySettings(); }));

    var card = el("div", "card");
    card.appendChild(switchRow("keepAlive", "常驻守护", "前台 Service 持续轮询", B.getKeepAlive(), function (on) { B.setKeepAlive(on); }));
    card.appendChild(switchRow("autoStart", "开机自启", "开机 / 解锁后自动拉起", B.getAutoStart(), function (on) { B.setAutoStart(on); }));
    view.appendChild(card);

    var start = el("button", "btn primary block", "立即启动守护服务");
    start.style.marginTop = "12px";
    start.addEventListener("click", function () { B.startServiceNow(); setTimeout(refreshServiceStatus, 600); });
    view.appendChild(start);

    view.appendChild(el("p", "subtle text-center", "ROM 自启动白名单需在系统设置手动加白，无法统一跳转。"));
  }

  function permit(id, title, sub, ok, btnLabel, onClick) {
    var cls = "permit" + (ok ? "" : " is-err");
    var p = el("div", cls);
    var main = el("div", "permit__main");
    main.appendChild(el("div", "permit__title", escapeHtml(title)));
    main.appendChild(el("div", "permit__sub", escapeHtml(sub)));
    p.appendChild(main);
    if (ok) {
      var badge = el("span", "badge is-ok", '<span class="dot is-ok"></span> 已就绪');
      p.appendChild(badge);
    } else {
      var btn = el("button", "btn sm primary", escapeHtml(btnLabel));
      btn.addEventListener("click", onClick);
      p.appendChild(btn);
    }
    return p;
  }

  function switchRow(id, label, sub, on, onChange) {
    var row = el("div", "list__row");
    var main = el("div", "list__main");
    main.appendChild(el("div", "list__label", escapeHtml(label)));
    main.appendChild(el("div", "list__sub", escapeHtml(sub)));
    row.appendChild(main);
    var aside = el("div", "list__aside");
    aside.innerHTML = switchHtml("sw_" + id, on);
    row.appendChild(aside);
    aside.querySelector("input").addEventListener("change", function (e) { onChange(e.target.checked); });
    return row;
  }

  /* ============ 视图：关于 ============ */
  var devTaps = 0;
  function renderAbout() {
    view.innerHTML = "";
    view.appendChild(el("div", "section-head", "安全选项"));

    var card = el("div", "card");
    card.appendChild(switchRow("antiUninstall", "防卸载", "启用设备管理员，防止被直接卸载", B.getAntiUninstall(), function (on) { B.setAntiUninstall(on); }));
    card.appendChild(switchRow("fakeCrash", "伪装崩溃", "异常时弹出系统级崩溃对话框", B.getFakeCrash(), function (on) { B.setFakeCrash(on); }));
    view.appendChild(card);

    if (B.getFakeCrash()) {
      var test = el("button", "btn ghost block", "测试伪装崩溃");
      test.style.marginTop = "10px";
      test.addEventListener("click", function () { B.applyFakeCrash(); });
      view.appendChild(test);
    }

    view.appendChild(el("div", "section-head", "关于"));
    var about = el("div", "card text-center");
    var ver = el("div", "title", "WatchAppLock");
    ver.style.fontSize = "17px";
    ver.style.fontWeight = "700";
    about.appendChild(ver);
    var v = el("div", "subtle", "版本 " + B.getVersionName());
    v.style.marginTop = "6px";
    v.style.cursor = "pointer";
    v.addEventListener("click", function () {
      devTaps++;
      if (B.bumpDevCounter()) { toast("已解锁开发者选项"); }
      else if (devTaps >= 7) { openSheet("sheetDev"); }
      refreshDevSheet();
    });
    about.appendChild(v);
    view.appendChild(about);

    if (B.getDevMode()) {
      var dev = el("button", "btn ghost block", "开发者选项");
      dev.style.marginTop = "12px";
      dev.addEventListener("click", function () { openSheet("sheetDev"); refreshDevSheet(); });
      view.appendChild(dev);
    }
  }

  function refreshDevSheet() {
    document.getElementById("adbBadge").textContent = B.getAdbStatus();
    document.getElementById("adbCmd").textContent = B.getAdbCommands();
    var rs = B.getRootStatus();
    document.getElementById("rootBadge").textContent = rs;
    document.getElementById("rootText").textContent =
      rs === "root-available" ? "已检测到 su，可装为系统应用获得系统级保活。" : "未检测到 root，系统级保活分支不可用。";
  }
  document.getElementById("btnCloseDev").addEventListener("click", function () { closeSheet("sheetDev"); });
  document.getElementById("btnCopyAdb").addEventListener("click", function () {
    var cmd = document.getElementById("adbCmd").textContent;
    try {
      navigator.clipboard.writeText(cmd);
      toast("已复制到剪贴板");
    } catch (e) { toast("请长按手动复制"); }
  });

  /* ============ 服务状态 ============ */
  function refreshServiceStatus() {
    var running = B.isServiceRunning();
    var badge = document.getElementById("svcBadge");
    var status = document.getElementById("svcStatus");
    if (running) {
      badge.className = "badge is-ok";
      badge.innerHTML = '<span class="dot is-ok"></span> 运行中';
      status.textContent = "守护服务运行中";
    } else {
      badge.className = "badge is-err";
      badge.innerHTML = '<span class="dot is-err"></span> 未运行';
      status.textContent = "守护服务未运行";
    }
  }

  /* ============ Sheet / Toast ============ */
  function openSheet(id) { document.getElementById(id).classList.add("is-open"); }
  function closeSheet(id) { document.getElementById(id).classList.remove("is-open"); }

  var toastTimer = null;
  function toast(msg) {
    var t = document.getElementById("walToast");
    if (!t) {
      t = el("div", "");
      t.id = "walToast";
      t.style.cssText =
        "position:fixed;left:50%;bottom:22%;transform:translateX(-50%);" +
        "background:var(--background);color:var(--color-text);padding:8px 14px;" +
        "border:1px solid var(--color-border);border-radius:var(--radius-md);" +
        "font-size:13px;z-index:99;opacity:0;transition:opacity .2s;pointer-events:none;";
      document.body.appendChild(t);
    }
    t.textContent = msg;
    t.style.opacity = "1";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.style.opacity = "0"; }, 1600);
  }

  function switchHtml(id, on) {
    return '<label class="switch">' +
      '<input type="checkbox" id="' + id + '" ' + (on ? "checked" : "") + ' />' +
      '<span class="switch__track"><span class="switch__thumb"></span></span>' +
      "</label>";
  }

  /* ---------------- 启动 ---------------- */
  if (B) renderAll();
})();
