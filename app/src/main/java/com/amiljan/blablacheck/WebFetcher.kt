package com.amiljan.blablacheck

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

const val HOME = "https://www.blablacar.hr/"
private const val TAG = "BBC"

/**
 * Talks to blablacar.hr through one real WebView (the site is behind DataDome, plain HTTP gets a captcha).
 * The page itself mints an anonymous Bearer token; a document-start hook copies the headers of the site's own
 * edge.blablacar.hr calls to window.__bbcH, and our calls are then made from inside the page with those headers.
 * All WebView access happens on the main thread; calls are serialized by a mutex.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebFetcher(private val ctx: Context) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val mutex = Mutex()
    private var loadedAt = 0L
    @Volatile private var pageDone: CompletableDeferred<Unit>? = null
    @Volatile private var blockedUntil = 0L
    private var blockCount = 0

    /** True while we are backing off after a block. */
    fun blockedUntilMillis() = blockedUntil.takeIf { it > System.currentTimeMillis() } ?: 0L

    private fun registerBlock(): Blocked {
        blockCount++
        val minutes = minOf(30L shl (blockCount - 1).coerceAtMost(4), 240L)
        blockedUntil = System.currentTimeMillis() + minutes * 60_000
        loadedAt = 0
        Log.w(TAG, "blocked by DataDome, backing off $minutes min")
        return Blocked(blockedUntil)
    }

    private fun checkCooldown() { if (blockedUntilMillis() > 0) throw Blocked(blockedUntil) }

    /** Maps a failed API result to the right exception: a captcha we can solve by hand, or a hard block we must wait out. */
    private fun fail(out: String): Nothing {
        if (out.startsWith("ERR 403")) {
            if (out.contains("captcha-delivery")) { loadedAt = 0; throw CaptchaRequired() }
            throw registerBlock()
        }
        throw FetchError(out.take(120))
    }

    private val hook = """
      (function(){
        var of=window.fetch;
        window.fetch=function(){
          var a=arguments,u=(typeof a[0]==='string')?a[0]:(a[0]&&a[0].url);
          try{ if(/edge\.blablacar/.test(u)){
            var h=(a[1]&&a[1].headers)||(a[0]&&a[0].headers); var o={};
            if(h&&h.forEach){h.forEach(function(v,k){o[k]=v;});}else if(h){o=h;}
            if(o.authorization||o.Authorization) window.__bbcH=o; } }catch(e){}
          return of.apply(this,a);
        };
      })();
    """.trimIndent()

    inner class Bridge {
        @JavascriptInterface fun result(id: String, payload: String) { pending.remove(id)?.complete(payload) }
    }

    /** The shared WebView. Must first be touched on the main thread. CaptchaActivity displays this same instance. */
    val webView: WebView by lazy {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "BBCBridge")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, hook, setOf("https://www.blablacar.hr"))
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { pageDone?.complete(Unit) }
            }
        }
    }

    private suspend fun evalRaw(js: String): String = suspendCancellableCoroutine { c ->
        webView.evaluateJavascript(js) { c.resume(it ?: "null") }
    }

    private suspend fun hasSession() = evalRaw("!!(window.__bbcH && window.__bbcH.authorization)") == "true"

    private suspend fun ensureReady(force: Boolean = false) {
        val stale = System.currentTimeMillis() - loadedAt > 25 * 60_000
        if (force || stale || webView.url == null) {
            pageDone = CompletableDeferred()
            webView.loadUrl(HOME)
            loadedAt = System.currentTimeMillis()
            withTimeoutOrNull(30_000) { pageDone?.await() }
        }
        repeat(60) {
            if (hasSession()) return
            delay(500)
        }
        val text = evalRaw("(document.body&&document.body.innerText||'').substring(0,400)")
        if (text.contains("blocked", ignoreCase = true)) throw registerBlock()
        val captcha = evalRaw("document.documentElement.innerHTML.indexOf('captcha-delivery')>=0")
        loadedAt = 0 // force a reload next time
        if (captcha == "true") throw CaptchaRequired()
        throw FetchError("No session headers from blablacar.hr")
    }

    /** Runs an async JS snippet that must call BBCBridge.result(ID, text). */
    private suspend fun runJs(body: String, timeoutMs: Long = 90_000): String {
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<String>()
        pending[id] = d
        webView.evaluateJavascript("(async function(ID){ try{ $body }catch(e){ BBCBridge.result(ID,'ERR '+e); } })(${JSONObject.quote(id)})", null)
        return try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun searchUrl(from: City, to: City, date: String) =
        "https://edge.blablacar.hr/trip/search/v9?from_place_id=${enc(from.placeId)}&to_place_id=${enc(to.placeId)}" +
            "&departure_date=$date&requested_seats=1&supply=CARPOOLING&search_origin=HOME&jrny_stp=ONE_WAY"

    /** All carpool rides for one direction and date (follows pagination). */
    suspend fun search(from: City, to: City, date: String): List<Ride> = withContext(Dispatchers.Main) {
        mutex.withLock {
            checkCooldown()
            var out = trySearch(from, to, date, force = false)
            if (out.startsWith("ERR 401")) out = trySearch(from, to, date, force = true)
            if (!out.startsWith("OK")) fail(out)
            blockCount = 0
            Parser.rides(out.substring(2))
        }
    }

    private suspend fun trySearch(from: City, to: City, date: String, force: Boolean): String {
        ensureReady(force)
        val url = JSONObject.quote(searchUrl(from, to, date))
        return runJs("""
          var h=Object.assign({},window.__bbcH); var pages=[]; var cur=null;
          for(var i=0;i<10;i++){
            var u=$url+(cur?'&from_cursor='+encodeURIComponent(cur):'');
            var r=await fetch(u,{headers:h,credentials:'include'});
            if(r.status!=200){ var eb=''; try{eb=(await r.text()).substring(0,300);}catch(e){} BBCBridge.result(ID,'ERR '+r.status+' '+eb); return; }
            var j=await r.json(); pages.push(j);
            var p=j.results_content&&j.results_content.results&&j.results_content.results.pagination;
            if(!p||!p.next_cursor) break; cur=p.next_cursor;
          }
          BBCBridge.result(ID,'OK'+JSON.stringify(pages));
        """.trimIndent())
    }

    /** Raw HTML of a trip page (same-origin fetch from inside the page, so the DataDome cookie applies). */
    suspend fun tripPage(url: String): String = withContext(Dispatchers.Main) {
        mutex.withLock {
            checkCooldown()
            var out = tryTrip(url, false)
            if (out.startsWith("ERR 401")) out = tryTrip(url, true)
            if (!out.startsWith("OK")) fail(out)
            out.substring(2)
        }
    }

    private suspend fun tryTrip(url: String, force: Boolean): String {
        ensureReady(force)
        return runJs("""
          var r=await fetch(${JSONObject.quote(url)},{credentials:'include'});
          var t=await r.text();
          BBCBridge.result(ID,r.status==200?'OK'+t:'ERR '+r.status+' '+t.substring(0,300));
        """.trimIndent())
    }

    fun log(msg: String) = Log.i(TAG, msg)
}
