(function () {
  "use strict";
  var root = document.documentElement;

  /* ---------- theme ---------- */
  function storedTheme() {
    try { return localStorage.getItem("sharpr-theme"); } catch (e) { return null; }
  }
  function saveTheme(t) {
    try { localStorage.setItem("sharpr-theme", t); } catch (e) { /* storage can be unavailable */ }
  }
  function applyTheme(t) {
    root.setAttribute("data-theme", t);
    document.querySelectorAll("img[data-light][data-dark]").forEach(function (img) {
      img.src = t === "dark" ? img.getAttribute("data-dark") : img.getAttribute("data-light");
    });
    var btn = document.getElementById("theme-toggle");
    if (btn) btn.setAttribute("aria-label", t === "dark" ? "Switch to light theme" : "Switch to dark theme");
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", t === "dark" ? "#0e0a1a" : "#f7f4ff");
  }
  var initial = storedTheme() || (window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark");
  applyTheme(initial);
  var tbtn = document.getElementById("theme-toggle");
  if (tbtn) {
    tbtn.addEventListener("click", function () {
      var next = root.getAttribute("data-theme") === "dark" ? "light" : "dark";
      applyTheme(next);
      saveTheme(next);
    });
  }

  /* ---------- mobile menu ---------- */
  var menuBtn = document.getElementById("menu-btn");
  var links = document.getElementById("nav-links");
  if (menuBtn && links) {
    menuBtn.addEventListener("click", function () {
      var open = links.classList.toggle("open");
      menuBtn.setAttribute("aria-expanded", String(open));
    });
    links.addEventListener("click", function (e) {
      if (e.target.tagName === "A") {
        links.classList.remove("open");
        menuBtn.setAttribute("aria-expanded", "false");
      }
    });
  }

  /* ---------- scroll reveal ---------- */
  var reveals = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add("in"); io.unobserve(en.target); }
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -40px 0px" });
    reveals.forEach(function (el) { io.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add("in"); });
  }

  /* ---------- before / after sliders ---------- */
  document.querySelectorAll(".compare").forEach(function (box) {
    var range = box.querySelector("input[type=range]");
    var after = box.querySelector(".after-wrap");
    var handle = box.querySelector(".handle");
    function set(v) {
      after.style.clipPath = "inset(0 0 0 " + v + "%)";
      handle.style.left = v + "%";
    }
    range.addEventListener("input", function () { set(range.value); });
    set(range.value);
    // gentle intro sweep once the slider is on screen
    if ("IntersectionObserver" in window && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      var seen = new IntersectionObserver(function (en) {
        if (!en[0].isIntersecting) return;
        seen.disconnect();
        var start = null;
        function frame(ts) {
          if (start === null) start = ts;
          var t = Math.min((ts - start) / 1400, 1);
          var v = 50 + Math.sin(t * Math.PI * 2) * 22 * (1 - t);
          range.value = v; set(v);
          if (t < 1) requestAnimationFrame(frame);
        }
        requestAnimationFrame(frame);
      }, { threshold: 0.6 });
      seen.observe(box);
    }
  });

  /* ---------- sample model tabs ---------- */
  var sampleTabs = document.querySelectorAll("[data-sample]");
  var sampleImg = document.getElementById("sample-after");
  var sampleMeta = document.getElementById("sample-meta");
  sampleTabs.forEach(function (tab) {
    tab.addEventListener("click", function () {
      sampleTabs.forEach(function (t) { t.setAttribute("aria-selected", "false"); });
      tab.setAttribute("aria-selected", "true");
      sampleImg.src = tab.getAttribute("data-src");
      sampleImg.alt = tab.getAttribute("data-alt");
      sampleMeta.innerHTML = tab.getAttribute("data-meta");
    });
  });

  /* ---------- app tabs ---------- */
  var appTabs = document.querySelectorAll("[data-app]");
  appTabs.forEach(function (tab) {
    tab.addEventListener("click", function () {
      appTabs.forEach(function (t) {
        var on = t === tab;
        t.setAttribute("aria-selected", String(on));
        document.getElementById("panel-" + t.getAttribute("data-app")).hidden = !on;
      });
    });
  });

  /* ---------- lightbox ---------- */
  var dlg = document.getElementById("lightbox");
  var dlgImg = dlg ? dlg.querySelector("img") : null;
  document.addEventListener("click", function (e) {
    var img = e.target.closest ? e.target.closest("img.zoomable") : null;
    if (img && dlg && dlgImg && typeof dlg.showModal === "function") {
      dlgImg.src = img.currentSrc || img.src;
      dlgImg.alt = img.alt;
      dlg.showModal();
    } else if (e.target === dlg) {
      dlg.close();
    }
  });

  /* ---------- footer year ---------- */
  var y = document.getElementById("year");
  if (y) y.textContent = new Date().getFullYear();
})();
