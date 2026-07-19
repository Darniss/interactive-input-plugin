/*
 * Interactive Input — shared client (vanilla JS, no framework).
 *
 * One adjunct drives every surface:
 *   - the (opt-in) global nav bell  ................  #interactive-input-bell
 *   - per-project job widgets  ....................  [data-ii-widget]  (data-job)
 *   - per-build audit widgets  ....................  [data-ii-audit]   (data-job + data-build)
 *
 * The modal, answer submission and markdown preview are shared so the bell and the scoped widgets
 * behave identically. All user-supplied text is inserted via textContent; only server-sanitised HTML
 * (contextHtml / markdown preview) is inserted as innerHTML.
 */
(function () {
  "use strict";

  if (window.__interactiveInputLoaded) {
    return;
  }

  function toArray(nodeList) {
    return Array.prototype.slice.call(nodeList || []);
  }

  var bellMount = document.getElementById("interactive-input-bell");
  var widgetMounts = toArray(document.querySelectorAll("[data-ii-widget]"));
  var auditMounts = toArray(document.querySelectorAll("[data-ii-audit]"));
  if (!bellMount && !widgetMounts.length && !auditMounts.length) {
    return;
  }
  window.__interactiveInputLoaded = true;

  // ----- shared config (all mounts share the same Jenkins origin) -----
  function attr(node, name, dflt) {
    var v = node ? node.getAttribute(name) : null;
    return v == null ? dflt : v;
  }
  var cfgSrc = bellMount || widgetMounts[0] || auditMounts[0];
  var rootUrl = (attr(cfgSrc, "data-root-url", "") || "").replace(/\/$/, "");
  var apiBase = rootUrl + "/interactive-input/api/v1";
  var richModalDefault = attr(cfgSrc, "data-rich-modal", "true") === "true";
  var pollSeconds = Math.max(5, parseInt(attr(cfgSrc, "data-poll-seconds", "15"), 10) || 15);

  var crumb = null; // {field, value}

  // ----- small DOM helpers -----
  function el(tag, opts) {
    var e = document.createElement(tag);
    opts = opts || {};
    if (opts.cls) e.className = opts.cls;
    if (opts.text != null) e.textContent = opts.text;
    if (opts.html != null) e.innerHTML = opts.html;
    if (opts.attrs) {
      Object.keys(opts.attrs).forEach(function (k) {
        e.setAttribute(k, opts.attrs[k]);
      });
    }
    return e;
  }

  function noop() {}

  function fetchJson(url, options) {
    options = options || {};
    options.headers = options.headers || {};
    options.headers["Accept"] = "application/json";
    options.credentials = "same-origin";
    return fetch(url, options).then(function (resp) {
      var ct = resp.headers.get("content-type") || "";
      var parse = ct.indexOf("application/json") >= 0 ? resp.json() : resp.text();
      return parse.then(function (body) {
        return { ok: resp.ok, status: resp.status, body: body };
      });
    });
  }

  function loadCrumb() {
    return fetchJson(rootUrl + "/crumbIssuer/api/json")
      .then(function (r) {
        if (r.ok && r.body && r.body.crumbRequestField) {
          crumb = { field: r.body.crumbRequestField, value: r.body.crumb };
        }
      })
      .catch(function () {
        /* CSRF protection may be disabled; proceed without a crumb. */
      });
  }

  function postJson(url, payload) {
    var headers = { "Content-Type": "application/json" };
    if (crumb) {
      headers[crumb.field] = crumb.value;
    }
    return fetchJson(url, { method: "POST", headers: headers, body: JSON.stringify(payload || {}) });
  }

  // ----- shared labels/formatters -----
  function refLabel(q) {
    return q.jobFullName + " #" + q.buildNumber;
  }

  function slaLabel(ms) {
    if (ms <= 0) return "SLA due";
    var min = Math.round(ms / 60000);
    if (min < 60) return "SLA " + min + "m";
    return "SLA " + Math.round(min / 60) + "h";
  }

  function fmtTime(ts) {
    if (!ts) return "";
    try {
      return " on " + new Date(ts).toLocaleString();
    } catch (e) {
      return "";
    }
  }

  function choiceLabelOf(q, choiceId) {
    if (q.choices) {
      for (var i = 0; i < q.choices.length; i++) {
        if (q.choices[i].id === choiceId) return q.choices[i].label;
      }
    }
    return choiceId;
  }

  function outcomeText(q) {
    var a = q.answer;
    if (q.status === "ANSWERED" && a) {
      var who = a.answeredBy || "unknown";
      if (a.choiceId === "__deny__") return "Denied by " + who + fmtTime(a.answeredTs);
      if (a.choiceId) return "Answered by " + who + ": " + choiceLabelOf(q, a.choiceId) + fmtTime(a.answeredTs);
      if (a.freeText) return "Answered by " + who + ": " + a.freeText + fmtTime(a.answeredTs);
      return "Answered by " + who + fmtTime(a.answeredTs);
    }
    if (q.status === "ABORTED") {
      return "Aborted" + (a && a.answeredBy ? " by " + a.answeredBy : "") + (a ? fmtTime(a.answeredTs) : "");
    }
    if (q.status === "EXPIRED") return "Expired: SLA elapsed with no answer";
    if (q.status === "WAITING") return "Still waiting for an answer.";
    return q.status || "";
  }

  function shortOutcome(q) {
    var t = outcomeText(q);
    return t.length > 80 ? t.slice(0, 80) + "…" : t;
  }

  function statusPill(status) {
    return el("span", { cls: "ii-pill ii-pill-" + (status || "").toLowerCase(), text: status || "" });
  }

  // ----- shared list rows -----
  function questionListItem(q, onClick) {
    var link = el("button", { cls: "ii-item", attrs: { type: "button", role: "menuitem" } });
    link.appendChild(el("span", { cls: "ii-item-prompt", text: q.prompt }));
    link.appendChild(el("span", { cls: "ii-item-ref", text: refLabel(q) }));
    if (q.startedBy) {
      link.appendChild(el("span", { cls: "ii-item-by", text: "started by " + q.startedBy }));
    }
    if (q.remainingMs >= 0) {
      link.appendChild(el("span", { cls: "ii-item-sla", text: slaLabel(q.remainingMs) }));
    }
    link.addEventListener("click", onClick);
    return link;
  }

  function auditRow(q, onClick) {
    var link = el("button", { cls: "ii-item", attrs: { type: "button" } });
    link.appendChild(el("span", { cls: "ii-item-prompt", text: q.prompt }));
    var meta = el("span", { cls: "ii-item-ref" });
    meta.appendChild(statusPill(q.status));
    if (q.startedBy) {
      meta.appendChild(el("span", { cls: "ii-item-by", text: " started by " + q.startedBy }));
    }
    link.appendChild(meta);
    link.appendChild(el("span", { cls: "ii-item-outcome", text: shortOutcome(q) }));
    link.addEventListener("click", onClick);
    return link;
  }

  // ================================ shared modal ================================
  var activeModal = null;
  var lastFocused = null;

  function closeModal() {
    if (activeModal) {
      document.removeEventListener("keydown", modalKeydown, true);
      if (activeModal.parentNode) activeModal.parentNode.removeChild(activeModal);
      activeModal = null;
      if (lastFocused && lastFocused.focus) lastFocused.focus();
    }
  }

  function modalKeydown(e) {
    if (e.key === "Escape") {
      e.preventDefault();
      closeModal();
    } else if (e.key === "Tab" && activeModal) {
      var focusable = activeModal.querySelectorAll(
        'button, [href], input, textarea, select, [tabindex]:not([tabindex="-1"])'
      );
      if (!focusable.length) return;
      var first = focusable[0];
      var last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
  }

  function navigateToInput(q) {
    window.location.href =
      rootUrl + "/job/" + q.jobFullName.split("/").join("/job/") + "/" + q.buildNumber + "/input/";
  }

  /**
   * Open a question. opts: { readOnly:bool, richModal:bool, onDone:fn }.
   * Read-only (audit) rows already carry full detail (contextHtml + answer); interactive opens fetch
   * the freshest detail first.
   */
  function openQuestion(q, opts) {
    opts = opts || {};
    var readOnly = !!opts.readOnly;
    var richModal = opts.richModal != null ? opts.richModal : richModalDefault;
    if (!readOnly && !richModal) {
      navigateToInput(q);
      return;
    }
    if (readOnly && q.contextHtml != null) {
      showModal(q, opts);
      return;
    }
    fetchJson(apiBase + "/questions/" + encodeURIComponent(q.id))
      .then(function (r) {
        showModal(r.ok ? r.body : q, opts);
      })
      .catch(function () {
        showModal(q, opts);
      });
  }

  function buildModalShell(q, readOnly) {
    var titleId = "ii-modal-title-" + q.id;
    var modal = el("div", {
      cls: "ii-modal",
      attrs: { role: "dialog", "aria-modal": "true", "aria-labelledby": titleId }
    });
    modal.appendChild(el("h2", { cls: "ii-modal-title", text: q.prompt, attrs: { id: titleId } }));

    var sub = el("p", { cls: "ii-modal-sub" });
    sub.appendChild(el("span", { text: refLabel(q) }));
    if (q.startedBy) {
      sub.appendChild(el("span", { cls: "ii-sub-by", text: " · started by " + q.startedBy }));
    }
    if (readOnly && q.status) {
      sub.appendChild(statusPill(q.status));
    }
    modal.appendChild(sub);

    if (q.contextHtml) {
      // Expanded by default so reviewers see the context without an extra click.
      var details = el("details", { cls: "ii-context", attrs: { open: "open" } });
      details.appendChild(el("summary", { text: "Context" }));
      details.appendChild(el("div", { cls: "ii-context-body", html: q.contextHtml }));
      modal.appendChild(details);
    }
    return modal;
  }

  function renderAudit(modal, q) {
    if (q.choices && q.choices.length) {
      var chosen = q.answer && q.answer.choiceId;
      var ul = el("ul", { cls: "ii-audit-choices" });
      q.choices.forEach(function (c) {
        var li = el("li", { cls: "ii-audit-choice" + (c.id === chosen ? " ii-chosen" : "") });
        li.appendChild(el("span", { cls: "ii-choice-label", text: c.label + (c.id === chosen ? "  ✓" : "") }));
        if (c.why) li.appendChild(el("span", { cls: "ii-choice-why", text: c.why }));
        ul.appendChild(li);
      });
      modal.appendChild(ul);
    }
    var outcome = el("div", { cls: "ii-audit-outcome" });
    outcome.appendChild(el("div", { cls: "ii-audit-outcome-title", text: "Outcome" }));
    outcome.appendChild(el("div", { text: outcomeText(q) }));
    modal.appendChild(outcome);

    var actions = el("div", { cls: "ii-actions" });
    var closeBtn = el("button", { cls: "ii-btn", text: "Close", attrs: { type: "button" } });
    closeBtn.addEventListener("click", closeModal);
    actions.appendChild(closeBtn);
    modal.appendChild(actions);
  }

  function renderForm(modal, q, opts) {
    var onDone = opts.onDone || noop;
    var form = el("form", { cls: "ii-form" });
    var selectedChoice = { id: null };

    if (q.choices && q.choices.length) {
      var fieldset = el("fieldset", { cls: "ii-choices" });
      fieldset.appendChild(el("legend", { text: "Choose an option" }));
      q.choices.forEach(function (c, idx) {
        var row = el("label", { cls: "ii-choice" });
        var radio = el("input", { attrs: { type: "radio", name: "ii-choice", value: c.id } });
        if (idx === 0) {
          radio.checked = true;
          selectedChoice.id = c.id;
        }
        radio.addEventListener("change", function () {
          selectedChoice.id = c.id;
        });
        var textWrap = el("span", { cls: "ii-choice-text" });
        textWrap.appendChild(el("span", { cls: "ii-choice-label", text: c.label }));
        if (c.why) {
          textWrap.appendChild(el("span", { cls: "ii-choice-why", text: c.why }));
        }
        row.appendChild(radio);
        row.appendChild(textWrap);
        fieldset.appendChild(row);
      });
      form.appendChild(fieldset);
    }

    var freeTextArea = null;
    if (q.allowFreeText) {
      var ftWrap = el("div", { cls: "ii-freetext" });
      var ftLabel = el("label", {
        text: "Or type an answer (markdown supported)",
        attrs: { for: "ii-ft-" + q.id }
      });
      freeTextArea = el("textarea", {
        attrs: { id: "ii-ft-" + q.id, rows: "3", "aria-label": "Free-text answer" }
      });
      var preview = el("div", { cls: "ii-preview", attrs: { "aria-live": "polite" } });
      var previewTimer = null;
      freeTextArea.addEventListener("input", function () {
        if (previewTimer) clearTimeout(previewTimer);
        previewTimer = setTimeout(function () {
          var val = freeTextArea.value;
          if (!val) {
            preview.innerHTML = "";
            return;
          }
          postJson(apiBase + "/preview", { markdown: val }).then(function (r) {
            if (r.ok && r.body && typeof r.body.html === "string") {
              preview.innerHTML = r.body.html; // server-sanitised
            }
          });
        }, 300);
      });
      ftWrap.appendChild(ftLabel);
      ftWrap.appendChild(freeTextArea);
      ftWrap.appendChild(el("div", { cls: "ii-preview-label", text: "Preview" }));
      ftWrap.appendChild(preview);
      form.appendChild(ftWrap);
    }

    var errBox = el("div", { cls: "ii-error", attrs: { role: "alert" } });
    form.appendChild(errBox);

    var actions = el("div", { cls: "ii-actions" });
    var answerBtn = el("button", { cls: "ii-btn ii-btn-primary", text: "Answer", attrs: { type: "submit" } });
    var denyBtn = el("button", { cls: "ii-btn ii-btn-danger", text: "Deny", attrs: { type: "button" } });
    var cancelBtn = el("button", { cls: "ii-btn", text: "Cancel", attrs: { type: "button" } });
    actions.appendChild(answerBtn);
    actions.appendChild(denyBtn);
    actions.appendChild(cancelBtn);
    form.appendChild(actions);
    modal.appendChild(form);

    function busy(on) {
      answerBtn.disabled = on;
      denyBtn.disabled = on;
    }
    function fail(msg) {
      errBox.textContent = msg;
      busy(false);
    }
    function submit(payload) {
      busy(true);
      errBox.textContent = "";
      postJson(apiBase + "/questions/" + encodeURIComponent(q.id) + "/answer", payload)
        .then(function (r) {
          if (r.ok) {
            closeModal();
            onDone();
          } else {
            fail((r.body && r.body.message) || "Failed (HTTP " + r.status + ")");
          }
        })
        .catch(function () {
          fail("Network error submitting the answer.");
        });
    }

    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var freeText = freeTextArea ? freeTextArea.value.trim() : "";
      if (freeText) {
        submit({ freeText: freeText });
      } else if (selectedChoice.id) {
        submit({ choiceId: selectedChoice.id });
      } else {
        fail("Pick a choice or type an answer.");
      }
    });
    denyBtn.addEventListener("click", function () {
      submit({ choiceId: "__deny__" });
    });
    cancelBtn.addEventListener("click", closeModal);
  }

  function showModal(q, opts) {
    opts = opts || {};
    var readOnly = !!opts.readOnly;
    closeModal();
    lastFocused = document.activeElement;

    var overlay = el("div", { cls: "ii-modal-overlay" });
    var modal = buildModalShell(q, readOnly);
    if (readOnly) {
      renderAudit(modal, q);
    } else {
      renderForm(modal, q, opts);
    }
    overlay.appendChild(modal);
    document.body.appendChild(overlay);
    activeModal = overlay;

    overlay.addEventListener("click", function (e) {
      if (e.target === overlay) closeModal();
    });
    document.addEventListener("keydown", modalKeydown, true);
    var focusTarget = modal.querySelector("button, [href], input, textarea, select");
    if (focusTarget) focusTarget.focus();
  }

  // ----- shared visibility-aware polling loop -----
  function scheduleLoop(fn) {
    var timer = null;
    function tick() {
      if (timer) clearTimeout(timer);
      if (document.hidden) return;
      timer = setTimeout(function () {
        fn().then(tick);
      }, pollSeconds * 1000);
    }
    document.addEventListener("visibilitychange", function () {
      if (!document.hidden) fn().then(tick);
    });
    tick();
  }

  // ================================ global nav bell ================================
  function anchorBell(container) {
    // Prefer inline placement among the header controls so the bell never overlaps the settings gear.
    var selectors = [
      ".jenkins-header__actions",
      "#page-header .page-header__hyperlinks",
      "header#page-header .jenkins-header__actions"
    ];
    for (var i = 0; i < selectors.length; i++) {
      var host = document.querySelector(selectors[i]);
      if (host) {
        container.classList.add("ii-bell-inline");
        host.insertBefore(container, host.firstChild);
        return;
      }
    }
    // Fallback: a floating control anchored bottom-right, clear of the header icons.
    container.classList.add("ii-bell-fixed");
    document.body.appendChild(container);
  }

  // Use the server-rendered <l:icon> (the operator's chosen Ionicon) if present, else a default bell.
  function setBellIcon(bellBtn, mount) {
    var tpl = mount.querySelector(".ii-icon-template svg");
    if (tpl) {
      bellBtn.appendChild(tpl.cloneNode(true));
    } else {
      bellBtn.innerHTML =
        '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">' +
        '<path fill="currentColor" d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22Zm6-6v-5a6 6 0 0 0-4.5-5.8V4a1.5 1.5 0 0 0-3 0v1.2A6 6 0 0 0 6 11v5l-1.7 1.7a1 1 0 0 0 .7 1.7h14a1 1 0 0 0 .7-1.7L18 16Z"/>' +
        "</svg>";
    }
  }

  function mountBell(mount) {
    var richModal = attr(mount, "data-rich-modal", "true") === "true";
    var initialCount = parseInt(attr(mount, "data-initial-count", "0"), 10) || 0;
    var job = attr(mount, "data-job", "") || "";
    // Dashboard => every answerable question; inside a pipeline => only that pipeline's questions.
    var listUrl = job ? apiBase + "/questions?job=" + encodeURIComponent(job) : apiBase + "/questions";
    var headerText = job ? "Pending for this pipeline" : "Pending questions";

    var bellBtn = el("button", {
      cls: "ii-bell-btn",
      attrs: {
        type: "button",
        "aria-label": "Pending interactive input questions",
        "aria-haspopup": "true",
        "aria-expanded": "false",
        title: "Interactive Input"
      }
    });
    setBellIcon(bellBtn, mount);
    var badge = el("span", { cls: "ii-bell-badge", attrs: { "aria-hidden": "false" } });
    bellBtn.appendChild(badge);

    var dropdown = el("div", {
      cls: "ii-dropdown",
      attrs: { role: "menu", "aria-label": "Pending questions", hidden: "hidden" }
    });

    var container = el("div", { cls: "ii-bell-container" });
    container.appendChild(bellBtn);
    container.appendChild(dropdown);
    anchorBell(container);
    if (mount.parentNode) mount.parentNode.removeChild(mount);

    var questionsCache = [];

    function setCount(n) {
      if (n > 0) {
        badge.textContent = n > 99 ? "99+" : String(n);
        badge.style.display = "inline-flex";
        bellBtn.classList.add("ii-has-pending");
      } else {
        badge.textContent = "";
        badge.style.display = "none";
        bellBtn.classList.remove("ii-has-pending");
      }
    }
    setCount(initialCount);

    function toggleDropdown(force) {
      var show = force != null ? force : dropdown.hasAttribute("hidden");
      if (show) {
        renderDropdown();
        dropdown.removeAttribute("hidden");
        bellBtn.setAttribute("aria-expanded", "true");
      } else {
        dropdown.setAttribute("hidden", "hidden");
        bellBtn.setAttribute("aria-expanded", "false");
      }
    }

    function renderDropdown() {
      dropdown.innerHTML = "";
      dropdown.appendChild(el("div", { cls: "ii-dropdown-header", text: headerText }));
      if (!questionsCache.length) {
        dropdown.appendChild(el("div", { cls: "ii-empty", text: "Nothing waiting for you right now." }));
        return;
      }
      var list = el("ul", { cls: "ii-list", attrs: { role: "none" } });
      questionsCache.slice(0, 10).forEach(function (q) {
        var item = el("li", { attrs: { role: "none" } });
        item.appendChild(
          questionListItem(q, function () {
            toggleDropdown(false);
            openQuestion(q, { richModal: richModal, onDone: refresh });
          })
        );
        list.appendChild(item);
      });
      dropdown.appendChild(list);
    }

    function refresh() {
      return fetchJson(listUrl)
        .then(function (r) {
          if (r.ok && r.body && Array.isArray(r.body.questions)) {
            questionsCache = r.body.questions;
            setCount(r.body.count != null ? r.body.count : questionsCache.length);
            if (!dropdown.hasAttribute("hidden")) {
              renderDropdown();
            }
          }
        })
        .catch(noop);
    }

    bellBtn.addEventListener("click", function () {
      toggleDropdown();
    });
    document.addEventListener("click", function (e) {
      if (!container.contains(e.target) && !dropdown.hasAttribute("hidden")) {
        toggleDropdown(false);
      }
    });

    refresh().then(function () {
      scheduleLoop(refresh);
    });
  }

  // ================================ per-project widgets ================================
  function mountListWidget(mount, url, rowFactory, emptyText) {
    var listWrap = el("div", { cls: "ii-widget" });
    mount.appendChild(listWrap);

    function render(questions) {
      listWrap.innerHTML = "";
      if (!questions.length) {
        listWrap.appendChild(el("div", { cls: "ii-empty", text: emptyText }));
        return;
      }
      var ul = el("ul", { cls: "ii-list" });
      questions.forEach(function (q) {
        var li = el("li");
        li.appendChild(rowFactory(q, refresh));
        ul.appendChild(li);
      });
      listWrap.appendChild(ul);
    }

    function refresh() {
      return fetchJson(url)
        .then(function (r) {
          if (r.ok && r.body && Array.isArray(r.body.questions)) {
            render(r.body.questions);
          }
        })
        .catch(noop);
    }

    refresh().then(function () {
      scheduleLoop(refresh);
    });
  }

  function mountJobWidget(mount) {
    var job = attr(mount, "data-job", "");
    var richModal = attr(mount, "data-rich-modal", "true") === "true";
    var url = apiBase + "/questions?job=" + encodeURIComponent(job);
    mountListWidget(
      mount,
      url,
      function (q, refresh) {
        return questionListItem(q, function () {
          openQuestion(q, { richModal: richModal, onDone: refresh });
        });
      },
      "All caught up — nothing waiting."
    );
  }

  function mountAuditWidget(mount) {
    var job = attr(mount, "data-job", "");
    var build = attr(mount, "data-build", "");
    var url =
      apiBase + "/questions?job=" + encodeURIComponent(job) + "&build=" + encodeURIComponent(build);
    mountListWidget(
      mount,
      url,
      function (q) {
        return auditRow(q, function () {
          openQuestion(q, { readOnly: true });
        });
      },
      "No interactive-input records for this build (they may have been compacted — see the build console)."
    );
  }

  // ----- bootstrap -----
  loadCrumb().then(function () {
    if (bellMount) mountBell(bellMount);
    widgetMounts.forEach(mountJobWidget);
    auditMounts.forEach(mountAuditWidget);
  });
})();
