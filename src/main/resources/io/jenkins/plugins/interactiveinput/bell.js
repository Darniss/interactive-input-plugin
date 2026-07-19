/*
 * Interactive Input — notification bell + rich modal (vanilla JS, no framework).
 *
 * Reads server configuration from #interactive-input-bell data-* attributes, polls the REST API for
 * pending questions the current user can answer, renders the dropdown and an accessible modal, and
 * submits answers with the Jenkins CSRF crumb. All user-supplied text is inserted via textContent;
 * only server-sanitised HTML (contextHtml, markdown preview) is inserted as innerHTML.
 */
(function () {
  "use strict";

  var mount = document.getElementById("interactive-input-bell");
  if (!mount || window.__interactiveInputBellLoaded) {
    return;
  }
  window.__interactiveInputBellLoaded = true;

  var rootUrl = (mount.getAttribute("data-root-url") || "").replace(/\/$/, "");
  var apiBase = rootUrl + "/interactive-input/api/v1";
  var pollSeconds = Math.max(5, parseInt(mount.getAttribute("data-poll-seconds"), 10) || 15);
  var richModal = mount.getAttribute("data-rich-modal") === "true";
  var initialCount = parseInt(mount.getAttribute("data-initial-count"), 10) || 0;

  var crumb = null; // {field, value}
  var questionsCache = [];
  var pollTimer = null;

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

  // ----- bell + badge -----
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
  bellBtn.innerHTML =
    '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">' +
    '<path fill="currentColor" d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22Zm6-6v-5a6 6 0 0 0-4.5-5.8V4a1.5 1.5 0 0 0-3 0v1.2A6 6 0 0 0 6 11v5l-1.7 1.7a1 1 0 0 0 .7 1.7h14a1 1 0 0 0 .7-1.7L18 16Z"/>' +
    "</svg>";
  var badge = el("span", { cls: "ii-bell-badge", attrs: { "aria-hidden": "false" } });
  bellBtn.appendChild(badge);

  var dropdown = el("div", {
    cls: "ii-dropdown",
    attrs: { role: "menu", "aria-label": "Pending questions", hidden: "hidden" }
  });

  var container = el("div", { cls: "ii-bell-container" });
  container.appendChild(bellBtn);
  container.appendChild(dropdown);
  document.body.appendChild(container);
  mount.parentNode.removeChild(mount);

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

  // ----- dropdown -----
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
    var header = el("div", { cls: "ii-dropdown-header", text: "Pending questions" });
    dropdown.appendChild(header);
    if (!questionsCache.length) {
      dropdown.appendChild(el("div", { cls: "ii-empty", text: "Nothing waiting for you right now." }));
      return;
    }
    var list = el("ul", { cls: "ii-list", attrs: { role: "none" } });
    questionsCache.slice(0, 10).forEach(function (q) {
      var item = el("li", { attrs: { role: "none" } });
      var link = el("button", {
        cls: "ii-item",
        attrs: { type: "button", role: "menuitem" }
      });
      link.appendChild(el("span", { cls: "ii-item-prompt", text: q.prompt }));
      link.appendChild(
        el("span", { cls: "ii-item-ref", text: q.jobFullName + " #" + q.buildNumber })
      );
      if (q.remainingMs >= 0) {
        link.appendChild(el("span", { cls: "ii-item-sla", text: slaLabel(q.remainingMs) }));
      }
      link.addEventListener("click", function () {
        toggleDropdown(false);
        openQuestion(q);
      });
      item.appendChild(link);
      list.appendChild(item);
    });
    dropdown.appendChild(list);
  }

  function slaLabel(ms) {
    if (ms <= 0) return "SLA due";
    var min = Math.round(ms / 60000);
    if (min < 60) return "SLA " + min + "m";
    return "SLA " + Math.round(min / 60) + "h";
  }

  // ----- modal -----
  function openQuestion(q) {
    if (!richModal) {
      // Fallback: navigate to the build's paused-input page.
      window.location.href = rootUrl + "/job/" + q.jobFullName.split("/").join("/job/") + "/" + q.buildNumber + "/input/";
      return;
    }
    // Fetch the freshest detail (includes contextHtml).
    fetchJson(apiBase + "/questions/" + encodeURIComponent(q.id)).then(function (r) {
      if (r.ok) {
        showModal(r.body);
      } else {
        showModal(q);
      }
    });
  }

  var activeModal = null;
  var lastFocused = null;

  function closeModal() {
    if (activeModal) {
      document.removeEventListener("keydown", modalKeydown, true);
      activeModal.parentNode.removeChild(activeModal);
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

  function showModal(q) {
    closeModal();
    lastFocused = document.activeElement;

    var overlay = el("div", { cls: "ii-modal-overlay" });
    var titleId = "ii-modal-title-" + q.id;
    var modal = el("div", {
      cls: "ii-modal",
      attrs: { role: "dialog", "aria-modal": "true", "aria-labelledby": titleId }
    });

    modal.appendChild(el("h2", { cls: "ii-modal-title", text: q.prompt, attrs: { id: titleId } }));
    modal.appendChild(
      el("p", { cls: "ii-modal-sub", text: q.jobFullName + " #" + q.buildNumber })
    );

    if (q.contextHtml) {
      var details = el("details", { cls: "ii-context" });
      details.appendChild(el("summary", { text: "Context" }));
      details.appendChild(el("div", { cls: "ii-context-body", html: q.contextHtml }));
      modal.appendChild(details);
    }

    var form = el("form", { cls: "ii-form" });
    var selectedChoice = { id: null };

    if (q.choices && q.choices.length) {
      var fieldset = el("fieldset", { cls: "ii-choices" });
      fieldset.appendChild(el("legend", { text: "Choose an option" }));
      q.choices.forEach(function (c, idx) {
        var row = el("label", { cls: "ii-choice" });
        var radio = el("input", {
          attrs: { type: "radio", name: "ii-choice", value: c.id }
        });
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
      var ftLabel = el("label", { text: "Or type an answer (markdown supported)", attrs: { for: "ii-ft-" + q.id } });
      freeTextArea = el("textarea", { attrs: { id: "ii-ft-" + q.id, rows: "3", "aria-label": "Free-text answer" } });
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
    overlay.appendChild(modal);
    document.body.appendChild(overlay);
    activeModal = overlay;

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
      postJson(apiBase + "/questions/" + encodeURIComponent(q.id) + "/answer", payload).then(function (r) {
        if (r.ok) {
          closeModal();
          refresh();
        } else {
          fail((r.body && r.body.message) || ("Failed (HTTP " + r.status + ")"));
        }
      }).catch(function () {
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
    overlay.addEventListener("click", function (e) {
      if (e.target === overlay) closeModal();
    });
    document.addEventListener("keydown", modalKeydown, true);
    answerBtn.focus();
  }

  // ----- polling -----
  function refresh() {
    return fetchJson(apiBase + "/questions").then(function (r) {
      if (r.ok && r.body && Array.isArray(r.body.questions)) {
        questionsCache = r.body.questions;
        setCount(r.body.count != null ? r.body.count : questionsCache.length);
        if (!dropdown.hasAttribute("hidden")) {
          renderDropdown();
        }
      }
    }).catch(function () {
      /* transient network error; keep last known state */
    });
  }

  function scheduleNext() {
    if (pollTimer) clearTimeout(pollTimer);
    if (document.hidden) return;
    pollTimer = setTimeout(function () {
      refresh().then(scheduleNext);
    }, pollSeconds * 1000);
  }

  bellBtn.addEventListener("click", function () {
    toggleDropdown();
  });
  document.addEventListener("click", function (e) {
    if (!container.contains(e.target) && !dropdown.hasAttribute("hidden")) {
      toggleDropdown(false);
    }
  });
  document.addEventListener("visibilitychange", function () {
    if (!document.hidden) {
      refresh().then(scheduleNext);
    }
  });

  loadCrumb().then(function () {
    return refresh();
  }).then(scheduleNext);
})();
