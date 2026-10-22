package com.gabriel.os.tv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedApps: String? = null

    private val refreshRunnable = Runnable {
        cachedApps = null
        pushApps()
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B0F19"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.settings.textZoom = 100
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.isLongClickable = false
        web.isHapticFeedbackEnabled = false
        web.setOnLongClickListener { true }
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pkgReceiver, filter)
        }

        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        hideSystemUi()
        web.requestFocus()
        js("window.GOS&&GOS.resume&&GOS.resume()")
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // กดปุ่ม Home ซ้ำ -> กลับหน้าหลัก ปิด drawer / เมนู
        js("window.GOS&&GOS.home&&GOS.home()")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        js("window.GOS&&GOS.back&&GOS.back()")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun js(code: String) {
        web.evaluateJavascript(code, null)
    }

    private fun safeStart(intent: Intent, fallback: Intent? = null) {
        runOnUiThread {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                if (fallback != null) {
                    try {
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(fallback)
                    } catch (e2: Exception) { }
                }
            }
        }
    }

    private fun pushApps() {
        Thread {
            val json = cachedApps ?: buildAppsJson().also { cachedApps = it }
            runOnUiThread { js("window.GOS&&GOS.setApps($json)") }
        }.start()
    }

    private fun buildAppsJson(): String {
        val pm = packageManager
        val found = LinkedHashMap<String, ResolveInfo>()

        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            @Suppress("DEPRECATION")
            val list = pm.queryIntentActivities(query, 0)
            for (ri in list) {
                val p = ri.activityInfo.packageName
                if (p != packageName && !found.containsKey(p)) found[p] = ri
            }
        }

        val rows = found.values
            .map { ri -> Pair(ri.loadLabel(pm).toString(), ri) }
            .sortedBy { it.first.lowercase() }

        val arr = JSONArray()
        for ((label, ri) in rows) {
            val o = JSONObject()
            o.put("pkg", ri.activityInfo.packageName)
            o.put("label", label)
            o.put("icon", try { iconToDataUri(ri.loadIcon(pm)) } catch (e: Exception) { "" })
            arr.put(o)
        }
        return arr.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    }

    private fun iconToDataUri(d: Drawable): String {
        val size = 144
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun prefs() = getSharedPreferences("gabrielos", Context.MODE_PRIVATE)

    inner class Bridge {

        @JavascriptInterface
        fun requestApps() {
            pushApps()
        }

        @JavascriptInterface
        fun launch(pkg: String): Boolean {
            val intent: Intent = when (pkg) {
                "gos:settings" -> Intent(Settings.ACTION_SETTINGS)
                "gos:wifi" -> {
                    safeStart(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                    return true
                }
                else -> packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: packageManager.getLaunchIntentForPackage(pkg)
                    ?: return false
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            safeStart(intent)
            return true
        }

        @JavascriptInterface
        fun openStore(pkg: String) {
            safeStart(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            )
        }

        @JavascriptInterface
        fun appInfo(pkg: String) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }

        @JavascriptInterface
        fun network(): String {
            val cm = this@MainActivity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                val n = cm.activeNetwork ?: return "none"
                val c = cm.getNetworkCapabilities(n) ?: return "none"
                return when {
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
            }
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            return if (info != null && info.isConnected) {
                if (info.type == ConnectivityManager.TYPE_WIFI) "wifi" else "other"
            } else "none"
        }

        @JavascriptInterface
        fun loadPins(): String = prefs().getString("pins", "[]") ?: "[]"

        @JavascriptInterface
        fun savePins(json: String) {
            prefs().edit().putString("pins", json).apply()
        }
    }
}
