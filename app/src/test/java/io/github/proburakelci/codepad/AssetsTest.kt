package io.github.proburakelci.codepad

import io.github.proburakelci.codepad.FileTypesTest.Companion.assetFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor itself is HTML, CSS and JavaScript in assets/, which Gradle
 * copies into the APK without looking at it. Nothing in the Android build
 * would notice if a script tag were misspelled or a file went missing, and
 * the app would simply show a blank screen on somebody's phone.
 *
 * So the page is checked here as text. These are not clever tests; they are
 * the ones that catch the mistakes that actually happen.
 */
class AssetsTest {

    private val html: String by lazy { assetFile("index.html").readText() }

    @Test
    fun `every script the page loads exists`() {
        val missing = Regex("""<script src="([^"]+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("http") }
            .filterNot { assetFile(it).exists() }
            .toList()

        assertEquals("the page loads scripts that are not in assets/", emptyList<String>(), missing)
    }

    @Test
    fun `every stylesheet the page loads exists`() {
        val missing = Regex("""<link[^>]+href="([^"]+\.css)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("http") }
            .filterNot { assetFile(it).exists() }
            .toList()

        assertEquals(emptyList<String>(), missing)
    }

    /*
     * Order is load-bearing three times over, and getting any of it wrong
     * gives a working-looking page that is subtly broken:
     *
     *   offline.js must run before app.js, or the language menu is built
     *     while Python still claims it can run.
     *   android.js must run after app.js, or window.codepad does not exist
     *     yet and the phone toolbar never gets wired up.
     *   android.css must come after styles.css, or the desktop layout wins.
     */
    @Test
    fun `the page loads things in the order that works`() {
        assertTrue(
            "offline.js must come before app.js",
            html.indexOf("js/offline.js") in 1 until html.indexOf("js/app.js"),
        )
        assertTrue(
            "android.js must come after app.js",
            html.indexOf("js/android.js") > html.indexOf("js/app.js"),
        )
        assertTrue(
            "android.css must come after styles.css",
            html.indexOf("android.css") > html.indexOf("styles.css"),
        )
        assertTrue(
            "highlight.js must come before offline.js, which edits its table",
            html.indexOf("js/highlight.js") in 1 until html.indexOf("js/offline.js"),
        )
        assertTrue(
            "samples.js must come before offline.js, which rewrites the Python example",
            html.indexOf("js/samples.js") in 1 until html.indexOf("js/offline.js"),
        )
    }

    /*
     * The app has no INTERNET permission. A page that tried to fetch a script
     * or a stylesheet would show a broken editor rather than an error, so the
     * markup must not ask for anything off the device.
     */
    @Test
    fun `the page fetches nothing from the internet`() {
        assertFalse(
            "a script is loaded from another site",
            Regex("""<script[^>]+src="https?:""").containsMatchIn(html),
        )
        assertFalse(
            "a stylesheet is loaded from another site",
            Regex("""<link[^>]+rel="stylesheet"[^>]+href="https?:""").containsMatchIn(html),
        )
        assertFalse(
            "an image is loaded from another site",
            Regex("""<img[^>]+src="https?:""").containsMatchIn(html),
        )
    }

    /**
     * The app has no INTERNET permission, so Python - which is downloaded on
     * first use - cannot work here. offline.js is what stops the app offering
     * it. If that file ever stopped doing its job, pressing Run on Python
     * would hang on a fetch that can never finish.
     */
    @Test
    fun `python is taken out of this build`() {
        val offline = assetFile("js/offline.js").readText()
        assertTrue(
            "offline.js no longer marks python as not runnable",
            Regex("""python[\s\S]{0,80}\.run\s*=\s*null""").containsMatchIn(offline),
        )
        assertTrue(
            "offline.js must do nothing in a browser",
            offline.contains("AndroidHost"),
        )

        /*
         * The Python example opens by saying it runs for real. Here it does
         * not, and an app that contradicts itself on its own first screen is
         * worse than one that is simply missing a feature.
         */
        assertTrue(
            "offline.js no longer rewrites the Python example's opening line",
            offline.contains("SAMPLES.python"),
        )
        assertTrue(
            "the Python example in assets/ still claims it runs",
            assetFile("js/samples.js").readText().contains("Runs for real"),
        )

        val manifest = File("src/main/AndroidManifest.xml")
            .takeIf { it.exists() }
            ?: File("app/src/main/AndroidManifest.xml")
        /*
         * Comments stripped first - the manifest explains at length why there
         * is no permission in it, and the words "uses-permission" appear in
         * that explanation.
         */
        val declarations = manifest.readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
        assertFalse(
            "the app asks for a permission - the rest of this reasoning no longer holds",
            declarations.contains("uses-permission"),
        )
    }

    /**
     * android.js must survive being loaded in a plain browser, because these
     * are the same files as the web app plus two. If it threw on a desktop the
     * shared copy would be useless.
     */
    @Test
    fun `the phone layer gives up when the app is not there`() {
        val android = assetFile("js/android.js").readText()
        assertTrue(
            "android.js must return early without AndroidHost",
            Regex("""if \(!host\)\s*return""").containsMatchIn(android),
        )
        assertTrue(
            "android.js must return early without the editor",
            Regex("""if \(!api\)\s*return""").containsMatchIn(android),
        )
    }

    /**
     * Everything the app sends the page arrives at one function as JSON. If a
     * second entry point appeared, or the app started building JavaScript by
     * hand, a file named `'); doSomething();//` would become a program.
     */
    @Test
    fun `messages from the app arrive as data, not as code`() {
        val activity = sourceFile("MainActivity.kt").readText()

        assertTrue(
            "the app must hand the page a quoted JSON string",
            activity.contains("JSONObject.quote"),
        )
        assertEquals(
            "there should be exactly one place that runs JavaScript with a payload in it",
            1,
            Regex("""evaluateJavascript\("window\.androidReceive""").findAll(activity).count(),
        )

        val android = assetFile("js/android.js").readText()
        assertTrue(
            "the page must parse it rather than evaluate it",
            android.contains("JSON.parse"),
        )
        assertFalse("nothing is eval'd", Regex("""\beval\s*\(""").containsMatchIn(android))
    }

    /**
     * The WebView must not be able to reach the filesystem. It runs whatever
     * code the user pasted; file access would let a snippet read the app's own
     * data directory.
     */
    @Test
    fun `the WebView cannot reach the filesystem`() {
        val activity = sourceFile("MainActivity.kt").readText()
        assertTrue(activity.contains(Regex("""allowFileAccess\s*=\s*false""")))
        assertTrue(activity.contains(Regex("""allowContentAccess\s*=\s*false""")))
    }

    /**
     * Saving must truncate. Without the "t" mode, saving a shorter version of
     * a file leaves the tail of the old one behind - a genuinely nasty way to
     * lose work, and completely invisible until somebody reopens the file.
     */
    @Test
    fun `saving truncates the file it writes`() {
        val documents = sourceFile("Documents.kt").readText()
        assertTrue(
            "openOutputStream must be called with \"wt\"",
            documents.contains(Regex("""openOutputStream\(uri,\s*"wt"\)""")),
        )
    }

    @Test
    fun `the editor files match the web version`() {
        val web = File("../../codepad").takeIf { it.isDirectory }
            ?: File("../codepad").takeIf { it.isDirectory }
            ?: return // the web app is not checked out next to this one

        val shared = listOf(
            "js/highlight.js", "js/editor.js", "js/runner.js",
            "js/samples.js", "js/share.js", "js/app.js", "worker.js", "styles.css",
        )

        val drifted = shared.filter { name ->
            val there = File(web, name)
            there.exists() && assetFile(name).readText() != there.readText()
        }

        assertEquals(
            "these bundled editor files have drifted from the web app",
            emptyList<String>(),
            drifted,
        )
    }

    private fun sourceFile(name: String): File {
        val relative = "src/main/java/io/github/proburakelci/codepad/$name"
        return File(relative).takeIf { it.exists() } ?: File("app/$relative")
    }
}
