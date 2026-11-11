package com.amiljan.blablacheck

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/** Shows the shared fetcher WebView so a DataDome captcha can be solved once; the cookie then serves the headless fetches. */
class CaptchaActivity : ComponentActivity() {
    private lateinit var holder: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = (application as App).fetcher.webView
        (wv.parent as? ViewGroup)?.removeView(wv)
        holder = FrameLayout(this)
        holder.addView(wv, FrameLayout.LayoutParams(-1, -1))
        setContentView(holder)
        wv.loadUrl(HOME)
    }
    override fun onDestroy() { holder.removeAllViews(); super.onDestroy() }
}
