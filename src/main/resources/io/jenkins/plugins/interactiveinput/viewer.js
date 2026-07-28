/*
 * interactive-input — review editor ("Interactive View").
 *
 * Loaded only on the review editor page (InteractiveViewRunAction/index.jelly), kept separate from
 * bell.js so the notification client stays lean. Renders a Confluence-style two-pane review UI over the
 * permission-checked REST API: rendered markdown / escaped, Prism-highlighted source on the left; a
 * general comment thread plus inline (per-line) comments on the right. Inline comments can be added from
 * BOTH the Source view (per line) and the Rendered view (per markdown block, anchored to the block's
 * source line). Also an edit-copy mode with version history; and Approve / Request changes / Reject /
 * Acknowledge — "Request changes" hands the inline comments back to the pipeline (regenerate loop).
 *
 * Security: file content is ALWAYS inserted via textContent (escaped, never executed). Only
 * server-sanitised HTML — rendered markdown (detail.renderedHtml) and rendered comment bodies
 * (comment.bodyHtml), both produced by MarkdownRenderer server-side — is ever assigned to innerHTML.
 * Every mutating request is a JSON POST carrying the Jenkins CSRF crumb (window.crumb.wrap).
 */
(function () {
  "use strict";

  var MAX_LINES_FOR_INLINE = 5000; // beyond this, fall back to a single block (general comments only)

  // ---------------------------------------------------------------- DOM + HTTP helpers
  function el(tag, opts) {
    var e = document.createElement(tag);
    opts = opts || {};
    if (opts.cls) e.className = opts.cls;
    if (opts.text != null) e.textContent = opts.text;
    if (opts.html != null) e.innerHTML = opts.html; // ONLY ever server-sanitised HTML
    if (opts.attrs) {
      Object.keys(opts.attrs).forEach(function (k) {
        e.setAttribute(k, opts.attrs[k]);
      });
    }
    return e;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
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

  function postJson(url, payload) {
    var base = { "Content-Type": "application/json" };
    var headers = window.crumb && typeof window.crumb.wrap === "function" ? window.crumb.wrap(base) : base;
    return fetchJson(url, { method: "POST", headers: headers, body: JSON.stringify(payload || {}) });
  }

  function fmtTime(ms) {
    if (!ms) return "";
    try {
      return new Date(ms).toLocaleString();
    } catch (e) {
      return "";
    }
  }

  function errorMessage(res) {
    if (res && res.body && typeof res.body === "object" && res.body.message) return res.body.message;
    if (res && res.status) return "Request failed (" + res.status + ")";
    return "Request failed";
  }

  // ---------------------------------------------------------------- per-mount controller
  function mount(root) {
    var rootUrl = (root.getAttribute("data-root-url") || "").replace(/\/$/, "");
    var apiBase = rootUrl + "/interactive-input/api/v1";
    var job = root.getAttribute("data-job") || "";
    var build = root.getAttribute("data-build") || "";
    var docId = new URLSearchParams(window.location.search).get("doc");

    var state = {
      detail: null,
      mode: "rendered", // "rendered" | "source" | "edit"
      selectedLine: 0, // 0 = none; the EXACT source line a new comment anchors to (picker choice in rendered mode)
      selectedLineEnd: 0, // 0 = exact single line (source); >= selectedBlockLo = block end line (rendered)
      selectedBlockLo: 0, // rendered mode only: first source line of the selected markdown block (picker range start)
      viewingVersion: 0, // 0 = latest
      versionContent: null, // content of a non-latest version being viewed
    };

    function viewUrl(id) {
      return apiBase + "/views/" + encodeURIComponent(id);
    }

    if (!docId) {
      loadList();
      return;
    }
    loadDetail();

    // ---- list mode (no ?doc): show this build's reviews ----
    function loadList() {
      var url = apiBase + "/views?job=" + encodeURIComponent(job) + "&build=" + encodeURIComponent(build);
      fetchJson(url)
        .then(function (res) {
          clear(root);
          if (!res.ok) {
            root.appendChild(el("div", { cls: "iv-error", text: errorMessage(res) }));
            return;
          }
          renderList(res.body.views || []);
        })
        .catch(function () {
          clear(root);
          root.appendChild(el("div", { cls: "iv-error", text: "Could not load reviews." }));
        });
    }

    // Reviews published by a glob/dir share a groupId + reportName; a lone file is its own group. The
    // listing groups cards by report/folder, tags each as "Needs approval" (review + OPEN) or "Info",
    // shows a "Notified" badge, and offers All / Notified / Needs-approval filter chips.
    function needsApproval(v) {
      return v.mode !== "info" && v.status === "OPEN";
    }

    function renderList(views) {
      root.appendChild(el("h1", { text: "Interactive View" }));
      if (!views.length) {
        root.appendChild(el("div", { cls: "iv-empty", text: "No reviews for this build." }));
        return;
      }

      var filter = { value: "all" };
      var chipsBar = el("div", { cls: "iv-filter-chips" });
      var listWrap = el("div", { cls: "iv-groups" });

      function passes(v) {
        if (filter.value === "notified") return !!v.notify;
        if (filter.value === "needs") return needsApproval(v);
        return true;
      }

      var notifiedCount = 0;
      var needsCount = 0;
      views.forEach(function (v) {
        if (v.notify) notifiedCount++;
        if (needsApproval(v)) needsCount++;
      });

      function chip(label, value) {
        var b = el("button", {
          cls: "iv-chip-btn" + (filter.value === value ? " active" : ""),
          attrs: { type: "button" },
          text: label,
        });
        b.addEventListener("click", function () {
          filter.value = value;
          var all = chipsBar.querySelectorAll(".iv-chip-btn");
          for (var i = 0; i < all.length; i++) all[i].classList.remove("active");
          b.classList.add("active");
          drawGroups();
        });
        return b;
      }
      chipsBar.appendChild(chip("All (" + views.length + ")", "all"));
      chipsBar.appendChild(chip("Notified (" + notifiedCount + ")", "notified"));
      chipsBar.appendChild(chip("Needs approval (" + needsCount + ")", "needs"));
      root.appendChild(chipsBar);
      root.appendChild(listWrap);

      function drawGroups() {
        clear(listWrap);
        var order = [];
        var groups = {};
        views.forEach(function (v) {
          if (!passes(v)) return;
          var key = v.groupId || v.id;
          if (!groups[key]) {
            groups[key] = {
              label: v.reportName || v.title || key,
              items: [],
              notify: false,
              grouped: !!v.grouped,
            };
            order.push(key);
          }
          groups[key].items.push(v);
          if (v.notify) groups[key].notify = true;
        });
        if (!order.length) {
          listWrap.appendChild(el("div", { cls: "iv-empty", text: "No reviews match this filter." }));
          return;
        }
        order.forEach(function (key) {
          var g = groups[key];
          var section = el("div", { cls: "iv-group" });
          var head = el("div", { cls: "iv-group-head" });
          head.appendChild(el("span", { cls: "iv-group-title", text: g.label }));
          head.appendChild(el("span", {
            cls: "iv-group-count",
            text: g.grouped ? g.items.length + " files" : g.items.length + " item",
          }));
          if (g.notify) head.appendChild(el("span", { cls: "iv-flag iv-flag-notified", text: "Notified" }));
          section.appendChild(head);
          var list = el("div", { cls: "iv-list" });
          g.items.forEach(function (v) {
            list.appendChild(listCard(v));
          });
          section.appendChild(list);
          listWrap.appendChild(section);
        });
      }
      drawGroups();
    }

    function listCard(v) {
      var card = el("a", { cls: "iv-list-card", attrs: { href: "?doc=" + encodeURIComponent(v.id) } });
      var titleRow = el("div", { cls: "iv-list-title-row" });
      titleRow.appendChild(el("div", { cls: "iv-list-title", text: v.title || v.reportName || v.id }));
      if (v.mode === "info") {
        titleRow.appendChild(el("span", { cls: "iv-tag iv-tag-info", text: "Info" }));
      } else if (v.status === "OPEN") {
        titleRow.appendChild(el("span", { cls: "iv-tag iv-tag-review", text: "Needs approval" }));
      }
      card.appendChild(titleRow);
      var meta = el("div", { cls: "iv-list-meta" });
      meta.appendChild(el("span", { cls: "iv-status iv-status-" + v.status, text: v.status }));
      if (v.notify) meta.appendChild(el("span", { cls: "iv-flag iv-flag-notified", text: "Notified" }));
      meta.appendChild(el("span", { text: (v.commentCount || 0) + " comment(s)" }));
      if (v.fileName) meta.appendChild(el("span", { cls: "iv-list-file", text: v.fileName }));
      card.appendChild(meta);
      return card;
    }

    // ---- detail mode ----
    function loadDetail(preserveLine) {
      var keepLine = preserveLine ? state.selectedLine : 0;
      fetchJson(viewUrl(docId))
        .then(function (res) {
          if (!res.ok) {
            clear(root);
            root.appendChild(el("div", { cls: "iv-error", text: errorMessage(res) }));
            return;
          }
          state.detail = res.body;
          state.selectedLine = keepLine;
          if (state.mode !== "source" && state.mode !== "edit") {
            state.mode = res.body.renderedHtml != null ? "rendered" : "source";
          }
          render();
        })
        .catch(function () {
          clear(root);
          root.appendChild(el("div", { cls: "iv-error", text: "Could not load this review." }));
        });
    }

    function isLatest() {
      return state.viewingVersion === 0 || state.viewingVersion === state.detail.currentVersion;
    }

    function currentContent() {
      if (!isLatest() && state.versionContent != null) return state.versionContent;
      return state.detail.content || "";
    }

    // Apply a mutation response to state.detail WITHOUT dropping the heavy content/renderedHtml fields
    // if a response omits them. The server now always returns the full document for mutations, but this
    // keeps the left pane from blanking should any endpoint ever return a summary-only payload (the
    // root cause of the earlier inline-comment "crash").
    function applyDetail(body) {
      if (!body || typeof body !== "object") {
        return;
      }
      var prev = state.detail || {};
      if (body.content == null && prev.content != null) {
        body.content = prev.content;
      }
      if (body.renderedHtml == null && prev.renderedHtml != null) {
        body.renderedHtml = prev.renderedHtml;
      }
      state.detail = body;
    }

    function render() {
      var d = state.detail;
      clear(root);
      root.appendChild(buildHeader(d));
      root.appendChild(buildToolbar(d));

      var body = el("div", { cls: "iv-body" });
      var left = el("div", { cls: "iv-pane iv-pane-content" });
      var right = el("div", { cls: "iv-pane iv-pane-comments" });
      body.appendChild(left);
      body.appendChild(right);
      root.appendChild(body);

      renderContent(left);
      renderComments(right);
    }

    function buildHeader(d) {
      var head = el("div", { cls: "iv-header" });
      var back = el("a", { cls: "iv-back", text: "\u2039 All reviews for this build", attrs: { href: "?" } });
      head.appendChild(back);

      var titleRow = el("div", { cls: "iv-title-row" });
      titleRow.appendChild(el("h1", { cls: "iv-title", text: d.title || d.reportName || d.id }));
      titleRow.appendChild(el("span", { cls: "iv-status iv-status-" + d.status, text: d.status }));
      if (d.blocking && d.status === "OPEN") {
        titleRow.appendChild(el("span", { cls: "iv-chip iv-chip-blocking", text: "Pipeline waiting" }));
      }
      head.appendChild(titleRow);

      var meta = el("div", { cls: "iv-meta" });
      meta.appendChild(el("span", { text: d.fileName || "" }));
      meta.appendChild(el("span", { text: "Published by " + (d.createdBy || "system") + " on " + fmtTime(d.createdTs) }));
      if (d.status !== "OPEN" && d.decidedBy) {
        meta.appendChild(el("span", { text: d.status.toLowerCase() + " by " + d.decidedBy + " on " + fmtTime(d.decidedTs) }));
      }
      head.appendChild(meta);
      return head;
    }

    function buildToolbar(d) {
      var bar = el("div", { cls: "iv-toolbar" });
      var leftGrp = el("div", { cls: "iv-toolbar-grp" });

      // Rendered / Source toggle (only when markdown provided a rendered view)
      if (d.renderedHtml != null) {
        var seg = el("div", { cls: "iv-seg" });
        seg.appendChild(segBtn("Rendered", state.mode === "rendered", function () {
          if (state.mode === "edit") return;
          state.mode = "rendered";
          render();
        }));
        seg.appendChild(segBtn("Source", state.mode === "source", function () {
          if (state.mode === "edit") return;
          state.mode = "source";
          render();
        }));
        leftGrp.appendChild(seg);
      }

      // Version selector
      if (d.versions && d.versions.length > 1) {
        var sel = el("select", { cls: "jenkins-select__input iv-version" });
        d.versions.forEach(function (v) {
          var latest = v.index === d.currentVersion;
          var label = "v" + v.index + (latest ? " (latest)" : "") + (v.editedBy ? " \u2014 " + v.editedBy : "");
          var opt = el("option", { text: label, attrs: { value: String(v.index) } });
          if ((state.viewingVersion === 0 && latest) || state.viewingVersion === v.index) opt.selected = true;
          sel.appendChild(opt);
        });
        sel.addEventListener("change", function () {
          var v = parseInt(sel.value, 10);
          if (v === d.currentVersion) {
            state.viewingVersion = 0;
            state.versionContent = null;
            if (state.mode === "edit") state.mode = "source";
            render();
          } else {
            viewVersion(v);
          }
        });
        leftGrp.appendChild(sel);
      }
      bar.appendChild(leftGrp);

      var rightGrp = el("div", { cls: "iv-toolbar-grp" });

      // Edit toggle (never on an informational, read-only view)
      if (d.editable && d.canContribute && d.status === "OPEN" && d.mode !== "info" && isLatest()) {
        if (state.mode === "edit") {
          var save = el("button", { cls: "jenkins-button jenkins-button--primary", text: "Save changes" });
          save.addEventListener("click", saveEdit);
          var cancel = el("button", { cls: "jenkins-button", text: "Cancel" });
          cancel.addEventListener("click", function () {
            state.mode = d.renderedHtml != null ? "rendered" : "source";
            render();
          });
          rightGrp.appendChild(save);
          rightGrp.appendChild(cancel);
        } else {
          var edit = el("button", { cls: "jenkins-button", text: "Edit" });
          edit.addEventListener("click", function () {
            state.mode = "edit";
            render();
          });
          rightGrp.appendChild(edit);
        }
      }

      // Decision buttons. Skipped for an informational (mode:info) view, which is read-only with no
      // decision. "Request changes" resolves a blocking review as CHANGES_REQUESTED and hands the inline
      // comments back to the pipeline so a generator can regenerate (the course-correction loop).
      if (d.canContribute && d.status === "OPEN" && d.mode !== "info" && state.mode !== "edit") {
        rightGrp.appendChild(decisionBtn("Approve", "approve", "jenkins-button--primary"));
        rightGrp.appendChild(decisionBtn("Request changes", "request-changes", ""));
        rightGrp.appendChild(decisionBtn("Reject", "reject", ""));
        rightGrp.appendChild(decisionBtn("Acknowledge", "acknowledge", ""));
      } else if (d.mode === "info") {
        rightGrp.appendChild(el("span", { cls: "iv-info-note", text: "Informational — no decision required" }));
      }
      bar.appendChild(rightGrp);

      var note = el("div", { cls: "iv-toolbar-note" });
      if (!isLatest()) {
        note.textContent = "Viewing version " + state.viewingVersion + " (read-only). Switch to latest to edit or comment on lines.";
        bar.appendChild(note);
      }
      return bar;
    }

    function segBtn(label, active, onClick) {
      var b = el("button", { cls: "iv-seg-btn" + (active ? " active" : ""), text: label });
      b.addEventListener("click", onClick);
      return b;
    }

    function decisionBtn(label, verb, extraCls) {
      var b = el("button", { cls: "jenkins-button " + extraCls, text: label });
      b.addEventListener("click", function () {
        b.disabled = true;
        postJson(viewUrl(docId) + "/decision", { decision: verb })
          .then(function (res) {
            if (!res.ok) {
              b.disabled = false;
              flash(errorMessage(res), true);
              return;
            }
            applyDetail(res.body);
            render();
          })
          .catch(function () {
            b.disabled = false;
            flash("Could not record decision.", true);
          });
      });
      return b;
    }

    function viewVersion(v) {
      fetchJson(viewUrl(docId) + "/raw?version=" + encodeURIComponent(v)).then(function (res) {
        if (!res.ok) {
          flash(errorMessage(res), true);
          return;
        }
        state.viewingVersion = v;
        state.versionContent = res.body.content || "";
        state.mode = "source";
        render();
      });
    }

    function saveEdit() {
      var textarea = root.querySelector(".iv-edit-area");
      if (!textarea) return;
      var content = textarea.value;
      postJson(viewUrl(docId) + "/edit", { content: content })
        .then(function (res) {
          if (!res.ok) {
            flash(errorMessage(res), true);
            return;
          }
          applyDetail(res.body);
          state.viewingVersion = 0;
          state.versionContent = null;
          state.mode = state.detail.renderedHtml != null ? "rendered" : "source";
          render();
          flash("Saved new version v" + state.detail.currentVersion + ".", false);
        })
        .catch(function () {
          flash("Could not save changes.", true);
        });
    }

    // ---- content pane ----
    function renderContent(left) {
      var d = state.detail;
      if (state.mode === "edit") {
        var wrap = el("div", { cls: "iv-edit-wrap" });
        var ta = el("textarea", { cls: "iv-edit-area", attrs: { spellcheck: "false" } });
        ta.value = d.content || "";
        wrap.appendChild(ta);
        left.appendChild(wrap);
        return;
      }
      if (state.mode === "rendered" && d.renderedHtml != null) {
        // Server-sanitised markdown HTML (MarkdownRenderer) — safe to insert.
        var rendered = el("div", { cls: "iv-rendered", html: d.renderedHtml });
        left.appendChild(rendered);
        decorateRenderedBlocks(rendered);
        return;
      }
      renderSource(left);
    }

    // Add inline-comment affordances to the rendered markdown: each top-level block carries a
    // data-source-line (added server-side by MarkdownRenderer). We wrap each block so a hover "+" (add)
    // or a count marker (existing comments) can be positioned in a left gutter without invalid nesting,
    // and clicking either opens the block's thread in the right pane. Comments reuse the SAME line model
    // as the Source view (anchored to the block's start line), so they round-trip to the pipeline.
    function decorateRenderedBlocks(rendered) {
      var d = state.detail;
      var blocks = Array.prototype.slice.call(rendered.querySelectorAll("[data-source-line]"));
      if (!blocks.length) return;
      var canComment = d.commentable && d.canContribute && isLatest();
      var commentsByLine = groupLineComments();
      var lineCount = currentContent().split(/\r\n|\r|\n/).length;
      var starts = blocks.map(function (b) {
        return parseInt(b.getAttribute("data-source-line"), 10) || 1;
      });

      blocks.forEach(function (block, i) {
        var lo = i === 0 ? 1 : starts[i];
        // The last block runs to the end of the file; cap to the real line count so the picker never
        // offers non-existent lines (previously Number.MAX_SAFE_INTEGER).
        var hi = i < blocks.length - 1 ? starts[i + 1] - 1 : lineCount;
        if (hi < lo) hi = lo;
        var anchor = starts[i];

        var count = 0;
        Object.keys(commentsByLine).forEach(function (ln) {
          var n = parseInt(ln, 10);
          if (n >= lo && n <= hi) count += commentsByLine[n].length;
        });

        var wrap = el("div", { cls: "iv-rblock" });
        block.parentNode.insertBefore(wrap, block);
        wrap.appendChild(block);
        if (count > 0) wrap.classList.add("has-comments");
        if (state.mode === "rendered" && state.selectedLineEnd && state.selectedLine >= lo && state.selectedLine <= hi) {
          wrap.classList.add("selected");
        }

        if (count > 0) {
          var marker = el("span", { cls: "iv-rblock-marker", text: String(count), attrs: { title: count + " comment(s) — click to view" } });
          marker.addEventListener("click", function () {
            selectBlock(anchor, hi, wrap);
          });
          wrap.appendChild(marker);
        } else if (canComment) {
          var add = el("button", { cls: "iv-rblock-add", text: "+", attrs: { title: "Comment on this section", "aria-label": "Comment on this section" } });
          add.addEventListener("click", function (e) {
            e.preventDefault();
            selectBlock(anchor, hi, wrap);
          });
          wrap.appendChild(add);
        }
      });
    }

    // Select a rendered block's source-line range: shows its thread (comments within [start,end]) plus a
    // line-picker (renderComments) so a new comment can be anchored to any exact source line in the block.
    // selectedLine defaults to the block start; the picker updates it.
    function selectBlock(startLine, endLine, wrapEl) {
      state.selectedBlockLo = startLine;
      state.selectedLine = startLine;
      state.selectedLineEnd = endLine;
      var sel = root.querySelectorAll(".iv-rblock.selected");
      for (var i = 0; i < sel.length; i++) sel[i].classList.remove("selected");
      if (wrapEl) wrapEl.classList.add("selected");
      renderComments(root.querySelector(".iv-pane-comments"));
      var composer = root.querySelector(".iv-line-thread .iv-composer textarea");
      if (composer) composer.focus();
    }

    function renderSource(left) {
      var d = state.detail;
      var content = currentContent();
      var lang = d.language && d.language !== "none" ? d.language : "";
      var lines = content.split(/\r\n|\r|\n/);
      var commentsByLine = groupLineComments();

      if (!isLatest() || lines.length > MAX_LINES_FOR_INLINE) {
        // Single block: correct multi-line highlighting, but no per-line commenting.
        var pre = el("pre", { cls: "iv-code-block line-numbers" });
        var code = el("code", { cls: lang ? "language-" + lang : "" });
        code.textContent = content;
        pre.appendChild(code);
        left.appendChild(pre);
        highlight(left);
        if (isLatest() && lines.length > MAX_LINES_FOR_INLINE) {
          left.appendChild(el("div", { cls: "iv-note", text: "File is large (" + lines.length + " lines); inline line comments are disabled. Use general comments." }));
        }
        return;
      }

      var container = el("div", { cls: "iv-code" });
      lines.forEach(function (text, i) {
        var n = i + 1;
        var row = el("div", { cls: "iv-line", attrs: { "data-line": String(n) } });
        if (state.selectedLine === n) row.classList.add("selected");
        if (commentsByLine[n]) row.classList.add("has-comments");

        var add = el("button", { cls: "iv-line-add", text: "+", attrs: { title: "Comment on line " + n, "aria-label": "Comment on line " + n } });
        add.addEventListener("click", function () {
          selectLine(n);
        });
        row.appendChild(add);
        row.appendChild(el("span", { cls: "iv-lnum", text: String(n) }));

        var code = el("code", { cls: "iv-lcode" + (lang ? " language-" + lang : "") });
        code.textContent = text.length ? text : "\u00a0";
        row.appendChild(code);

        if (commentsByLine[n]) {
          var marker = el("span", { cls: "iv-line-marker", text: String(commentsByLine[n].length) });
          marker.addEventListener("click", function () {
            selectLine(n);
          });
          row.appendChild(marker);
        }
        container.appendChild(row);
      });
      left.appendChild(container);
      highlight(left);
    }

    function highlight(scope) {
      if (window.Prism && typeof window.Prism.highlightAllUnder === "function") {
        try {
          window.Prism.highlightAllUnder(scope);
        } catch (e) {
          /* highlighting is best-effort; escaped source is already shown */
        }
      }
    }

    function selectLine(n) {
      state.selectedLine = n;
      state.selectedLineEnd = 0; // exact single line (Source view)
      state.selectedBlockLo = 0; // no block range in Source view (no picker)
      // update row selection without a full re-render
      var rows = root.querySelectorAll(".iv-line.selected");
      for (var i = 0; i < rows.length; i++) rows[i].classList.remove("selected");
      var row = root.querySelector('.iv-line[data-line="' + n + '"]');
      if (row) row.classList.add("selected");
      renderComments(root.querySelector(".iv-pane-comments"));
      var composer = root.querySelector(".iv-line-thread .iv-composer textarea");
      if (composer) composer.focus();
    }

    // ---- comments pane ----
    function groupLineComments() {
      var map = {};
      (state.detail.comments || []).forEach(function (c) {
        if (c.line >= 1) {
          (map[c.line] = map[c.line] || []).push(c);
        }
      });
      return map;
    }

    function renderComments(right) {
      if (!right) return;
      clear(right);
      var d = state.detail;
      var canComment = d.commentable && d.canContribute && isLatest();

      // General thread
      var gen = el("div", { cls: "iv-thread iv-general-thread" });
      gen.appendChild(el("h2", { cls: "iv-thread-title", text: "General comments" }));
      var generals = (d.comments || []).filter(function (c) {
        return c.line == null || c.line < 1;
      });
      appendCommentList(gen, generals);
      if (canComment) gen.appendChild(buildComposer(-1));
      else if (!d.commentable) gen.appendChild(el("div", { cls: "iv-hint", text: "Comments are disabled for this review." }));
      right.appendChild(gen);

      // Line thread (selected). In Source view a single exact line is selected. In Rendered view a click
      // selects a markdown block spanning source lines [selectedBlockLo, selectedLineEnd]; a line-picker
      // then lets the reviewer anchor the new comment to any exact line inside that block, so comments
      // round-trip identically to the Source view (and to the pipeline).
      var lineThread = el("div", { cls: "iv-thread iv-line-thread" });
      if (state.selectedLine >= 1) {
        var isBlock = state.mode === "rendered" && state.selectedBlockLo >= 1 && state.selectedLineEnd > state.selectedBlockLo;
        var lo = isBlock ? state.selectedBlockLo : state.selectedLine;
        var hi = isBlock ? state.selectedLineEnd : state.selectedLine;
        if (state.selectedLine < lo || state.selectedLine > hi) state.selectedLine = lo;
        var titleRow = el("div", { cls: "iv-line-thread-head" });
        var titleText = isBlock ? "Selected section (lines " + lo + "\u2013" + hi + ")" : "Line " + lo;
        titleRow.appendChild(el("h2", { cls: "iv-thread-title", text: titleText }));
        var clearBtn = el("button", { cls: "iv-clear-line", text: "\u00d7", attrs: { title: "Clear selection" } });
        clearBtn.addEventListener("click", function () {
          state.selectedLine = 0;
          state.selectedLineEnd = 0;
          state.selectedBlockLo = 0;
          var selRows = root.querySelectorAll(".iv-line.selected, .iv-rblock.selected");
          for (var i = 0; i < selRows.length; i++) selRows[i].classList.remove("selected");
          renderComments(right);
        });
        titleRow.appendChild(clearBtn);
        lineThread.appendChild(titleRow);
        // Rendered-view, multi-line block: pick the exact source line to anchor the new comment to.
        if (isBlock && canComment) lineThread.appendChild(buildLinePicker(lo, hi));
        var lineComments = (d.comments || []).filter(function (c) {
          return c.line >= lo && c.line <= hi;
        });
        appendCommentList(lineThread, lineComments);
        // For a block the anchor is dynamic (the picker updates state.selectedLine); for a single line it is fixed.
        if (canComment) lineThread.appendChild(buildComposer(isBlock ? function () { return state.selectedLine; } : lo));
      } else {
        var hintText =
          state.mode === "rendered"
            ? "Hover a section and click + to pick a line and comment on it."
            : "Click the + on any line to comment on it.";
        lineThread.appendChild(el("div", { cls: "iv-hint", text: hintText }));
      }
      right.appendChild(lineThread);

      // All line comments (navigation)
      var byLine = groupLineComments();
      var lineNums = Object.keys(byLine).map(Number).sort(function (a, b) {
        return a - b;
      });
      if (lineNums.length) {
        var nav = el("div", { cls: "iv-thread iv-line-nav" });
        nav.appendChild(el("h2", { cls: "iv-thread-title", text: "Line comments (" + lineNums.length + ")" }));
        lineNums.forEach(function (n) {
          var item = el("button", { cls: "iv-line-nav-item" });
          item.appendChild(el("span", { cls: "iv-chip", text: "L" + n }));
          item.appendChild(el("span", { text: byLine[n].length + " comment(s)" }));
          item.addEventListener("click", function () {
            if (state.mode !== "source") {
              state.mode = "source";
              render();
            }
            selectLine(n);
            var row = root.querySelector('.iv-line[data-line="' + n + '"]');
            if (row && row.scrollIntoView) row.scrollIntoView({ block: "center" });
          });
          nav.appendChild(item);
        });
        right.appendChild(nav);
      }
    }

    // Rendered-view line-picker: a markdown block can span several source lines, so let the reviewer
    // anchor the comment to an EXACT line. Lists the non-blank source lines in [lo,hi] as "L{n}: {snippet}";
    // choosing one sets state.selectedLine so buildComposer posts comment.line = that line.
    function buildLinePicker(lo, hi) {
      var wrap = el("div", { cls: "iv-line-picker" });
      wrap.appendChild(el("label", { cls: "iv-line-picker-label", text: "Comment on line:", attrs: { "for": "iv-line-picker-sel" } }));
      var sel = el("select", { cls: "jenkins-select__input iv-line-picker-sel", attrs: { id: "iv-line-picker-sel" } });
      var srcLines = currentContent().split(/\r\n|\r|\n/);
      var matched = false;
      var firstLine = 0;
      for (var n = lo; n <= hi && n <= srcLines.length; n++) {
        var raw = (srcLines[n - 1] || "").trim();
        if (!raw) continue; // blank source line — nothing to anchor to
        if (!firstLine) firstLine = n;
        var snippet = raw.length > 60 ? raw.slice(0, 60) + "\u2026" : raw;
        var opt = el("option", { text: "L" + n + ": " + snippet, attrs: { value: String(n) } });
        if (n === state.selectedLine) {
          opt.selected = true;
          matched = true;
        }
        sel.appendChild(opt);
      }
      if (!firstLine) {
        // whole block is blank (unlikely) — fall back to its first line
        sel.appendChild(el("option", { text: "L" + lo, attrs: { value: String(lo) } }));
        state.selectedLine = lo;
      } else if (!matched) {
        // the current anchor is a blank/out-of-range line — snap to the first selectable line
        state.selectedLine = firstLine;
        sel.value = String(firstLine);
      }
      sel.addEventListener("change", function () {
        var v = parseInt(sel.value, 10);
        if (v >= 1) {
          state.selectedLine = v;
          var cta = wrap.parentNode ? wrap.parentNode.querySelector(".iv-composer textarea") : null;
          if (cta) cta.setAttribute("placeholder", "Comment on line " + v + " (markdown)\u2026");
        }
      });
      wrap.appendChild(sel);
      return wrap;
    }

    function appendCommentList(container, comments) {
      if (!comments.length) {
        container.appendChild(el("div", { cls: "iv-hint", text: "No comments yet." }));
        return;
      }
      var list = el("div", { cls: "iv-comment-list" });
      comments.forEach(function (c) {
        var item = el("div", { cls: "iv-comment" + (c.resolved ? " resolved" : "") });
        var head = el("div", { cls: "iv-comment-head" });
        head.appendChild(el("span", { cls: "iv-comment-author", text: c.author || "unknown" }));
        head.appendChild(el("span", { cls: "iv-comment-time", text: fmtTime(c.createdTs) }));
        if (c.line >= 1) {
          var chip = el("button", { cls: "iv-chip iv-chip-line", text: "L" + c.line });
          chip.addEventListener("click", function () {
            if (state.mode !== "source") {
              state.mode = "source";
              render();
            }
            selectLine(c.line);
          });
          head.appendChild(chip);
        }
        item.appendChild(head);
        // bodyHtml is server-sanitised markdown (MarkdownRenderer) — safe to insert.
        item.appendChild(el("div", { cls: "iv-comment-body", html: c.bodyHtml || "" }));

        if (state.detail.canContribute) {
          var foot = el("div", { cls: "iv-comment-foot" });
          var toggle = el("button", { cls: "iv-link-btn", text: c.resolved ? "Reopen" : "Resolve" });
          toggle.addEventListener("click", function () {
            postJson(viewUrl(docId) + "/resolveComment", { commentId: c.id, resolved: !c.resolved }).then(function (res) {
              if (!res.ok) {
                flash(errorMessage(res), true);
                return;
              }
              applyDetail(res.body);
              renderComments(root.querySelector(".iv-pane-comments"));
            });
          });
          foot.appendChild(toggle);
          item.appendChild(foot);
        }
        list.appendChild(item);
      });
      container.appendChild(list);
    }

    // line is a fixed line number (Source view / single-line block / -1 for general) OR a getter function
    // returning the current anchor line (Rendered view, where the picker changes state.selectedLine).
    function buildComposer(line) {
      function anchorLine() {
        return typeof line === "function" ? line() : line;
      }
      var initial = anchorLine();
      var wrap = el("div", { cls: "iv-composer" });
      var ta = el("textarea", { attrs: { rows: "3", placeholder: initial >= 1 ? "Comment on line " + initial + " (markdown)\u2026" : "Add a general comment (markdown)\u2026" } });
      wrap.appendChild(ta);
      var row = el("div", { cls: "iv-composer-row" });
      var err = el("span", { cls: "iv-composer-err" });
      var submit = el("button", { cls: "jenkins-button jenkins-button--primary", text: "Add comment" });
      submit.addEventListener("click", function () {
        var body = ta.value.trim();
        if (!body) {
          err.textContent = "Please enter a comment.";
          return;
        }
        err.textContent = "";
        submit.disabled = true;
        var ln = anchorLine();
        var payload = { body: body };
        if (ln >= 1) payload.line = ln;
        postJson(viewUrl(docId) + "/comments", payload)
          .then(function (res) {
            submit.disabled = false;
            if (!res.ok) {
              err.textContent = errorMessage(res);
              return;
            }
            applyDetail(res.body);
            // A line/block comment adds a gutter marker on the content pane, so re-render both panes
            // (the selection persists via state.selectedLine); a general comment only touches the right.
            if (ln >= 1) {
              render();
            } else {
              renderComments(root.querySelector(".iv-pane-comments"));
            }
          })
          .catch(function () {
            submit.disabled = false;
            err.textContent = "Could not add comment.";
          });
      });
      row.appendChild(submit);
      row.appendChild(err);
      wrap.appendChild(row);
      return wrap;
    }

    function flash(msg, isError) {
      var f = root.querySelector(".iv-flash");
      if (!f) {
        f = el("div", { cls: "iv-flash" });
        root.insertBefore(f, root.firstChild);
      }
      f.textContent = msg;
      f.className = "iv-flash" + (isError ? " iv-flash-error" : " iv-flash-ok");
      window.setTimeout(function () {
        if (f && f.parentNode) f.parentNode.removeChild(f);
      }, 4000);
    }
  }

  // Client-side filter for the server-rendered per-job review table (grouped by report, one <tbody> per
  // group with a data-iv-group-row header). Narrows rows by free text and All / Notified / Needs-approval,
  // and hides a group heading when none of its rows match. No backend calls — pure DOM filtering.
  function mountTableFilter(container) {
    var tableId = container.getAttribute("data-iv-table-filter");
    var table = tableId ? document.getElementById(tableId) : null;
    if (!table) return;
    var search = container.querySelector(".iv-filter-search");
    var chips = container.querySelectorAll(".iv-chip-btn");
    var st = { q: "", filter: "all" };

    function rowMatches(row) {
      var text = (row.getAttribute("data-iv-text") || "").toLowerCase();
      var status = row.getAttribute("data-iv-status") || "";
      var notify = row.getAttribute("data-iv-notify") === "true";
      var mode = row.getAttribute("data-iv-mode") || "review";
      if (st.q && text.indexOf(st.q) < 0) return false;
      if (st.filter === "notified" && !notify) return false;
      if (st.filter === "needs" && !(mode !== "info" && status === "OPEN")) return false;
      return true;
    }

    function apply() {
      st.q = search ? search.value.toLowerCase() : "";
      var bodies = table.tBodies;
      for (var b = 0; b < bodies.length; b++) {
        var rows = bodies[b].rows;
        var headers = [];
        var anyVisible = false;
        for (var i = 0; i < rows.length; i++) {
          var row = rows[i];
          if (row.getAttribute("data-iv-group-row") === "true") {
            headers.push(row);
            continue;
          }
          var ok = rowMatches(row);
          row.style.display = ok ? "" : "none";
          if (ok) anyVisible = true;
        }
        for (var h = 0; h < headers.length; h++) headers[h].style.display = anyVisible ? "" : "none";
      }
    }

    if (search) {
      search.addEventListener("input", apply);
    }
    for (var i = 0; i < chips.length; i++) {
      (function (chip) {
        chip.addEventListener("click", function () {
          st.filter = chip.getAttribute("data-filter") || "all";
          for (var j = 0; j < chips.length; j++) chips[j].classList.remove("active");
          chip.classList.add("active");
          apply();
        });
      })(chips[i]);
    }
    apply();
  }

  function init() {
    var roots = document.querySelectorAll(".iv-app[data-iv-root]");
    for (var i = 0; i < roots.length; i++) mount(roots[i]);
    var filters = document.querySelectorAll("[data-iv-table-filter]");
    for (var k = 0; k < filters.length; k++) mountTableFilter(filters[k]);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
