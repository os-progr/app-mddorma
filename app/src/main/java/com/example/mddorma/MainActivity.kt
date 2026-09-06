package com.example.mddorma

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout

    // Selector de archivos nativo (Fotos de perfil, adjuntos en chats, comprobantes)
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback != null) {
            val results: Array<Uri>? = if (result.resultCode == RESULT_OK) {
                val dataString = result.data?.dataString
                val clipData = result.data?.clipData
                if (clipData != null) {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else if (dataString != null) {
                    arrayOf(Uri.parse(dataString))
                } else {
                    null
                }
            } else {
                null
            }
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }

    private val WEB_URL = "https://mddorma.com"
    private val UPDATE_CHECK_URL = "https://mddorma.com/api/check_app_update.php"

    // Launcher para permisos de notificaciones (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permiso procesado */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Asegurar directorios de caché de WebView antes de inicializar la vista
        MddormaApp.ensureWebViewCacheDirectories(applicationContext)

        // Ajuste automático respetando los límites de pantalla (Safe Areas / Barra de estado / Botones)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)
        fullscreenContainer = findViewById(R.id.fullscreen_container)

        setupWebView()

        // Solicitar permisos de notificación tras 2 segundos
        webView.postDelayed({
            requestNotificationPermission()
        }, 2000)

        // Verificar actualizaciones en segundo plano
        webView.postDelayed({
            checkForUpdates()
        }, 3000)

        // Cargar la web
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(WEB_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // User Agent optimizado para compatibilidad total con Google OAuth e identificación In-App
        val rawUserAgent = settings.userAgentString
        val cleanUserAgent = rawUserAgent.replace("; wv", "")
                                         .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
        settings.userAgentString = cleanUserAgent

        // Habilitar cookies (incluyendo cookies de terceros para sesión y Google)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        cookieManager.setCookie("https://mddorma.com", "mddorma_in_app=1; path=/; domain=mddorma.com; secure")

        // Listener de descargas nativo
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimetype)
                val cookies = CookieManager.getInstance().getCookie(url)
                request.addRequestHeader("cookie", cookies)
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("Descargando archivo desde mddorma...")
                val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                request.setTitle(filename)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Descargando: $filename", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (ex: Exception) {
                    Toast.makeText(this, "No se pudo iniciar la descarga", Toast.LENGTH_SHORT).show()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                try {
                    val parent = view?.parent as? android.view.ViewGroup
                    parent?.removeView(view)
                    view?.destroy()
                } catch (_: Exception) {}
                recreate()
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // 1. Mantener URLs de la plataforma y autenticación dentro de la app
                if (url.contains("mddorma.com") || 
                    url.contains("accounts.google.com") || 
                    url.contains("google.com/accounts") ||
                    url.contains("gsi/select") ||
                    url.contains("mercadopago.com") ||
                    url.contains("paypal.com")) {
                    return false
                }

                // Manejo de URLs con esquema intent://
                if (url.startsWith("intent:")) {
                    return try {
                        val parsedIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (parsedIntent.resolveActivity(packageManager) != null) {
                            startActivity(parsedIntent)
                        } else {
                            val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                            if (!fallbackUrl.isNullOrEmpty()) {
                                view?.loadUrl(fallbackUrl)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

                // 2. Manejar enlaces externos (WhatsApp, Telegram, Teléfonos, Mail)
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    true
                }
            }
        }

        // Manejo de Reproducción de Video a Pantalla Completa y Selección de Archivos
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                }
            }

            // Soporte para subida de fotos / selector de archivos
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileUploadCallback = null
                    false
                }
            }

            // Soporte para popups / múltiples ventanas (Google OAuth login sin perder la ventana padre)
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val dialog = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                val popupWebView = WebView(this@MainActivity)
                popupWebView.settings.javaScriptEnabled = true
                popupWebView.settings.domStorageEnabled = true
                popupWebView.settings.userAgentString = settings.userAgentString
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(popupWebView, true)

                val alertDialog = dialog.setView(popupWebView).create()

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        try { alertDialog.dismiss() } catch (e: Exception) {}
                    }
                }

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        val url = req?.url?.toString() ?: return false
                        if (url.contains("mddorma.com") && !url.contains("accounts.google.com") && !url.contains("gsi")) {
                            view?.loadUrl(url)
                            try { alertDialog.dismiss() } catch (e: Exception) {}
                            return true
                        }
                        return false
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                alertDialog.show()
                return true
            }

            // Entrar a pantalla completa (Girar automáticamente a horizontal para ver el dorama)
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE

                // Girar pantalla a horizontal automático e inmersión total para video
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            // Salir de pantalla completa (Volver a vertical respetando límites del sistema)
            override fun onHideCustomView() {
                super.onHideCustomView()
                customView?.let { fullscreenContainer.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE

                // Restaurar orientación vertical y límites del sistema
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 🚀 Sistema de Verificación Automática de Actualizaciones
    private fun checkForUpdates() {
        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(UPDATE_CHECK_URL)
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "MDDormaApp/2.8")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val json = JSONObject(response)
                    val latestCode = json.optInt("latest_version_code", 1)
                    val latestName = json.optString("latest_version_name", "2.8")
                    val apkUrl = json.optString("apk_url", "https://mddorma.com/mddorma.apk?v=2.8")
                    val notes = json.optString("release_notes", "Hay una nueva versión disponible de mddorma.")
                    val forceUpdate = json.optBoolean("force_update", false)

                    val currentCode = BuildConfig.VERSION_CODE

                    if (latestCode > currentCode) {
                        Handler(Looper.getMainLooper()).post {
                            showUpdateDialog(latestName, apkUrl, notes, forceUpdate)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignorar errores de red en segundo plano
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun showUpdateDialog(versionName: String, apkUrl: String, notes: String, forceUpdate: Boolean) {
        if (isFinishing || isDestroyed) return

        val builder = AlertDialog.Builder(this)
            .setTitle("Actualización Disponible (v$versionName)")
            .setMessage(notes)
            .setPositiveButton("Actualizar Ahora") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                startActivity(intent)
            }

        if (!forceUpdate) {
            builder.setNegativeButton("Más tarde", null)
        } else {
            builder.setCancelable(false)
        }

        val dialog = builder.create()
        dialog.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Gestión inteligente del botón atrás
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.webChromeClient?.onHideCustomView()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        webView.destroy()
        super.onDestroy()
    }
}
