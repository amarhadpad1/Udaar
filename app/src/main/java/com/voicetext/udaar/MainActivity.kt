package com.voicetext.udaar
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loader: ProgressBar
    private var pendingBackup: String? = null

    // Export backup: lets the user choose where to save the .json file (Drive, Downloads, etc.)
    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            val data = pendingBackup
            pendingBackup = null
            if (uri == null || data == null) return@registerForActivityResult
            val ok = try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(data.toByteArray(Charsets.UTF_8)) } != null
            } catch (e: Exception) {
                false
            }
            js("window.__onBackupSaved($ok)")
        }

    // Restore backup: lets the user pick a backup file and hands its text to the page
    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
            if (text == null) {
                Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show()
            } else {
                js("window.__onBackupPicked(${JSONObject.quote(text)})")
            }
        }

    inner class Bridge {
        @JavascriptInterface
        fun saveBackup(name: String, data: String) {
            pendingBackup = data
            runOnUiThread { saveLauncher.launch(name) }
        }

        @JavascriptInterface
        fun pickBackup() {
            runOnUiThread { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Udaar log", text))
            }
        }
    }

    private fun js(code: String) {
        webView.post { webView.evaluateJavascript(code, null) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.parseColor("#121212") else Color.WHITE
        val accent = if (dark) Color.parseColor("#4ADE80") else Color.parseColor("#14532D")

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }

        webView = WebView(this).apply {
            setBackgroundColor(bg)
            visibility = View.INVISIBLE // hidden until the page is ready, so no white flash
        }
        loader = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
        }

        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            loader,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loader.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            // Phone calls, WhatsApp and other links open in their own apps, never inside Udaar
            // (this also keeps the AndroidBridge away from outside web pages).
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "file") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "No app found to open this link", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        webView.webChromeClient = WebChromeClient()

        // Load Udaar inside the Android app
        webView.loadUrl("file:///android_asset/index.html")

        // Back button: go back one screen inside Udaar; leave the app only from the first screen
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    webView.evaluateJavascript("(window.__back ? window.__back() : false)") { result ->
                        if (result != "true") finish()
                    }
                }
            }
        )
    }
}