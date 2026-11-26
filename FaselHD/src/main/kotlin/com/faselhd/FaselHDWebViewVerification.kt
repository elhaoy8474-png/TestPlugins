package com.faselhd

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONTokener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Opens a visible WebView only after the normal HTTP request was challenged.
 * The user completes the site check manually; no synthetic clicks or challenge
 * automation are performed. The resulting page and URL-scoped cookies are then
 * returned to the provider.
 */
internal object FaselHDWebViewVerification {
    @Volatile
    var verifiedUserAgent: String? = null
        private set

    suspend fun open(url: String): String? = suspendCancellableCoroutine { continuation ->
        val activity = currentActivity()
        if (activity == null || activity.isFinishing) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        activity.runOnUiThread {
            if (!continuation.isActive) return@runOnUiThread

            val density = activity.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val dialog = Dialog(activity)
            val title = TextView(activity).apply {
                text = "التحقق من FaselHD"
                textSize = 18f
                setPadding(dp(16), dp(14), dp(16), dp(4))
            }
            val status = TextView(activity).apply {
                text = "أكمل التحقق داخل الصفحة. بعد ظهور الموقع ومحتواه اضغط «متابعة»."
                textSize = 13f
                setPadding(dp(16), dp(4), dp(16), dp(10))
            }
            val button = Button(activity).apply {
                text = "متابعة بعد التحقق"
                isEnabled = false
            }
            val webView = WebView(activity)
            var completed = false
            var complete: ((String?) -> Unit)? = null
            val handler = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                status.text = "انتهت مهلة التحقق. افتح المصدر وحاول مرة أخرى."
                complete?.invoke(null)
            }

            complete = { html ->
                if (!completed) {
                    completed = true
                    handler.removeCallbacks(timeout)
                    runCatching { CookieManager.getInstance().flush() }
                    runCatching {
                        webView.stopLoading()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.destroy()
                    }
                    if (dialog.isShowing) dialog.dismiss()
                    if (continuation.isActive) continuation.resume(html)
                }
            }

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                addView(status, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                addView(webView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ))
                addView(button, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(12), dp(6), dp(12), dp(12))
                })
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                userAgentString = WebSettings.getDefaultUserAgent(activity)
            }
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    pageUrl: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    status.text = "جارٍ تحميل صفحة التحقق..."
                    button.isEnabled = false
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    status.text = "أكمل أي تحقق ظاهر في الصفحة، ثم اضغط «متابعة بعد التحقق»."
                    button.isEnabled = true
                }
            }

            button.setOnClickListener {
                button.isEnabled = false
                status.text = "جارٍ فحص نتيجة التحقق..."
                webView.evaluateJavascript("document.documentElement.outerHTML") { encoded ->
                    val html = runCatching {
                        JSONTokener(encoded ?: "null").nextValue() as? String
                    }.getOrNull()

                    when {
                        html.isNullOrBlank() -> {
                            status.text = "تعذر قراءة الصفحة. أعد تحميلها وحاول مرة أخرى."
                            button.isEnabled = true
                        }
                        FaselHDParser.isChallengePage(html) -> {
                            status.text = "الموقع ما زال يطلب التحقق. أكمله داخل الصفحة ثم حاول مجددًا."
                            button.isEnabled = true
                        }
                        else -> {
                            verifiedUserAgent = webView.settings.userAgentString
                            complete?.invoke(html)
                        }
                    }
                }
            }

            dialog.setContentView(content)
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnCancelListener { complete?.invoke(null) }
            dialog.show()
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            handler.postDelayed(timeout, 240_000L)
            webView.loadUrl(url)

            continuation.invokeOnCancellation {
                activity.runOnUiThread { complete?.invoke(null) }
            }
        }
    }

    private fun currentActivity(): Activity? = runCatching {
        // KodaStream exposes the resumed activity through CommonActivity.
        // Reflection keeps this extension independent of the host app module at compile time.
        val commonActivity = Class.forName("com.lagradost.cloudstream3.CommonActivity")
        val instance = commonActivity.getField("INSTANCE").get(null)
        commonActivity.getMethod("getActivity").invoke(instance) as? Activity
    }.getOrNull()
}
