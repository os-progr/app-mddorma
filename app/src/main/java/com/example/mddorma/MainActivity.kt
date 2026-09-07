package com.example.mddorma

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var mainRoot: FrameLayout
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var errorLayout: FrameLayout
    private lateinit var errorIcon: ImageView
    private lateinit var errorTitleText: TextView
    private lateinit var errorMessageText: TextView
    private lateinit var retryButton: Button

    private lateinit var gestureHud: LinearLayout
    private lateinit var hudIcon: ImageView
    private lateinit var hudProgressBar: ProgressBar
    private lateinit var hudText: TextView
    private lateinit var audioManager: AudioManager

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isFirstNetworkCheck = true
    private var lastFailedUrl: String? = null

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isAdjustingBrightness = false
    private var initialBrightness = 0.5f
    private var initialVolume = 0
    private var isDraggingFullscreenGesture = false

    // ── Selector de Archivos Nativo (Fotos de perfil, adjuntos de chat, etc.) ──
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback != null) {
            val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
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

    // ── Google Sign-In Nativo / Firebase Auth Bridge ──
    private var googleSignInClient: GoogleSignInClient? = null
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (!idToken.isNullOrEmpty()) {
                    syncGoogleAuthWithBackend(idToken, account.displayName ?: "Usuario")
                } else {
                    Toast.makeText(this, "No se pudo obtener el token de Google", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                Log.e("MDDormaAuth", "Google sign in failed: code ${e.statusCode}", e)
                Toast.makeText(this, "Error al autenticar con Google (código: ${e.statusCode})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val hideHudRunnable = Runnable {
        gestureHud.animate().alpha(0f).setDuration(250).withEndAction {
            gestureHud.visibility = View.GONE
        }.start()
    }

    companion object {
        private const val BASE_URL = "https://mddorma.com"
        private const val GOOGLE_WEB_CLIENT_ID = "614867100474-4ilca83g5q0pkvs3int4429dcaps2fj4.apps.googleusercontent.com"
        private const val AUTH_SYNC_URL = "https://mddorma.com/api/google_login.php"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainRoot = findViewById(R.id.main_root)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh)
        webView = findViewById(R.id.webview)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        progressBar = findViewById(R.id.progress_bar)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        errorLayout = findViewById(R.id.error_layout)
        errorIcon = findViewById(R.id.error_icon)
        errorTitleText = findViewById(R.id.error_title_text)
        errorMessageText = findViewById(R.id.error_message_text)
        retryButton = findViewById(R.id.retry_button)

        gestureHud = findViewById(R.id.gesture_hud)
        hudIcon = findViewById(R.id.hud_icon)
        hudProgressBar = findViewById(R.id.hud_progress_bar)
        hudText = findViewById(R.id.hud_text)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Configuración de Google Sign-In Nativo
        setupGoogleSignIn()

        // Paleta visual de MDDorma para SwipeRefreshLayout
        swipeRefreshLayout.setColorSchemeColors(
            Color.parseColor("#A855F7"),
            Color.parseColor("#EC4899"),
            Color.parseColor("#9333EA")
        )
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(Color.parseColor("#1F1F1F"))

        swipeRefreshLayout.setOnRefreshListener {
            if (!isOnline()) {
                swipeRefreshLayout.isRefreshing = false
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_no_internet),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                hideErrorOverlay()
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                webView.reload()
            }
        }

        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            webView.scrollY > 0
        }

        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { view, insets ->
            if (customView == null) {
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            }
            insets
        }

        retryButton.setOnClickListener {
            if (!isOnline()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_no_internet),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                hideErrorOverlay()
                progressBar.visibility = View.VISIBLE
                swipeRefreshLayout.isRefreshing = true
                val targetUrl = lastFailedUrl ?: webView.url ?: BASE_URL
                webView.loadUrl(targetUrl)
            }
        }

        setupWebView()
        registerNetworkCallback()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    exitFullscreenVideo()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(BASE_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }

        checkForAppUpdates()
    }

    private fun setupGoogleSignIn() {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(GOOGLE_WEB_CLIENT_ID)
                .requestEmail()
                .requestProfile()
                .build()
            googleSignInClient = GoogleSignIn.getClient(this, gso)
        } catch (e: Exception) {
            Log.e("MDDormaAuth", "Error configuring GoogleSignIn: ${e.message}", e)
        }
    }

    fun launchGoogleSignIn() {
        runOnUiThread {
            try {
                googleSignInClient?.signOut()?.addOnCompleteListener {
                    val signInIntent = googleSignInClient?.signInIntent
                    if (signInIntent != null) {
                        googleSignInLauncher.launch(signInIntent)
                    } else {
                        Toast.makeText(this, "Servicio de Google no inicializado", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MDDormaAuth", "Error launching Google Sign In: ${e.message}", e)
                Toast.makeText(this, "Error al abrir Google Sign-In", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncGoogleAuthWithBackend(idToken: String, userName: String) {
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            Toast.makeText(this, "Conectando cuenta con mddorma.com...", Toast.LENGTH_SHORT).show()
        }

        thread {
            try {
                val url = URL(AUTH_SYNC_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.doInput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.setRequestProperty("User-Agent", webView.settings.userAgentString)

                // Enviar cookies existentes del WebView (para mantener sesión o CSRF)
                val existingCookies = CookieManager.getInstance().getCookie(BASE_URL)
                if (!existingCookies.isNullOrEmpty()) {
                    conn.setRequestProperty("Cookie", existingCookies)
                }

                val postData = "credential=" + URLEncoder.encode(idToken, "UTF-8")
                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(postData)
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode

                // Extraer cookies de sesión ANTES de leer el body y ANTES de disconnect()
                val cookieManager = CookieManager.getInstance()
                val headerFields = conn.headerFields
                val cookiesHeader = headerFields["Set-Cookie"] ?: headerFields["set-cookie"]
                if (cookiesHeader != null) {
                    for (cookie in cookiesHeader) {
                        cookieManager.setCookie(BASE_URL, cookie)
                    }
                    cookieManager.flush()
                }

                val reader = BufferedReader(InputStreamReader(if (responseCode in 200..299) conn.inputStream else conn.errorStream))
                val responseBody = reader.use { it.readText() }
                conn.disconnect()

                val json = try { JSONObject(responseBody) } catch (e: Exception) { JSONObject() }
                val success = json.optBoolean("success", false)

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (success) {
                        Toast.makeText(this@MainActivity, "¡Bienvenido, $userName!", Toast.LENGTH_LONG).show()
                        // Esperar 400ms para garantizar que las cookies de sesión se persistan al WebView
                        webView.postDelayed({
                            val current = webView.url ?: BASE_URL
                            if (current.contains("ingresar.php")) {
                                webView.loadUrl(BASE_URL)
                            } else {
                                webView.reload()
                            }
                        }, 400)
                    } else {
                        val msg = json.optString("message", "Error al sincronizar con el servidor")
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                Log.e("MDDormaAuth", "Error syncing with backend: ${e.message}", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Error de red al conectar con el servidor", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── In-App Update Checker ──
    private fun checkForAppUpdates() {
        thread {
            try {
                val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionCode
                }

                val url = URL("https://mddorma.com/api/check_app_update.php")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.use { it.readText() }
                    val json = JSONObject(response)

                    if (json.optBoolean("success", false)) {
                        val latestCode = json.optInt("latest_version_code", 0)
                        val latestName = json.optString("latest_version_name", "")
                        val apkUrl = json.optString("apk_url", "https://mddorma.com/mddorma.apk")
                        val title = json.optString("title", "¡Nueva versión disponible!")
                        val notes = json.optString("release_notes", "")
                        val forceUpdate = json.optBoolean("force_update", false)

                        if (latestCode > currentVersionCode) {
                            runOnUiThread {
                                showUpdateDialog(title, latestName, notes, apkUrl, forceUpdate)
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.d("MDDormaUpdate", "Update check skipped: ${e.message}")
            }
        }
    }

    private fun showUpdateDialog(title: String, versionName: String, notes: String, apkUrl: String, forceUpdate: Boolean) {
        if (isFinishing || isDestroyed) return
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$notes\n\n¿Deseas descargar e instalar la versión $versionName ahora?")
            .setPositiveButton("Actualizar Ahora") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Error al abrir el enlace de descarga", Toast.LENGTH_SHORT).show()
                }
            }
        if (!forceUpdate) {
            builder.setNegativeButton("Más tarde", null)
        } else {
            builder.setCancelable(false)
        }
        builder.show()
    }

    // ── Puente JavaScript para comunicación WebView <-> Android Nativo ──
    inner class AndroidAuthBridge {
        @JavascriptInterface
        fun isAndroidApp(): Boolean = true

        @JavascriptInterface
        fun getVersionCode(): Int = 12

        @JavascriptInterface
        fun getVersionName(): String = "3.2"

        @JavascriptInterface
        fun signInWithGoogle() {
            launchGoogleSignIn()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = if (isOnline()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        // User Agent optimizado para compatibilidad total con Google OAuth e identificación In-App
        val rawUserAgent = settings.userAgentString
        val cleanUserAgent = rawUserAgent.replace("; wv", "")
                                         .replace(Regex("Version/\\d+\\.\\d+\\s*"), "") + " MDDormaApp/3.2"
        settings.userAgentString = cleanUserAgent

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Registrar Puente JavaScript para autenticación nativa
        webView.addJavascriptInterface(AndroidAuthBridge(), "AndroidAuth")

        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                if (customView != null) {
                    exitFullscreenVideo()
                }
                view?.let { wv ->
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
                recreateWebView()
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefreshLayout.isRefreshing = false
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
                if (lastFailedUrl == null) {
                    hideErrorOverlay()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val code = errorResponse?.statusCode ?: return
                if (request?.isForMainFrame == true && code >= 500) {
                    swipeRefreshLayout.isRefreshing = false
                    progressBar.visibility = View.GONE
                    lastFailedUrl = request.url?.toString() ?: webView.url ?: BASE_URL
                    showErrorOverlay(isServerError = true)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                swipeRefreshLayout.isRefreshing = false
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    lastFailedUrl = request.url?.toString() ?: webView.url ?: BASE_URL
                    showErrorOverlay(isServerError = false)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_no_internet),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val url = uri.toString()

                if (isAdOrMaliciousUrl(url)) {
                    return true
                }

                if (customView != null && !url.contains("mddorma.com")) {
                    return true
                }

                val scheme = uri.scheme?.lowercase() ?: ""
                if (scheme == "http" || scheme == "https") {
                    return false
                }

                if (scheme == "intent" || scheme == "market") {
                    return true
                }

                return try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Selector de archivos nativo (Fotos, imágenes, archivos)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
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

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                return false
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress in 1..99) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.removeAllViews()
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                swipeRefreshLayout.isEnabled = false

                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                fullscreenContainer.keepScreenOn = true

                mainRoot.setPadding(0, 0, 0, 0)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                hideSystemBarsForFullscreen()
            }

            override fun onHideCustomView() {
                super.onHideCustomView()
                exitFullscreenVideo()
            }
        }
    }

    private fun isAdOrMaliciousUrl(url: String): Boolean {
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        if (host.contains("mddorma.com")) return false

        val lower = url.lowercase()
        val adIndicators = listOf(
            "doubleclick", "popads", "popcash", "adsterra", "exoclick",
            "propellerads", "adcash", "onclick", "highcpm", "whomepthe",
            "syndication", "trafficjunky", "ad-delivery", "bet365", "1xbet",
            "betting", "casino", "redirect", "banner", "tracking", "track",
            "pushnotification", "monetag", "yllix", "hilltopads"
        )
        for (indicator in adIndicators) {
            if (host.contains(indicator) || lower.contains(indicator)) return true
        }
        return false
    }

    private fun hideSystemBarsForFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBarsAfterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        ViewCompat.requestApplyInsets(mainRoot)
    }

    private fun exitFullscreenVideo() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        swipeRefreshLayout.isEnabled = true

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fullscreenContainer.keepScreenOn = false
        gestureHud.removeCallbacks(hideHudRunnable)
        gestureHud.visibility = View.GONE

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        showSystemBarsAfterFullscreen()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (customView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPiPMode()
        }
    }

    private fun enterPiPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val aspectRatio = Rational(16, 9)
                val pipParams = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(pipParams)
            } catch (e: Exception) {
                // Device does not support PiP in this state
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            gestureHud.removeCallbacks(hideHudRunnable)
            gestureHud.visibility = View.GONE
            errorLayout.visibility = View.GONE
            progressBar.visibility = View.GONE
        }
    }

    private fun showGestureHud() {
        gestureHud.removeCallbacks(hideHudRunnable)
        gestureHud.alpha = 1f
        gestureHud.visibility = View.VISIBLE
    }

    private fun scheduleHideGestureHud() {
        gestureHud.removeCallbacks(hideHudRunnable)
        gestureHud.postDelayed(hideHudRunnable, 1200)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (customView != null) {
            val handled = handleFullscreenTouchGesture(ev)
            if (handled) return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleFullscreenTouchGesture(ev: MotionEvent): Boolean {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.x
                touchStartY = ev.y
                isAdjustingBrightness = ev.x < (screenWidth / 2f)
                val currentBrightness = window.attributes.screenBrightness
                initialBrightness = if (currentBrightness < 0) 0.5f else currentBrightness
                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                isDraggingFullscreenGesture = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = touchStartY - ev.y
                val deltaX = Math.abs(ev.x - touchStartX)

                if (Math.abs(deltaY) > 30 && Math.abs(deltaY) > deltaX) {
                    isDraggingFullscreenGesture = true
                    val fraction = deltaY / (screenHeight * 0.65f)

                    if (isAdjustingBrightness) {
                        val newBrightness = (initialBrightness + fraction).coerceIn(0.01f, 1.0f)
                        val lp = window.attributes
                        lp.screenBrightness = newBrightness
                        window.attributes = lp

                        val percent = (newBrightness * 100).toInt()
                        hudIcon.setImageResource(R.drawable.ic_brightness)
                        hudProgressBar.progress = percent
                        hudProgressBar.progressTintList = ColorStateList.valueOf(Color.parseColor("#FBBF24"))
                        hudText.text = "$percent%"
                        showGestureHud()
                    } else {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val deltaVol = (fraction * maxVol).toInt()
                        val newVol = (initialVolume + deltaVol).coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)

                        val percent = if (maxVol > 0) ((newVol.toFloat() / maxVol) * 100).toInt() else 0
                        hudIcon.setImageResource(R.drawable.ic_volume)
                        hudProgressBar.progress = percent
                        hudProgressBar.progressTintList = ColorStateList.valueOf(Color.parseColor("#A855F7"))
                        hudText.text = "$percent%"
                        showGestureHud()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingFullscreenGesture) {
                    scheduleHideGestureHud()
                    isDraggingFullscreenGesture = false
                    return true
                }
            }
        }
        return false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && customView != null) {
            hideSystemBarsForFullscreen()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (customView == null) {
            ViewCompat.requestApplyInsets(mainRoot)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                exitFullscreenVideo()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                runOnUiThread {
                    webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_connection_lost),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onAvailable(network: Network) {
                runOnUiThread {
                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    if (!isFirstNetworkCheck) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_connection_restored),
                            Toast.LENGTH_SHORT
                        ).show()
                        if (errorLayout.visibility == View.VISIBLE) {
                            val targetUrl = lastFailedUrl ?: webView.url ?: BASE_URL
                            hideErrorOverlay()
                            progressBar.visibility = View.VISIBLE
                            webView.loadUrl(targetUrl)
                        }
                    }
                    isFirstNetworkCheck = false
                }
            }
        }
        networkCallback?.let {
            connectivityManager?.registerNetworkCallback(builder.build(), it)
        }
    }

    private fun showErrorOverlay(isServerError: Boolean = false) {
        if (isServerError) {
            errorIcon.setImageResource(R.drawable.ic_cloud_off)
            errorTitleText.text = getString(R.string.error_server_title)
            errorMessageText.text = getString(R.string.error_server_message)
        } else {
            errorIcon.setImageResource(R.drawable.ic_wifi_off)
            errorTitleText.text = getString(R.string.error_title)
            errorMessageText.text = getString(R.string.error_message)
        }
        if (errorLayout.visibility == View.VISIBLE) return
        errorLayout.alpha = 0f
        errorLayout.visibility = View.VISIBLE
        errorLayout.animate().alpha(1f).setDuration(250).start()
    }

    private fun recreateWebView() {
        try {
            swipeRefreshLayout.removeView(webView)
        } catch (e: Exception) {
            // Ignore
        }
        webView = WebView(this).apply {
            id = R.id.webview
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        swipeRefreshLayout.addView(webView)
        setupWebView()
        val targetUrl = lastFailedUrl ?: BASE_URL
        webView.loadUrl(targetUrl)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            webView.clearCache(false)
        }
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            CookieManager.getInstance().flush()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        webView.clearCache(false)
    }

    private fun hideErrorOverlay() {
        if (errorLayout.visibility == View.GONE) return
        errorLayout.animate().alpha(0f).setDuration(200).withEndAction {
            errorLayout.visibility = View.GONE
            lastFailedUrl = null
        }.start()
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        if (customView != null) {
            exitFullscreenVideo()
        }
        webView.destroy()
        super.onDestroy()
    }
}
