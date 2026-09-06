package com.example.mddorma

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class SplashActivity : ComponentActivity() {

    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pre-inicializar directorios de caché de WebView con anticipación
        MddormaApp.ensureWebViewCacheDirectories(applicationContext)

        // Modo pantalla completa inmersiva
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_splash)

        val root = findViewById<View>(R.id.splash_root)
        val logoImage = findViewById<ImageView>(R.id.splash_logo)
        val appName = findViewById<TextView>(R.id.splash_app_name)
        val subtitle = findViewById<TextView>(R.id.splash_subtitle)

        // Animación suave de entrada tipo Netflix (inicia visible para evitar pantallas en blanco)
        logoImage.scaleX = 0.85f
        logoImage.scaleY = 0.85f
        logoImage.alpha = 0.6f

        logoImage.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .alpha(1.0f)
            .setDuration(2200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        appName.alpha = 0.8f
        appName.animate()
            .alpha(1.0f)
            .translationYBy(-8f)
            .setDuration(1800)
            .setStartDelay(300)
            .start()

        subtitle.alpha = 0.6f
        subtitle.animate()
            .alpha(1.0f)
            .setDuration(1600)
            .setStartDelay(600)
            .start()

        // Permitir tocar para saltar la intro de inmediato
        root.setOnClickListener {
            navigateToMain()
        }

        // Navegar automáticamente a MainActivity tras 2.8 segundos
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToMain()
        }, 2800)
    }

    private fun navigateToMain() {
        if (hasNavigated) return
        hasNavigated = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
