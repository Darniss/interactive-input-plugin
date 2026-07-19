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

  // Mounts are discovered on DOM ready (see boot()), NOT here. On a job/pipeline page this adjunct is
  // emitted by jobMain.jelly in the MAIN PANEL — i.e. BEFORE the sidebar [data-ii-tasklink] controller
  // (its next sibling) and the footer #interactive-input-bell are parsed. Querying at script-execution
  // time therefore misses them, which is why the bell was absent inside a job and the sidebar "(N)"
  // count never updated live. Discovering after the DOM is parsed fixes both without touching layout.
  var bellMount = null;
  var widgetMounts = [];
  var auditMounts = [];
  var taskLinkMounts = [];
  // No early return when there are no mounts: build-history badges ([data-ii-badge]) are injected
  // lazily by the async build-history widget, so they may not exist yet at load. A delegated click
  // handler (wired at the bottom) covers them; the polling mounts are still set up conditionally.
  window.__interactiveInputLoaded = true;

  // ----- shared config (all mounts share the same Jenkins origin) -----
  function attr(node, name, dflt) {
    var v = node ? node.getAttribute(name) : null;
    return v == null ? dflt : v;
  }
  // Shared config, (re)computed from the first mount present once the DOM is ready (see discover()).
  // Safe defaults keep the delegated badge handler usable on a badge-only page before discovery runs.
  var cfgSrc = null;
  var rootUrl = "";
  var apiBase = rootUrl + "/interactive-input/api/v1";
  var richModalDefault = true;
  var pollSeconds = 15;

  // When the only surface on the page is a build-history badge (no mount to read config from), adopt
  // the origin from the clicked badge's data-root-url so API calls resolve under any context path.
  function adoptRootUrl(node) {
    if (cfgSrc) {
      return;
    }
    var ru = node && node.getAttribute ? node.getAttribute("data-root-url") : null;
    if (ru != null) {
      rootUrl = ru.replace(/\/$/, "");
      apiBase = rootUrl + "/interactive-input/api/v1";
    }
  }

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

  // Let every surface on the page (bell, per-project widgets, build-list badges) update immediately
  // when a question is answered from any modal here, instead of waiting for the next poll.
  function announceAnswered(q) {
    try {
      document.dispatchEvent(
        new CustomEvent("ii:answered", { detail: { id: q.id, job: q.jobFullName, build: q.buildNumber } })
      );
    } catch (e) {
      /* CustomEvent unsupported: surfaces still refresh on their next poll. */
    }
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

  // Load the crumb at most once, on demand — so a badge-only page (no eager mount bootstrap) still
  // has a crumb before it POSTs an answer.
  var crumbAttempted = false;
  function ensureCrumb() {
    if (crumbAttempted) {
      return Promise.resolve();
    }
    crumbAttempted = true;
    return loadCrumb();
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

    // In a series the caller passes opts.initial to restore a half-finished answer (draft) when the
    // user pages back to this question; otherwise the first choice is pre-selected as before.
    var initialChoiceId = opts.initial ? opts.initial.choiceId : null;
    var hasInitialChoice = false;
    if (initialChoiceId && q.choices) {
      hasInitialChoice = q.choices.some(function (c) {
        return c.id === initialChoiceId;
      });
    }

    if (q.choices && q.choices.length) {
      var fieldset = el("fieldset", { cls: "ii-choices" });
      fieldset.appendChild(el("legend", { text: "Choose an option" }));
      q.choices.forEach(function (c, idx) {
        var row = el("label", { cls: "ii-choice" });
        var radio = el("input", { attrs: { type: "radio", name: "ii-choice", value: c.id } });
        if (hasInitialChoice ? c.id === initialChoiceId : idx === 0) {
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
      // Restore a series draft so text typed before paging away is not lost.
      if (opts.initial && opts.initial.freeText) {
        freeTextArea.value = opts.initial.freeText;
      }
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

    // Locked (Point 3): when the server reports the viewer may not answer this question (lock-to-
    // build-starter is on and they are not the owner), they can still read it but the controls are
    // disabled with an explanation. `canAnswer` is only present when the server computes it, so this
    // is a no-op for older payloads.
    var locked = q.canAnswer === false;

    var actions = el("div", { cls: "ii-actions" });
    var answerBtn = el("button", { cls: "ii-btn ii-btn-primary", text: "Answer", attrs: { type: "submit" } });
    var denyBtn = el("button", { cls: "ii-btn ii-btn-danger", text: "Deny", attrs: { type: "button" } });
    var cancelBtn = el("button", { cls: "ii-btn", text: locked ? "Close" : "Cancel", attrs: { type: "button" } });
    if (locked) {
      var owner = q.startedBy ? " Only " + q.startedBy + " (the build starter) can answer it." : "";
      errBox.textContent = "Locked." + owner;
      answerBtn.disabled = true;
      denyBtn.disabled = true;
      answerBtn.setAttribute("aria-disabled", "true");
      denyBtn.setAttribute("aria-disabled", "true");
    } else {
      actions.appendChild(answerBtn);
      actions.appendChild(denyBtn);
    }
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
            // In a series the pager keeps the overlay open and moves to the next question; a single
            // modal closes itself. announceAnswered fires either way so other surfaces refresh.
            if (!opts.keepOpen) {
              closeModal();
            }
            announceAnswered(q);
            onDone(payload);
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
      if (locked) {
        return;
      }
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
      if (locked) {
        return;
      }
      submit({ choiceId: "__deny__" });
    });
    cancelBtn.addEventListener("click", closeModal);
  }

  // Open a fresh overlay hosting `modal`. Wires overlay-click/Escape close and initial focus.
  function openOverlay(modal) {
    closeModal();
    lastFocused = document.activeElement;
    var overlay = el("div", { cls: "ii-modal-overlay" });
    overlay.appendChild(modal);
    document.body.appendChild(overlay);
    activeModal = overlay;
    overlay.addEventListener("click", function (e) {
      if (e.target === overlay) closeModal();
    });
    document.addEventListener("keydown", modalKeydown, true);
    focusFirst(modal);
    return overlay;
  }

  function focusFirst(modal) {
    var focusTarget = modal.querySelector("button, [href], input, textarea, select");
    if (focusTarget) focusTarget.focus();
  }

  // Swap the modal body inside the current overlay (used by the series pager to move between
  // questions without tearing down/rebuilding the overlay, so there is no flicker). Falls back to a
  // fresh overlay when nothing is open yet.
  function replaceModal(modal) {
    if (!activeModal) {
      return openOverlay(modal);
    }
    while (activeModal.firstChild) {
      activeModal.removeChild(activeModal.firstChild);
    }
    activeModal.appendChild(modal);
    focusFirst(modal);
    return activeModal;
  }

  function showModal(q, opts) {
    opts = opts || {};
    var readOnly = !!opts.readOnly;
    var modal = buildModalShell(q, readOnly);
    if (readOnly) {
      renderAudit(modal, q);
    } else {
      renderForm(modal, q, opts);
    }
    openOverlay(modal);
  }

  // ================================ series pager ================================
  // A single modal that pages through a *series* of questions with numbered navigation (‹ 2 / 5 ›
  // plus clickable numbered pips). Answering advances to the next still-waiting question in place;
  // already-answered ones render read-only so you can review what was chosen. Used by the bell's and
  // the job box's "Answer all (N)" affordance and by a build with more than one waiting question.
  function buildSeriesNav(ctx) {
    var nav = el("div", { cls: "ii-series-nav" });
    var row = el("div", { cls: "ii-series-row" });
    var prev = el("button", { cls: "ii-btn ii-series-prev", text: "‹ Prev", attrs: { type: "button" } });
    var pos = el("span", { cls: "ii-series-pos", text: ctx.index + 1 + " / " + ctx.list.length });
    var next = el("button", { cls: "ii-btn ii-series-next", text: "Next ›", attrs: { type: "button" } });
    prev.disabled = ctx.index <= 0;
    next.disabled = ctx.index >= ctx.list.length - 1;
    prev.addEventListener("click", function () {
      ctx.goTo(ctx.index - 1);
    });
    next.addEventListener("click", function () {
      ctx.goTo(ctx.index + 1);
    });
    row.appendChild(prev);
    row.appendChild(pos);
    row.appendChild(next);
    nav.appendChild(row);

    var pips = el("div", { cls: "ii-series-pips" });
    ctx.list.forEach(function (q, i) {
      var cls = "ii-pip";
      if (i === ctx.index) cls += " ii-current";
      if (ctx.answered[q.id]) cls += " ii-done";
      var pip = el("button", {
        cls: cls,
        text: String(i + 1),
        attrs: { type: "button", "aria-label": "Go to question " + (i + 1) }
      });
      pip.addEventListener("click", function () {
        ctx.goTo(i);
      });
      pips.appendChild(pip);
    });
    nav.appendChild(pips);
    return nav;
  }

  function openSeries(questions, opts) {
    opts = opts || {};
    var list = (questions || []).filter(function (q) {
      return !q.status || q.status === "WAITING";
    });
    if (list.length <= 1) {
      if (list.length === 1) {
        openQuestion(list[0], opts);
      }
      return;
    }
    var answered = {};
    // Per-question drafts (unsubmitted free text / selected choice), keyed by question id, so paging
    // between slides no longer discards what the user typed. The list items already carry full detail
    // (prompt/choices/allowFreeText/contextHtml/canAnswer) from the list endpoint, so slides render
    // straight from them — the previous per-navigation re-fetch is what rebuilt the form empty and
    // dropped the draft.
    var drafts = {};
    var ctx = { list: list, index: 0, answered: answered };

    // Snapshot the current slide's in-progress answer before we navigate away from it.
    function captureDraft() {
      var q = list[ctx.index];
      if (!q || answered[q.id] || !activeModal) {
        return;
      }
      var ta = activeModal.querySelector(".ii-freetext textarea");
      var radio = activeModal.querySelector('input[name="ii-choice"]:checked');
      drafts[q.id] = { freeText: ta ? ta.value : "", choiceId: radio ? radio.value : null };
    }

    function render(q) {
      var isDone = !!answered[q.id] || (q.status && q.status !== "WAITING");
      var modal = buildModalShell(q, isDone);
      modal.appendChild(buildSeriesNav(ctx));
      if (isDone) {
        renderAudit(modal, q);
      } else {
        renderForm(modal, q, {
          keepOpen: true,
          richModal: opts.richModal,
          initial: drafts[q.id],
          onDone: function (payload) {
            answered[q.id] = true;
            delete drafts[q.id];
            // Reflect the just-submitted answer on the cached item so paging back to this slide shows
            // its outcome read-only without another round-trip.
            q.status = "ANSWERED";
            q.answer = { answeredBy: "you", answeredTs: Date.now() };
            if (payload && payload.choiceId) q.answer.choiceId = payload.choiceId;
            if (payload && payload.freeText) q.answer.freeText = payload.freeText;
            ctx.next();
          }
        });
      }
      replaceModal(modal);
    }

    ctx.goTo = function (i) {
      if (i < 0 || i >= list.length) return;
      captureDraft();
      ctx.index = i;
      render(list[i]);
    };
    ctx.next = function () {
      for (var i = ctx.index + 1; i < list.length; i++) {
        if (!answered[list[i].id]) {
          ctx.goTo(i);
          return;
        }
      }
      for (var j = 0; j < list.length; j++) {
        if (!answered[list[j].id]) {
          ctx.goTo(j);
          return;
        }
      }
      // Nothing left waiting — close and let the surface refresh.
      closeModal();
      if (opts.onDone) opts.onDone();
    };

    ctx.goTo(0);
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
      // With more than one waiting, offer a single "Answer all" pager that slides through them.
      if (questionsCache.length >= 2) {
        var all = el("button", {
          cls: "ii-answer-all",
          text: "Answer all (" + questionsCache.length + ")",
          attrs: { type: "button" }
        });
        all.addEventListener("click", function () {
          toggleDropdown(false);
          openSeries(questionsCache, { richModal: richModal, onDone: refresh });
        });
        dropdown.appendChild(all);
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
    document.addEventListener("ii:answered", function () {
      refresh();
    });

    refresh().then(function () {
      scheduleLoop(refresh);
    });
  }

  // ================================ per-project widgets ================================
  function mountListWidget(mount, url, rowFactory, emptyText, seriesOpts) {
    var listWrap = el("div", { cls: "ii-widget" });
    mount.appendChild(listWrap);

    function render(questions) {
      listWrap.innerHTML = "";
      if (!questions.length) {
        listWrap.appendChild(el("div", { cls: "ii-empty", text: emptyText }));
        return;
      }
      // When answering is possible (job box) and more than one is waiting, offer the series pager.
      if (seriesOpts && questions.length >= 2) {
        var all = el("button", {
          cls: "ii-answer-all",
          text: "Answer all (" + questions.length + ")",
          attrs: { type: "button" }
        });
        all.addEventListener("click", function () {
          openSeries(questions, { richModal: seriesOpts.richModal, onDone: refresh });
        });
        listWrap.appendChild(all);
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

    document.addEventListener("ii:answered", function () {
      refresh();
    });

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
      "All caught up — nothing waiting.",
      { richModal: richModal }
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

  // ============================ live sidebar task-link count ============================
  // Keeps the left-sidebar "Interactive Input (N)" link's number live (Point 2). Core renders that
  // link server-side once per page load, so without this the count only refreshes on reload. The
  // always-present [data-ii-tasklink] controller (jobMain.jelly) polls the scoped count and updates
  // the link text, hides the row at zero, and best-effort reveals it when a question first appears.
  function mountTaskLink(mount) {
    var job = attr(mount, "data-job", "");
    if (!job) {
      return;
    }
    var jobUrl = rootUrl + "/job/" + job.split("/").join("/job/") + "/";
    var expectedHref = jobUrl + "interactive-input/";
    var url = apiBase + "/questions?job=" + encodeURIComponent(job);

    // Compare hrefs by path only, ignoring the origin and any trailing slash. Core renders this link
    // WITHOUT a trailing slash (…/interactive-input) while we build expectedHref WITH one; an exact
    // match therefore fails and would make apply() clone a duplicate sidebar row. Normalising both
    // sides fixes the match without matching a build's link (…/<n>/interactive-input).
    function normPath(href) {
      return href.replace(/^https?:\/\/[^/]+/, "").replace(/\/+$/, "");
    }
    var expectedPath = normPath(expectedHref);

    function findLink() {
      var anchors = document.querySelectorAll("#tasks a[href], #side-panel a[href], .task a[href]");
      for (var i = 0; i < anchors.length; i++) {
        if (normPath(anchors[i].getAttribute("href") || "") === expectedPath) {
          return anchors[i];
        }
      }
      return null;
    }

    function taskRow(link) {
      return (link.closest && link.closest(".task")) || link.parentNode || link;
    }

    function setLabel(link, text) {
      var span = link.querySelector(".task-link-text");
      if (span) {
        span.textContent = text;
        return;
      }
      // Fallback: rewrite the last non-empty text node so the icon (if any) is preserved.
      for (var i = link.childNodes.length - 1; i >= 0; i--) {
        var node = link.childNodes[i];
        if (node.nodeType === 3 && node.textContent.trim()) {
          node.textContent = text;
          return;
        }
      }
      link.appendChild(document.createTextNode(text));
    }

    function injectLink(text) {
      var tasks = document.querySelector("#tasks");
      if (!tasks) {
        return null;
      }
      var sample = tasks.querySelector(".task");
      if (!sample) {
        return null;
      }
      var clone = sample.cloneNode(true);
      clone.setAttribute("data-ii-injected", "true");
      var a = clone.querySelector("a[href]");
      if (!a) {
        return null;
      }
      a.setAttribute("href", expectedHref);
      a.removeAttribute("id");
      setLabel(a, text);
      tasks.appendChild(clone);
      return a;
    }

    function apply(n) {
      var text = "Interactive Input" + (n > 0 ? " (" + n + ")" : "");
      var link = findLink();
      if (n > 0) {
        if (!link) {
          link = injectLink(text);
          if (!link) {
            return; // sidebar shape unknown — nothing safe to do; reload will render it server-side
          }
        } else {
          setLabel(link, text);
        }
        taskRow(link).style.display = "";
      } else if (link) {
        taskRow(link).style.display = "none";
      }
    }

    apply(parseInt(attr(mount, "data-initial-count", "0"), 10) || 0);

    function refresh() {
      return fetchJson(url)
        .then(function (r) {
          if (r.ok && r.body && typeof r.body.count === "number") {
            apply(r.body.count);
          }
        })
        .catch(noop);
    }

    document.addEventListener("ii:answered", function () {
      refresh();
    });
    refresh().then(function () {
      scheduleLoop(refresh);
    });
  }

  // ============================ build-history badge (delegated) ============================
  // The empty red dot in the build list. Clicking it opens the answer modal in place (like the bell),
  // rather than navigating to the audit page. Delegation is used so it also works for build rows the
  // async build-history widget injects after this script runs.
  function closestBadge(node) {
    while (node && node.nodeType === 1) {
      if (node.hasAttribute && node.hasAttribute("data-ii-badge")) {
        return node;
      }
      node = node.parentNode;
    }
    return null;
  }

  function removeBadge(badge) {
    if (badge && badge.parentNode) {
      badge.parentNode.removeChild(badge);
    }
  }

  function buildQuestionsUrl(job, build) {
    return apiBase + "/questions?job=" + encodeURIComponent(job) + "&build=" + encodeURIComponent(build);
  }

  function waitingFrom(body) {
    var qs = body && Array.isArray(body.questions) ? body.questions : [];
    return qs.filter(function (q) {
      return q.status === "WAITING";
    });
  }

  // Re-check a build and drop its badge once nothing is left WAITING (handles builds with more than
  // one pending question — the dot stays until the last one is answered).
  function refreshBadge(badge, job, build) {
    fetchJson(buildQuestionsUrl(job, build))
      .then(function (r) {
        if (r.ok && !waitingFrom(r.body).length) {
          removeBadge(badge);
        }
      })
      .catch(noop);
  }

  function openBadge(badge) {
    adoptRootUrl(badge);
    var job = badge.getAttribute("data-job") || "";
    var build = badge.getAttribute("data-build") || "";
    ensureCrumb().then(function () {
      fetchJson(buildQuestionsUrl(job, build))
        .then(function (r) {
          var waiting = r.ok ? waitingFrom(r.body) : [];
          if (!waiting.length) {
            removeBadge(badge); // already settled elsewhere — clear the stale dot
            return;
          }
          var onDone = function () {
            refreshBadge(badge, job, build);
          };
          // A build with several waiting questions opens the series pager; a single one opens directly.
          if (waiting.length > 1) {
            openSeries(waiting, { richModal: richModalDefault, onDone: onDone });
          } else {
            openQuestion(waiting[0], { richModal: richModalDefault, onDone: onDone });
          }
        })
        .catch(noop);
    });
  }

  document.addEventListener("click", function (e) {
    var badge = closestBadge(e.target);
    if (!badge) {
      return;
    }
    e.preventDefault();
    openBadge(badge);
  });

  // A question answered from any other surface on the page (bell / job box) should also clear the
  // matching build's badge.
  document.addEventListener("ii:answered", function (e) {
    var d = e && e.detail;
    toArray(document.querySelectorAll("[data-ii-badge]")).forEach(function (badge) {
      var job = badge.getAttribute("data-job");
      var build = badge.getAttribute("data-build");
      if (d && d.job && d.build != null && (job !== d.job || build !== String(d.build))) {
        return; // unrelated build — leave it alone
      }
      refreshBadge(badge, job, build);
    });
  });

  // ----- bootstrap -----
  // Discover the mounts + shared config from the DOM, then wire the polling surfaces. Deferred to DOM
  // ready because on a job page this adjunct is emitted in the main panel, BEFORE the footer bell and
  // the sidebar tasklink controller exist (see the note near the top). Running before they are parsed
  // is exactly what left the bell missing inside a job and the sidebar count stale. Badge clicks are
  // handled by delegation above and need no mount.
  function discover() {
    bellMount = document.getElementById("interactive-input-bell");
    widgetMounts = toArray(document.querySelectorAll("[data-ii-widget]"));
    auditMounts = toArray(document.querySelectorAll("[data-ii-audit]"));
    taskLinkMounts = toArray(document.querySelectorAll("[data-ii-tasklink]"));
    cfgSrc = bellMount || widgetMounts[0] || auditMounts[0] || taskLinkMounts[0];
    rootUrl = (attr(cfgSrc, "data-root-url", "") || "").replace(/\/$/, "");
    apiBase = rootUrl + "/interactive-input/api/v1";
    richModalDefault = attr(cfgSrc, "data-rich-modal", "true") === "true";
    pollSeconds = Math.max(5, parseInt(attr(cfgSrc, "data-poll-seconds", "15"), 10) || 15);
  }

  function boot() {
    discover();
    if (bellMount || widgetMounts.length || auditMounts.length || taskLinkMounts.length) {
      ensureCrumb().then(function () {
        if (bellMount) mountBell(bellMount);
        widgetMounts.forEach(mountJobWidget);
        auditMounts.forEach(mountAuditWidget);
        taskLinkMounts.forEach(mountTaskLink);
      });
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
