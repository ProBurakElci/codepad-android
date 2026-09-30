/*
 * The phone half of the editor.
 *
 * Loaded after app.js, and does nothing at all in a browser: if AndroidHost is
 * missing, this file returns immediately and the page stays the web app. That
 * is what keeps one copy of the editor working on a phone and on a web page.
 *
 * Everything that crosses into the app goes through AndroidHost, and
 * everything coming back arrives at window.androidReceive as one JSON string.
 */
(function () {
  "use strict";

  const host = window.AndroidHost;
  if (!host) return;

  const api = window.codepad;
  if (!api) return;

  document.body.classList.add("is-android");

  let fileName = null;

  /* ---------------- the toolbar ---------------- */

  /*
   * On a phone the web build's buttons are wrong: Share puts the snippet in
   * the address bar, and Download drops it in a folder nobody can find. Both
   * become the things a phone actually has.
   */
  function relabel(id, label, title, onClick) {
    const button = document.querySelector(id);
    if (!button) return;
    button.textContent = label;
    button.title = title;
    button.onclick = onClick;
  }

  relabel("#share", "Open", "Open a file", function () { host.openFile(); });
  relabel("#download", "Save", "Save this file", function () { requestSave(); });

  /*
   * Save as and Send live behind one extra button rather than crowding a
   * phone-width toolbar.
   */
  const toolbar = document.querySelector("#share") && document.querySelector("#share").parentNode;
  if (toolbar) {
    const more = document.createElement("button");
    more.id = "more";
    more.type = "button";
    // Without this it falls back to the browser's own button, which is a
    // light grey slab and looks broken sitting next to the real ones.
    more.className = "btn";
    more.textContent = "More";
    more.title = "Save as, send, new file";
    more.onclick = function () { showSheet(sheet.hidden); };
    toolbar.appendChild(more);
  }

  const sheet = document.createElement("div");
  sheet.id = "sheet";
  sheet.hidden = true;
  sheet.innerHTML =
    '<button type="button" data-do="saveAs">Save as a new file</button>' +
    '<button type="button" data-do="send">Send this snippet</button>' +
    '<button type="button" data-do="new">Start a new file</button>' +
    '<button type="button" data-do="source">Source code</button>';
  document.body.appendChild(sheet);

  /*
   * The app needs to know this is up, because Back must close the sheet
   * rather than the whole app - a hardware key the page cannot see.
   */
  function showSheet(show) {
    sheet.hidden = !show;
    host.setOverlayOpen(show);
  }

  sheet.addEventListener("click", function (event) {
    const what = event.target.getAttribute("data-do");
    if (!what) return;
    showSheet(false);

    if (what === "saveAs") host.saveAs(api.getCode(), api.getExtension());
    if (what === "send") host.share(api.getCode());
    if (what === "new") host.newFile();
    if (what === "source") host.openSourceCode();
  });

  /* tapping anywhere else closes it */
  document.addEventListener("click", function (event) {
    if (sheet.hidden) return;
    if (sheet.contains(event.target) || event.target.id === "more") return;
    showSheet(false);
  }, true);

  /* ---------------- name in the header ---------------- */

  const title = document.createElement("div");
  title.id = "filename";
  const header = document.querySelector("header") || document.body;
  header.appendChild(title);

  function showName(dirty) {
    title.textContent = (dirty ? "• " : "") + (fileName || "Untitled");
  }
  showName(false);

  /* ---------------- dirty state ---------------- */

  api.onChange(function () {
    host.setDirty(true);
    showName(true);
  });

  /* ---------------- saving ---------------- */

  /*
   * Named on window because the app calls it on the way out, when the user
   * chose Save in the "you have unsaved changes" dialog. The app closes only
   * after the save comes back, so this must not be the last word.
   */
  function requestSave() {
    host.save(api.getCode(), api.getExtension());
  }
  window.androidRequestSave = requestSave;

  /* ---------------- messages from the app ---------------- */

  const handlers = {
    opened: function (data) {
      // An extension nobody recognises leaves the language alone rather than
      // dropping the file into JavaScript highlighting without saying so.
      if (data.language) api.setLanguage(data.language);
      api.setCode(data.text);
      fileName = data.name;
      showName(false);
      host.setDirty(false);
      api.note("opened " + data.name +
        (data.language ? "" : "  (unfamiliar extension - still showing " + api.getLanguage() + ")"));
    },

    saved: function (data) {
      fileName = data.name;
      showName(false);
      host.setDirty(false);
      api.note("saved to " + data.name);
    },

    setCode: function (data) {
      api.setCode(data.text);
      fileName = null;
      showName(false);
      host.setDirty(false);
    },

    named: function (data) {
      fileName = data.name === null ? null : data.name;
      showName(false);
    },

    note: function (data) {
      api.note(data.text);
    },

    /* Back was pressed while the sheet was up. */
    closeOverlay: function () {
      showSheet(false);
    },
  };

  /*
   * One entry point, one JSON string. The app builds it with a real JSON
   * encoder rather than gluing strings together, so a file called
   * `'); something();//` is a filename and not a program.
   */
  window.androidReceive = function (raw) {
    let message;
    try {
      message = JSON.parse(raw);
    } catch (err) {
      return; // nothing we can usefully do with a malformed message
    }

    const handler = handlers[message && message.event];
    if (handler) handler(message.data || {});
  };

  /* ---------------- the keyboard ---------------- */

  /*
   * A phone keyboard covers half the screen. While the code has focus the
   * output pane collapses, so the editor keeps the space that is left instead
   * of being squeezed into three visible lines.
   */
  const input = document.querySelector("#input");
  if (input) {
    /*
     * The web build focuses the editor on load, which is right on a desktop
     * and wrong here twice over: it throws the keyboard up over half the
     * screen before the user has asked for anything, and the scroll to the
     * caret leaves the file starting partway down. Let them tap the code
     * when they want it.
     */
    input.blur();
    input.scrollTop = 0;
    input.scrollLeft = 0;

    input.addEventListener("focus", function () {
      document.body.classList.add("typing");
    });
    input.addEventListener("blur", function () {
      document.body.classList.remove("typing");
    });
  }

  /*
   * The web build focuses the editor after you pick a language, which is
   * right with a keyboard already under your hands and wrong with one that
   * has to cover half the screen to appear - especially here, where picking
   * Python writes an explanation into the output pane that the keyboard
   * would immediately hide. This listener runs after the one in app.js.
   */
  const languageMenu = document.querySelector("#language");
  if (languageMenu && input) {
    languageMenu.addEventListener("change", function () {
      input.blur();
      document.body.classList.remove("typing");
    });
  }

  // Pressing Run should show the output again even mid-typing.
  const runButton = document.querySelector("#run");
  if (runButton) {
    runButton.addEventListener("click", function () {
      document.body.classList.remove("typing");
      if (input) input.blur();
    });
  }

  api.note("tap Open to edit a file on this phone");
  host.ready();
})();
