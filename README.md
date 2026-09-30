# codepad — Android

A code editor for your phone that opens and saves real files, runs JavaScript
and renders HTML, and **asks for no permissions at all.**

Open `app/src/main/AndroidManifest.xml`. There is not one `<uses-permission>`
line in it — not storage, not even internet. The app has no way to send what
you type anywhere, because it has no way to reach the network.

Sixteen languages are highlighted. Two of them run.

## What it does

**Files.** Tap **Open** and Android's own file picker appears. You pick one
file, the app gets that one file, and nothing else on the phone is reachable —
that is how it needs no storage permission. **Save** writes back to the same
file. The language follows the extension, so opening a `.rs` gives you Rust
highlighting.

Tapping a code file in any file manager opens it here, and sharing text from
another app drops it into the editor.

**Running.** Tap **Run**.

- **JavaScript** runs in a Web Worker. `console.log` appears in the output
  panel, and so does the value of the last expression. An endless loop is
  stopped after five seconds instead of freezing the app.
- **HTML** renders in a sandboxed frame below the editor, with its CSS and
  JavaScript working.

**Highlighted, not run:** TypeScript, Java, C, C#, Go, Rust, PHP, Ruby, SQL,
Shell, CSS, JSON, YAML, Markdown — and Python, for the reason below. The app
says so plainly when you press Run on one of them rather than pretending.

## Why Python does not run here

Python in codepad is [Pyodide](https://pyodide.org) — real CPython compiled to
WebAssembly, about ten megabytes, downloaded the first time you use it.

Downloading it needs the INTERNET permission. Having that permission would
mean the app *could* send what you type somewhere, and then "nothing leaves
your phone" would be a promise instead of a fact.

So this build keeps the permission off and drops Python instead. One file does
it — [`js/offline.js`](app/src/main/assets/js/offline.js) — and a unit test
checks both halves still hold: that Python is really marked unrunnable, and
that the manifest still has no permission in it.

If you want Python on a phone, the
[browser version](https://proburakelci.github.io/codepad/) runs the full thing
in Chrome, where you have already decided to let a browser use the network.

## Build it

You need Android Studio, or just a JDK 17 and the Android SDK.

```bash
git clone https://github.com/ProBurakElci/codepad-android
cd codepad-android
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. `./gradlew installDebug`
puts it on a connected phone.

## Tests

```bash
./gradlew test
```

No device, no emulator — plain JVM, so this runs in CI. Twenty-two of them.

They cover the things a phone app has no interface to catch:

- every extension maps to a language the editor really has, checked by reading
  the editor's own language table out of the assets, so renaming a language in
  `highlight.js` fails this build
- no extension is claimed by two languages
- a filename suggested for saving cannot carry a path, a quote or a `..` out
  of the editor
- every script and stylesheet the page loads actually exists in `assets/`
- the three load orders that are load-bearing are in the right order
- messages from the app arrive at the page as JSON through one entry point, so
  a file named `'); doSomething();//` is a filename and not a program
- the WebView cannot reach the filesystem
- saving truncates, so saving a shorter file cannot leave the tail of the old
  one behind
- the bundled copy of the editor has not drifted from the web one

And on a real device or emulator:

```bash
tools/check-on-device.sh
```

That installs the APK and checks the things a unit test cannot: that the app
opens at all, that the **installed package holds and requests no permissions**
— read off `dumpsys`, not off the source — and that opening a file and
pressing Back do not crash it. An APK that builds perfectly can still die the
moment it launches, and `./gradlew test` would never notice.

## How it is put together

`app/src/main/assets/` is the browser version of codepad, unchanged, plus two
files:

- `js/offline.js` takes Python out, before the editor is built
- `js/android.js` turns Share into Open and Download into Save, adds the
  filename to the header, and collapses the output pane while the keyboard is
  up

Both return immediately if the app is not around them, which is how the same
files run on a phone and on a web page.

The Kotlin side is three files. `MainActivity` owns the WebView and the system
file pickers, `Documents` reads and writes the files the user chose, and
`FileTypes` is the extension-to-language table — kept apart from Android so it
can be unit tested.

The editor is served from `https://appassets.androidplatform.net` rather than
`file:///android_asset`, and that is not cosmetic: a page on `file://` is not
a secure origin, and a **Web Worker refuses to start there.** The JavaScript
runner is built on a worker — that is what keeps an endless loop from freezing
the app — so on `file://` the app's main feature would not work at all.

The launcher icon is drawn by [`tools/make-icons.js`](tools/make-icons.js)
rather than pasted in as binaries nobody can review.

## The rest of it

Same editor, three places:

- **[proburakelci.github.io/codepad](https://proburakelci.github.io/codepad/)** — in a browser, nothing to install, Python included
- **[codepad-desktop](https://github.com/ProBurakElci/codepad-desktop)** — Windows, macOS and Linux
- this one — Android

## Licence

MIT. Do what you like with it.
