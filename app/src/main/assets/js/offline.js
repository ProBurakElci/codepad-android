/*
 * What this build cannot do, said out loud before anything else runs.
 *
 * The Android app asks for no permissions at all - look at
 * AndroidManifest.xml, there is not a single <uses-permission> line, including
 * INTERNET. That is the point of it: nothing you type can leave the phone
 * because the app has no way to send it.
 *
 * Python in codepad is Pyodide, which is CPython compiled to WebAssembly and
 * about ten megabytes downloaded on first use. With no INTERNET permission
 * that download cannot happen, so Python is highlighted here and not run.
 *
 * Rather than let the app offer Python and then fail, this file marks it as
 * not runnable before the editor is built. Everything downstream - the ▸ in
 * the language menu, the badge, the message when you press Run - reads the
 * same table, so all of it corrects itself.
 *
 * Loaded after highlight.js and samples.js, and before app.js. Order matters:
 * it edits what those two set up, and app.js reads the result.
 */
(function (global) {
  "use strict";

  if (!global.AndroidHost) return; // opened in a browser: nothing to take away
  if (!global.Highlight || !global.Highlight.LANGUAGES) return;

  const python = global.Highlight.LANGUAGES.python;
  if (python) python.run = null;

  /*
   * The Python example opens with "Runs for real. Python is downloaded the
   * first time you press Run." - true everywhere else, and a straight lie
   * here. An app that contradicts itself on its own first screen is worse
   * than one that is simply missing a feature.
   */
  const samples = global.Samples;
  if (samples && samples.SAMPLES && samples.SAMPLES.python) {
    samples.SAMPLES.python = samples.SAMPLES.python.replace(
      /^#[^\n]*\n/,
      "# Highlighted here, not run: this app has no internet permission, and\n" +
      "# Python is a 10 MB download, so it cannot be fetched.\n" +
      "# The browser version runs it: proburakelci.github.io/codepad\n"
    );
  }
})(typeof window !== "undefined" ? window : globalThis);
