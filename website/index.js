(function () {
  var root = document.documentElement;
  var storageKey = "nwc-landing-theme";
  var saved = localStorage.getItem(storageKey);
  var initial =
    saved ||
    (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
      ? "dark"
      : "light");
  root.dataset.theme = initial;

  function labelForTheme(theme) {
    var toggle = document.getElementById("theme-toggle");
    if (!toggle) return "";
    var dark = toggle.getAttribute("data-label-dark") || "Dark";
    var light = toggle.getAttribute("data-label-light") || "Light";
    return theme === "dark" ? light : dark;
  }

  function syncArchFrameTheme(theme) {
    var frame = document.querySelector(".arch-frame");
    if (!frame) return;
    try {
      var url = new URL(frame.getAttribute("src") || frame.src, window.location.href);
      url.searchParams.set("embed", "1");
      url.searchParams.set("theme", theme === "dark" ? "dark" : "light");
      frame.src = url.pathname + url.search;
    } catch (_) {}
  }

  document.addEventListener("click", function (event) {
    var target = event.target;
    if (!(target instanceof HTMLElement)) return;
    if (target.id !== "theme-toggle") return;
    var next = root.dataset.theme === "dark" ? "light" : "dark";
    root.dataset.theme = next;
    localStorage.setItem(storageKey, next);
    target.textContent = labelForTheme(next);
    syncArchFrameTheme(next);
  });

  window.addEventListener("DOMContentLoaded", function () {
    var toggle = document.getElementById("theme-toggle");
    if (toggle) toggle.textContent = labelForTheme(root.dataset.theme || "light");
    syncArchFrameTheme(root.dataset.theme || "light");
  });
})();

(function () {
  var jsonEl = document.getElementById("demo-json");
  var bannerEl = document.getElementById("demo-banner");
  var eventsEl = document.getElementById("demo-events");
  if (!jsonEl || !bannerEl || !eventsEl) return;

  var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var zh = !document.documentElement.lang || document.documentElement.lang.indexOf('zh') === 0;

  var scenes = zh ? [
    {
      type: "snapshot", seq: 41, meta: "stream 9f2c",
      json: { announcement: "双十一主会场今晚 20:00 开放", theme: { color: "#e11d48" } },
      banner: "双十一主会场今晚 20:00 开放"
    },
    {
      type: "change", seq: 42, meta: "key ui",
      json: { announcement: "票已补货：加售 5,000 张", theme: { color: "#e11d48" } },
      banner: "票已补货：加售 5,000 张"
    },
    {
      type: "change", seq: 43, meta: "key ui",
      json: { announcement: "系统维护将于今日 23:00 开始", theme: { color: "#0284c7" } },
      banner: "系统维护将于今日 23:00 开始"
    },
    {
      type: "snapshot", seq: 47, meta: "after reconnect",
      json: { announcement: "维护完成，服务已恢复", theme: { color: "#0284c7" } },
      banner: "维护完成，服务已恢复"
    }
  ] : [
    {
      type: "snapshot", seq: 41, meta: "stream 9f2c",
      json: { announcement: "11.11 main stage opens tonight at 20:00", theme: { color: "#e11d48" } },
      banner: "11.11 main stage opens tonight at 20:00"
    },
    {
      type: "change", seq: 42, meta: "key ui",
      json: { announcement: "Restocked: 5,000 more tickets on sale", theme: { color: "#e11d48" } },
      banner: "Restocked: 5,000 more tickets on sale"
    },
    {
      type: "change", seq: 43, meta: "key ui",
      json: { announcement: "Scheduled maintenance starts at 23:00 today", theme: { color: "#0284c7" } },
      banner: "Scheduled maintenance starts at 23:00 today"
    },
    {
      type: "snapshot", seq: 47, meta: "after reconnect",
      json: { announcement: "Maintenance complete — all services restored", theme: { color: "#0284c7" } },
      banner: "Maintenance complete — all services restored"
    }
  ];

  function escapeHtml(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function renderJson(obj) {
    var raw = JSON.stringify(obj, null, 2);
    return escapeHtml(raw).replace(/&quot;([^&]+)&quot;:/g, '<span class="jk">"$1"</span>:');
  }

  function addEvent(scene) {
    var li = document.createElement("li");
    li.innerHTML =
      '<span class="ev">event:' + scene.type + "</span>" +
      '<span class="seq">seq:' + scene.seq + "</span>" +
      '<span class="meta">' + escapeHtml(scene.meta) + "</span>";
    eventsEl.insertBefore(li, eventsEl.firstChild);
    while (eventsEl.children.length > 4) eventsEl.removeChild(eventsEl.lastChild);
  }

  function play(scene) {
    jsonEl.innerHTML = renderJson(scene.json);
    jsonEl.classList.add("is-changed");
    setTimeout(function () { jsonEl.classList.remove("is-changed"); }, 700);
    bannerEl.textContent = scene.banner;
    bannerEl.classList.add("flash");
    setTimeout(function () { bannerEl.classList.remove("flash"); }, 450);
    addEvent(scene);
  }

  var i = 0;
  play(scenes[0]);
  if (reduced) return;
  i = 1;

  function step() {
    play(scenes[i % scenes.length]);
    i++;
    setTimeout(step, i === 1 ? 3200 : 4200);
  }
  setTimeout(step, 3200);
})();
