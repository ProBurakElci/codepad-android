package io.github.proburakelci.codepad

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject

/**
 * The whole app.
 *
 * The editor is the same HTML, CSS and JavaScript as the web version, sitting
 * in assets/. What this activity adds is the two things a web page in a
 * browser tab cannot do on a phone: open a file the user picked, and save back
 * to it.
 *
 * It is served from https://appassets.androidplatform.net rather than
 * file:///android_asset, for a reason that is not cosmetic: a page on file://
 * is not a secure origin, and a Web Worker refuses to start there. The
 * JavaScript runner is built on a worker - that is what keeps an endless loop
 * from freezing the app - so without the asset loader the app's main feature
 * would not work at all.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    /** The file we are editing, if the user has opened or saved one. */
    private var documentUri: Uri? = null
    private var documentName: String? = null
    private var dirty = false

    /** What the page last told us it had, so a save never writes stale text. */
    private var lastExtension = "txt"

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) load(uri, persist = true)
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) {
                // The user backed out of the save sheet, so they are not
                // leaving either.
                leaveAfterSave = false
                pendingSaveText = null
                return@registerForActivityResult
            }
            takePermission(uri)
            pendingSaveText?.let { text ->
                documentUri = uri
                documentName = Documents.displayName(contentResolver, uri)
                writeTo(uri, text)
            }
            pendingSaveText = null
        }

    /** Held only between asking the page for its text and the sheet coming back. */
    private var pendingSaveText: String? = null

    /**
     * Set when the user chose Save on the way out. The app closes only once the
     * write has actually landed - closing when the save was merely started is
     * how somebody loses the work they just asked to keep.
     */
    private var leaveAfterSave = false

    /**
     * True while the page has its own sheet up. Back closes that first, the
     * way Back closes anything else stacked over a screen - the page cannot
     * see a hardware key, so it has to say so here.
     */
    private var overlayOpen = false

    /* ---------------- setting up ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)

        /*
         * Handy while developing, and it weakens nothing: the page being
         * inspected is one we shipped. Read off the install rather than out of
         * BuildConfig, so this needs no generated class.
         */
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // the editor keeps your draft here
            // Nothing in this app needs to reach the filesystem from the page.
            allowFileAccess = false
            allowContentAccess = false
            // The editor is responsive; letting the WebView pretend to be a
            // desktop and then zoom out makes the text unreadable.
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100 // honouring the system font scale would break the gutter alignment
            mediaPlaybackRequiresUserGesture = true
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            /*
             * The only link in the editor is the one to its own source code.
             * Anything that is not our own asset origin leaves for the
             * browser, so a page can never replace the editor in place.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                if (url.host == ASSET_HOST) return false
                openInBrowser(url)
                return true
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidHost")
        web.loadUrl("https://$ASSET_HOST/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        // Opened by tapping a file in a file manager, or shared from another app.
        intent?.let { handleIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data?.let { pendingOpen = it }
            Intent.ACTION_SEND -> {
                intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let { pendingOpen = it }
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { pendingText = it }
            }
        }
    }

    /*
     * An intent can arrive before the page has finished loading, so it waits
     * here until the page says it is ready.
     */
    private var pendingOpen: Uri? = null
    private var pendingText: String? = null

    /* ---------------- what the page can ask for ---------------- */

    private inner class Bridge {

        /** The page calls this once it is up, which is when intents get handled. */
        @JavascriptInterface
        fun ready() = runOnUiThread {
            pendingOpen?.let { load(it, persist = false) }
            pendingOpen = null

            pendingText?.let { text ->
                documentUri = null
                documentName = null
                toPage("setCode", JSONObject().put("text", text))
                toPage("note", JSONObject().put("text", "shared from another app"))
            }
            pendingText = null
        }

        @JavascriptInterface
        fun openFile() = runOnUiThread {
            confirmDiscard {
                // Almost every code file has a type Android calls
                // application/octet-stream, so narrowing this only hides the
                // files people actually want.
                openDocument.launch(arrayOf("*/*"))
            }
        }

        @JavascriptInterface
        fun save(text: String, extension: String) = runOnUiThread {
            lastExtension = extension
            val uri = documentUri
            if (uri == null) {
                startSaveAs(text, extension)
            } else {
                writeTo(uri, text)
            }
        }

        @JavascriptInterface
        fun saveAs(text: String, extension: String) = runOnUiThread {
            lastExtension = extension
            startSaveAs(text, extension)
        }

        @JavascriptInterface
        fun newFile() = runOnUiThread {
            confirmDiscard {
                documentUri = null
                documentName = null
                dirty = false
                toPage("setCode", JSONObject().put("text", ""))
                toPage("named", JSONObject().put("name", JSONObject.NULL))
            }
        }

        /** Hands the snippet to whatever the user wants to send it with. */
        @JavascriptInterface
        fun share(text: String) = runOnUiThread {
            if (text.isBlank()) return@runOnUiThread
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                documentName?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }
            startActivity(Intent.createChooser(send, "Share this snippet"))
        }

        @JavascriptInterface
        fun setDirty(value: Boolean) = runOnUiThread {
            dirty = value
        }

        @JavascriptInterface
        fun setOverlayOpen(value: Boolean) = runOnUiThread {
            overlayOpen = value
        }

        @JavascriptInterface
        fun openSourceCode() = runOnUiThread {
            openInBrowser(Uri.parse("https://github.com/ProBurakElci/codepad-android"))
        }
    }

    /* ---------------- opening and saving ---------------- */

    private fun load(uri: Uri, persist: Boolean) {
        if (persist) takePermission(uri)

        when (val result = Documents.read(contentResolver, uri)) {
            is Documents.Read.Ok -> {
                documentUri = uri
                documentName = result.name
                dirty = false

                val language = FileTypes.languageFor(result.name)
                lastExtension = FileTypes.extensionOf(result.name) ?: "txt"

                toPage(
                    "opened",
                    JSONObject()
                        .put("text", result.text)
                        .put("name", result.name ?: "file")
                        .put("language", language ?: JSONObject.NULL),
                )
            }

            is Documents.Read.Failed -> {
                AlertDialog.Builder(this)
                    .setTitle("Could not open that")
                    .setMessage(result.reason)
                    .setPositiveButton("All right", null)
                    .show()
            }
        }
    }

    private fun startSaveAs(text: String, extension: String) {
        pendingSaveText = text
        createDocument.launch(documentName ?: FileTypes.suggestedName(extension))
    }

    private fun writeTo(uri: Uri, text: String) {
        val problem = Documents.write(contentResolver, uri, text)
        if (problem == null) {
            dirty = false
            documentName = documentName ?: Documents.displayName(contentResolver, uri)
            toPage("saved", JSONObject().put("name", documentName ?: "file"))
            if (leaveAfterSave) {
                leaveAfterSave = false
                finish()
            }
        } else {
            leaveAfterSave = false
            AlertDialog.Builder(this)
                .setTitle("Could not save")
                .setMessage(problem)
                .setPositiveButton("All right", null)
                .show()
        }
    }

    /**
     * Asks Android to remember that we may reach this file after a restart, so
     * Save keeps working tomorrow instead of turning into Save as.
     */
    private fun takePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (err: SecurityException) {
            // Some providers do not offer a lasting grant. The file still
            // opens now; it just will not survive a restart.
            Log.i(TAG, "no lasting permission for this file")
        }
    }

    /* ---------------- leaving ---------------- */

    private fun goBack() {
        if (overlayOpen) {
            overlayOpen = false
            toPage("closeOverlay", JSONObject())
            return
        }

        if (!dirty) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("You have unsaved changes")
            .setMessage(
                documentName?.let { "Save them back to $it?" }
                    ?: "This snippet has never been saved."
            )
            .setPositiveButton("Save") { _, _ ->
                leaveAfterSave = true
                web.evaluateJavascript(REQUEST_SAVE, null)
            }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Stay", null)
            .show()
    }

    private fun confirmDiscard(then: () -> Unit) {
        if (!dirty) {
            then()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("You have unsaved changes")
            .setMessage("They will be lost.")
            .setPositiveButton("Carry on") { _, _ -> then() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ---------------- talking to the page ---------------- */

    /**
     * Everything crossing into the page goes through one function with a JSON
     * payload. Building JavaScript out of string concatenation here is how a
     * file named `'); doSomething();//` would end up being executed.
     */
    private fun toPage(event: String, payload: JSONObject) {
        val json = JSONObject().put("event", event).put("data", payload).toString()
        val quoted = JSONObject.quote(json)
        web.evaluateJavascript("window.androidReceive && window.androidReceive($quoted);", null)
    }

    private fun openInBrowser(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (err: Exception) {
            Log.i(TAG, "nothing on this device can open $url")
        }
    }

    private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name) as? T
        }

    companion object {
        private const val TAG = "codepad"
        private const val ASSET_HOST = "appassets.androidplatform.net"

        /** Asks the page to hand its text to save(), wherever that ends up going. */
        private const val REQUEST_SAVE = "window.androidRequestSave && window.androidRequestSave();"
    }
}
