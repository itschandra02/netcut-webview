package com.itschandra.netcut

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * NetCut WebView — wrapper native untuk dashboard NetCut
 * (http://127.0.0.1:<port>) yang di-serve oleh daemon Termux/root.
 *
 * Fitur:
 *  - WebView penuh (JS, DOM storage, zoom) ke dashboard lokal
 *  - Progress bar brand saat loading
 *  - Overlay error + auto-retry tiap 4 detik saat daemon mati
 *  - Tombol "MULAI NETCUT (ROOT)" — start daemon via su, lalu reload
 *  - Tombol refresh mengambang (FAB)
 *  - Back = history webview, double-back = keluar
 *  - Link eksternal dibuka di browser sistem
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var overlay: LinearLayout
    private lateinit var overlayStatus: TextView

    private var port = 8080
    private var errorShown = false
    private var lastBackAt = 0L

    private val base: String get() = "http://127.0.0.1:$port"

    // ---- warna design system (brand #006DF0, mono untuk angka/IP) ----
    private val brand = Color.parseColor("#006DF0")
    private val textPrimaryDark = Color.parseColor("#0E1218")
    private val textMutedDark = Color.parseColor("#64748B")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        port = intent.getIntExtra("port", 8080)

        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) Color.parseColor("#0E1218") else Color.parseColor("#F4F6FB")
        val textPrimary = if (night) Color.parseColor("#E6EAF2") else textPrimaryDark
        val textMuted = if (night) Color.parseColor("#8B96A8") else textMutedDark

        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        // ---- WebView ----
        web = WebView(this)
        web.setBackgroundColor(bg)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
        }
        WebView.setWebContentsDebuggingEnabled(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                return if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
                    false // tetap di webview
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {
                    }
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                hideError()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) showError()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }

        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- progress bar tipis di atas ----
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.isIndeterminate = false
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(brand)
        progress.indeterminateTintList = ColorStateList.valueOf(brand)
        progress.visibility = View.GONE
        root.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))

        // ---- FAB refresh ----
        val fab = ImageView(this)
        fab.setImageResource(R.drawable.ic_refresh)
        fab.imageTintList = ColorStateList.valueOf(Color.WHITE)
        val fabBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(brand)
        }
        fab.background = fabBg
        fab.elevation = dp(6).toFloat()
        val pad = dp(14)
        fab.setPadding(pad, pad, pad, pad)
        fab.setOnClickListener {
            if (!errorShown) {
                progress.visibility = View.VISIBLE
                web.reload()
            } else {
                retryNow()
            }
        }
        val fabLp = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END)
        fabLp.rightMargin = dp(16)
        fabLp.bottomMargin = dp(16)
        root.addView(fab, fabLp)

        // ---- overlay error ----
        overlay = buildErrorOverlay(bg, textPrimary, textMuted)
        overlay.visibility = View.GONE
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)

        // ---- muat dashboard / cek server ----
        thread {
            val up = serverUp()
            runOnUiThread {
                if (up) web.loadUrl(base) else showError()
            }
        }
    }

    private fun buildErrorOverlay(bg: Int, textPrimary: Int, textMuted: Int): LinearLayout {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.gravity = Gravity.CENTER
        lay.setBackgroundColor(bg)
        lay.setPadding(dp(32), dp(32), dp(32), dp(32))

        // ikon: glyph launcher dalam lingkaran brand
        val iconWrap = FrameLayout(this)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(brand)
        }
        iconWrap.background = circle
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_launcher_foreground)
        val iconLp = FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
        iconWrap.addView(icon, iconLp)
        val wrapLp = LinearLayout.LayoutParams(dp(108), dp(108))
        wrapLp.gravity = Gravity.CENTER_HORIZONTAL
        wrapLp.bottomMargin = dp(20)
        lay.addView(iconWrap, wrapLp)

        val title = TextView(this)
        title.text = "NetCut tidak merespon"
        title.textSize = 20f
        title.setTextColor(textPrimary)
        title.gravity = Gravity.CENTER
        title.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        lay.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val urlText = TextView(this)
        urlText.text = "$base/api/state"
        urlText.textSize = 13f
        urlText.setTextColor(textMuted)
        urlText.gravity = Gravity.CENTER
        urlText.typeface = android.graphics.Typeface.MONOSPACE
        val urlLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        urlLp.topMargin = dp(8)
        lay.addView(urlText, urlLp)

        overlayStatus = TextView(this)
        overlayStatus.text = "Daemon NetCut belum jalan — mencoba koneksi ulang…"
        overlayStatus.textSize = 13f
        overlayStatus.setTextColor(textMuted)
        overlayStatus.gravity = Gravity.CENTER
        val stLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        stLp.topMargin = dp(20)
        stLp.bottomMargin = dp(28)
        lay.addView(overlayStatus, stLp)

        val retry = Button(this)
        retry.text = "COBA LAGI"
        retry.setTextColor(Color.WHITE)
        retry.textSize = 14f
        retry.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(brand)
        }
        retry.setOnClickListener { retryNow() }
        val retryLp = LinearLayout.LayoutParams(dp(260), dp(48))
        retryLp.gravity = Gravity.CENTER_HORIZONTAL
        retryLp.bottomMargin = dp(12)
        lay.addView(retry, retryLp)

        val start = Button(this)
        start.text = "MULAI NETCUT (ROOT)"
        start.setTextColor(brand)
        start.textSize = 14f
        start.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), brand)
        }
        start.setOnClickListener { startNetcutDaemon() }
        val startLp = LinearLayout.LayoutParams(dp(260), dp(48))
        startLp.gravity = Gravity.CENTER_HORIZONTAL
        lay.addView(start, startLp)

        return lay
    }

    // ---- koneksi / retry ----

    private fun serverUp(): Boolean = try {
        val conn = URL("$base/api/state").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (_: Exception) {
        false
    }

    private val retryTick = object : Runnable {
        override fun run() {
            if (!errorShown) return
            thread {
                val up = serverUp()
                runOnUiThread {
                    if (up) {
                        hideError()
                        web.loadUrl(base)
                    } else {
                        overlayStatus.text = "Belum ada server di $base — coba lagi otomatis…"
                    }
                }
            }
            handler.postDelayed(this, 4000)
        }
    }

    private fun showError() {
        if (errorShown) return
        errorShown = true
        progress.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(retryTick)
        handler.postDelayed(retryTick, 4000)
    }

    private fun hideError() {
        errorShown = false
        overlay.visibility = View.GONE
        handler.removeCallbacks(retryTick)
    }

    private fun retryNow() {
        overlayStatus.text = "Mencoba koneksi…"
        thread {
            val up = serverUp()
            runOnUiThread {
                if (up) {
                    hideError()
                    web.loadUrl(base)
                } else {
                    overlayStatus.text = "Masih belum terhubung. Cek daemon NetCut / root."
                    handler.removeCallbacks(retryTick)
                    handler.postDelayed(retryTick, 4000)
                }
            }
        }
    }

    private fun startNetcutDaemon() {
        overlayStatus.text = "Menjalankan NetCut via su (root)…"
        thread {
            var up = false
            try {
                val cmd = "cd /data/data/com.termux/files/home/netcut && " +
                        "HOME=/data/data/com.termux/files/home " +
                        "nohup /data/data/com.termux/files/usr/bin/python netcut.py web --port $port " +
                        "> data/netcut.log 2>&1 &"
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                proc.inputStream.readBytes()
                proc.errorStream.readBytes()
                proc.waitFor()
                val deadline = System.currentTimeMillis() + 15000
                while (System.currentTimeMillis() < deadline && !up) {
                    up = serverUp()
                    if (!up) Thread.sleep(1000)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal menjalankan su: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            runOnUiThread {
                if (up) {
                    hideError()
                    web.loadUrl(base)
                } else {
                    overlayStatus.text = "Gagal menjalankan NetCut — pastikan root (su) diizinkan untuk app ini."
                    handler.removeCallbacks(retryTick)
                    handler.postDelayed(retryTick, 4000)
                }
            }
        }
    }

    // ---- navigasi ----

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) {
            web.goBack()
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2000) {
                finish()
            } else {
                lastBackAt = now
                Toast.makeText(this, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        if (errorShown) retryNow()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
