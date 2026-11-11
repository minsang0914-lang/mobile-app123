package io.github.minsang0914.svt

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var homeUrl: String
    private lateinit var homeHost: String

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var choosingFile = false
    private var mainFrameFailed = false
    private var pendingDownload: PendingDownload? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileCallback
        fileCallback = null
        choosingFile = false
        // The picker returns before onResume, so wake the WebView first.
        if (::webView.isInitialized) webView.onResume()
        callback?.onReceiveValue(selectedUris(result.resultCode, result.data))
    }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val download = pendingDownload
        pendingDownload = null
        if (granted && download != null) {
            enqueueDownload(download)
        } else if (!granted) {
            toast(R.string.download_failed)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        homeUrl = getString(R.string.home_url)
        homeHost = Uri.parse(homeUrl).host ?: "minsang0914-lang.github.io"

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<View>(R.id.retryButton).setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }

        configureWebView()

        if (savedInstanceState == null) {
            webView.loadUrl(homeUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (!choosingFile) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        choosingFile = false
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            removeJavascriptInterface(BRIDGE_NAME)
            destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(getColor(R.color.site_background))
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            disableForceDark(this)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(BlobBridge(), BRIDGE_NAME)
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                saveBlob(url, mimeType, contentDisposition)
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                downloadHttp(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun disableForceDark(settings: WebSettings) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_OFF
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.isAlgorithmicDarkeningAllowed = false
        }
    }

    private fun saveBlob(url: String, mimeType: String?, contentDisposition: String?) {
        val mime = mimeType?.substringBefore(';')?.ifBlank { "image/gif" } ?: "image/gif"
        val guessed = URLUtil.guessFileName(url, contentDisposition, mime)
        val name = safeFileName(guessed, mime)
        val script = """
            (function () {
              function fail() {
                $BRIDGE_NAME.fail(location.hostname);
              }
              try {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', ${JSONObject.quote(url)}, true);
                xhr.responseType = 'blob';
                xhr.onload = function () {
                  var reader = new FileReader();
                  reader.onloadend = function () {
                    var data = String(reader.result || '');
                    var step = 180000;
                    $BRIDGE_NAME.start(${JSONObject.quote(mime)}, ${JSONObject.quote(name)}, location.hostname, data.length);
                    for (var i = 0; i < data.length; i += step) {
                      $BRIDGE_NAME.chunk(data.substring(i, Math.min(data.length, i + step)));
                    }
                    $BRIDGE_NAME.finish();
                  };
                  reader.onerror = fail;
                  reader.readAsDataURL(xhr.response);
                };
                xhr.onerror = fail;
                xhr.send();
              } catch (e) {
                fail();
              }
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun downloadHttp(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val download = PendingDownload(url, userAgent, contentDisposition, mimeType)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = download
            storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueueDownload(download)
    }

    private fun enqueueDownload(download: PendingDownload) {
        try {
            val mime = download.mimeType?.substringBefore(';')
            val name = safeFileName(
                URLUtil.guessFileName(download.url, download.contentDisposition, mime),
                mime ?: "application/octet-stream"
            )
            val request = DownloadManager.Request(Uri.parse(download.url)).apply {
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SVT/$name")
                if (!mime.isNullOrBlank()) setMimeType(mime)
                download.userAgent?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(download.url)?.let { addRequestHeader("Cookie", it) }
            }
            getSystemService(DownloadManager::class.java).enqueue(request)
            toast(R.string.download_saved)
        } catch (_: Exception) {
            toast(R.string.download_failed)
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.link_failed)
        }
    }

    private fun injectHelpers() {
        webView.evaluateJavascript(
            """
            (function () {
              if (!window.crypto || typeof crypto.randomUUID !== "function") {
                if (window.crypto) {
                  crypto.randomUUID = function () {
                    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
                      var r = Math.random() * 16 | 0;
                      var v = c === "x" ? r : (r & 0x3 | 0x8);
                      return v.toString(16);
                    });
                  };
                }
              }
              if (window.__svtDownloadInstalled) return;
              window.__svtDownloadInstalled = true;

              function sendBlob(blob, filename) {
                return new Promise(function (resolve) {
                  if (!blob) { resolve(); return; }
                  var reader = new FileReader();
                  reader.onloadend = function () {
                    var data = String(reader.result || "");
                    var step = 180000;
                    $BRIDGE_NAME.start("image/gif", filename || "video.gif", location.hostname, data.length);
                    for (var i = 0; i < data.length; i += step) {
                      $BRIDGE_NAME.chunk(data.substring(i, Math.min(data.length, i + step)));
                    }
                    $BRIDGE_NAME.finish();
                    resolve();
                  };
                  reader.onerror = function () {
                    $BRIDGE_NAME.fail(location.hostname);
                    resolve();
                  };
                  reader.readAsDataURL(blob);
                });
              }

              function toBlob(data) {
                if (!data) return null;
                if (data instanceof Blob) return data;
                if (data.data) return toBlob(data.data);
                if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
                  return new Blob([data], { type: "image/gif" });
                }
                if (typeof data === "string") return new Blob([data], { type: "image/gif" });
                return null;
              }

              var picker = function (options) {
                var name = (options && options.suggestedName) || "video.gif";
                return Promise.resolve({
                  createWritable: function () {
                    var parts = [];
                    return Promise.resolve({
                      write: function (data) {
                        var blob = toBlob(data);
                        if (blob) parts.push(blob);
                        return Promise.resolve();
                      },
                      close: function () {
                        if (!parts.length) return Promise.resolve();
                        return sendBlob(new Blob(parts, { type: "image/gif" }), name);
                      }
                    });
                  }
                });
              };

              try {
                Object.defineProperty(window, "showSaveFilePicker", {
                  configurable: true,
                  writable: true,
                  value: picker
                });
              } catch (e) {
                window.showSaveFilePicker = picker;
              }

              var nativeClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                var href = this.href || "";
                if (href.indexOf("blob:") === 0) {
                  var name = this.download || "video.gif";
                  fetch(href).then(function (response) {
                    return response.blob();
                  }).then(function (blob) {
                    return sendBlob(blob, name);
                  }).catch(function () {
                    $BRIDGE_NAME.fail(location.hostname);
                  });
                  return;
                }
                return nativeClick.call(this);
              };
            })();
            """.trimIndent(),
            null
        )
    }

    private fun toast(message: Int) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun safeFileName(raw: String, mime: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .trim('.')
            .let { name ->
                if (name.equals("download.bin", true) ||
                    name.equals("download", true) ||
                    name.equals("unknown.bin", true)
                ) {
                    ""
                } else {
                    name
                }
            }
        val extension = when {
            mime.contains("gif") -> ".gif"
            mime.contains("png") -> ".png"
            mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
            mime.contains("webm") -> ".webm"
            mime.contains("mp4") -> ".mp4"
            else -> ""
        }
        val named = when {
            cleaned.isBlank() -> "SVT-${System.currentTimeMillis()}${extension.ifBlank { ".gif" }}"
            cleaned.contains('.') -> cleaned
            extension.isNotEmpty() -> cleaned + extension
            else -> "$cleaned.bin"
        }
        return named.take(120)
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val payload = dataUrl.substringAfter(',', dataUrl)
        if (payload.isBlank()) error("empty")
        return Base64.decode(payload, Base64.DEFAULT)
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SVT")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            return try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("stream")
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                true
            } catch (_: Exception) {
                contentResolver.delete(uri, null, null)
                false
            }
        }

        val directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        directory.mkdirs()
        val file = File(directory, name)
        FileOutputStream(file).use { it.write(bytes) }
        mainHandler.post {
            if (isDestroyed) return@post
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(share, getString(R.string.download_share)))
            } catch (_: ActivityNotFoundException) {
                toast(R.string.download_failed)
            }
        }
        return true
    }

    private inner class AppWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            mainFrameFailed = false
            errorView.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 8
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            progressBar.visibility = View.GONE
            if (!mainFrameFailed) {
                errorView.visibility = View.GONE
                injectHelpers()
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                mainFrameFailed = true
                progressBar.visibility = View.GONE
                errorView.visibility = View.VISIBLE
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            if (!request.isForMainFrame) return false
            if (uri.scheme == "blob" || uri.scheme == "data") return false
            if (uri.host == homeHost) return false
            if (uri.scheme == "http" || uri.scheme == "https") {
                openExternal(uri)
                return true
            }
            return true
        }
    }

    private inner class AppChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (mainFrameFailed) {
                progressBar.visibility = View.GONE
                return
            }
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            val intent = try {
                fileChooserParams?.createIntent()
            } catch (_: Exception) {
                null
            } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            if (fileChooserParams == null ||
                fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            ) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            if (intent.type.isNullOrBlank()) {
                intent.type = "video/*"
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            choosingFile = true
            return try {
                fileChooser.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                choosingFile = false
                fileCallback = null
                filePathCallback?.onReceiveValue(null)
                toast(R.string.file_chooser_failed)
                false
            }
        }
    }

    private fun selectedUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        val uris = linkedSetOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { uris.add(it) }
            }
        }
        data.data?.let { uris.add(it) }
        if (uris.isEmpty()) {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data)?.forEach { uris.add(it) }
        }
        if (uris.isEmpty()) return null
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // A temporary read grant from the picker is enough for this upload.
            }
        }
        return uris.toTypedArray()
    }

    private inner class BlobBridge {
        private val buffer = StringBuilder()
        private var expectedLength = -1
        private var activeMime = "image/gif"
        private var activeName = "SVT.gif"
        private var accepting = false

        @JavascriptInterface
        fun start(mime: String?, filename: String?, origin: String?, length: Int) {
            if (origin != homeHost) return
            val type = mime?.substringBefore(';')?.ifBlank { "image/gif" } ?: "image/gif"
            synchronized(this) {
                buffer.setLength(0)
                if (length > 0) buffer.ensureCapacity(length)
                expectedLength = length
                activeMime = type
                activeName = safeFileName(filename.orEmpty(), type)
                accepting = length > 0
            }
            if (length <= 0) toast(R.string.download_failed)
        }

        @JavascriptInterface
        fun chunk(part: String?) {
            if (part.isNullOrEmpty()) return
            synchronized(this) {
                if (!accepting) return
                buffer.append(part)
            }
        }

        @JavascriptInterface
        fun finish() {
            val dataUrl: String
            val mime: String
            val filename: String
            synchronized(this) {
                if (!accepting) return
                accepting = false
                if (expectedLength >= 0 && buffer.length != expectedLength) {
                    buffer.setLength(0)
                    toast(R.string.download_failed)
                    return
                }
                dataUrl = buffer.toString()
                mime = activeMime
                filename = activeName
                buffer.setLength(0)
            }
            val bytes = try {
                decodeDataUrl(dataUrl)
            } catch (_: Exception) {
                toast(R.string.download_failed)
                return
            }
            mainHandler.post {
                if (isDestroyed) return@post
                val currentHost = Uri.parse(webView.url ?: return@post).host
                if (currentHost != homeHost) return@post
                val saved = try {
                    saveToDownloads(filename, mime, bytes)
                } catch (_: Exception) {
                    false
                }
                if (!saved) {
                    toast(R.string.download_failed)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    toast(R.string.download_saved)
                }
            }
        }

        @JavascriptInterface
        fun fail(origin: String?) {
            if (origin != homeHost) return
            synchronized(this) {
                accepting = false
                buffer.setLength(0)
            }
            toast(R.string.download_failed)
        }
    }

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )

    companion object {
        private const val BRIDGE_NAME = "SVTBlob"
    }
}
