package com.malick.nsiacapital

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

class MainActivity : AppCompatActivity(), DashboardRenderer.Actions {

    companion object {
        const val LOGIN_URL = "https://clients.nsiafinance.com/templates/logon/logon.cfm"
        const val BRVM_URL = "https://www.brvm.org/fr/cours-actions/0"
        const val SIKA_URL = "https://www.sikafinance.com/marches/aaz"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var dashScroll: ScrollView
    private lateinit var web: WebView
    private lateinit var brvm: WebView
    private lateinit var brvmBox: View
    private lateinit var nav: BottomNavigationView

    private lateinit var store: DataStore
    private lateinit var renderer: DashboardRenderer
    private lateinit var extractJs: String

    private val main = Handler(Looper.getMainLooper())
    private var docStartScript = false
    private var brvmLoaded = false

    private val crawlQueue = ArrayDeque<String>()
    private var crawling = false
    private var crawlTotal = 0
    private val crawlNextRunnable = Runnable { crawlNext() }

    private val saveRunnable = Runnable { store.save() }
    private val renderRunnable = Runnable { if (dashScroll.visibility == View.VISIBLE) renderer.render(store) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        dashScroll = findViewById(R.id.dashScroll)
        web = findViewById(R.id.web)
        brvm = findViewById(R.id.brvm)
        brvmBox = findViewById(R.id.brvmBox)
        findViewById<MaterialButtonToggleGroup>(R.id.marketToggle).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            brvmLoaded = true
            when (checkedId) {
                R.id.btnSika -> {
                    toolbar.title = "Sika Finance"
                    brvm.loadUrl(SIKA_URL)
                }
                else -> {
                    toolbar.title = "Cours BRVM"
                    brvm.loadUrl(BRVM_URL)
                }
            }
        }
        nav = findViewById(R.id.nav)

        store = DataStore(this)
        extractJs = assets.open("extract.js").bufferedReader().use { it.readText() }
        renderer = DashboardRenderer(this, findViewById<LinearLayout>(R.id.dash), this)

        setupToolbar()
        setupClientWebView()
        setupBrvmWebView()

        nav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(LOGIN_URL)
        }
        nav.selectedItemId = if (store.isEmpty()) R.id.nav_site else R.id.nav_dash
        showTab(nav.selectedItemId)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    web.visibility == View.VISIBLE && web.canGoBack() -> web.goBack()
                    brvmBox.visibility == View.VISIBLE && brvm.canGoBack() -> brvm.goBack()
                    nav.selectedItemId != R.id.nav_dash -> nav.selectedItemId = R.id.nav_dash
                    else -> finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        store.save()
        CookieManager.getInstance().flush()
    }

    // ---------- Onglets ----------

    private fun showTab(id: Int) {
        dashScroll.visibility = if (id == R.id.nav_dash) View.VISIBLE else View.GONE
        web.visibility = if (id == R.id.nav_site) View.VISIBLE else View.GONE
        brvmBox.visibility = if (id == R.id.nav_brvm) View.VISIBLE else View.GONE
        when (id) {
            R.id.nav_dash -> {
                toolbar.title = "Mon tableau de bord"
                renderer.render(store)
            }
            R.id.nav_site -> toolbar.title = "Espace client NSIA"
            R.id.nav_brvm -> {
                toolbar.title = "Marchés"
                if (!brvmLoaded) {
                    brvmLoaded = true
                    brvm.loadUrl(BRVM_URL)
                }
            }
        }
        if (!crawling) toolbar.subtitle = null
    }

    private fun setupToolbar() {
        toolbar.inflateMenu(R.menu.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reload -> {
                    when {
                        web.visibility == View.VISIBLE -> web.reload()
                        brvmBox.visibility == View.VISIBLE -> brvm.reload()
                        else -> renderer.render(store)
                    }
                    true
                }
                R.id.action_login -> {
                    nav.selectedItemId = R.id.nav_site
                    web.loadUrl(LOGIN_URL)
                    true
                }
                else -> false
            }
        }
    }

    // ---------- WebView de l'espace client ----------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupClientWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Bridge(), "NsiaBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, extractJs, setOf("https://clients.nsiafinance.com"))
                docStartScript = true
            } catch (e: Exception) {
                docStartScript = false
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host.endsWith("nsiafinance.com")) return false
                openExternal(request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.INVISIBLE
                if (docStartScript) {
                    view.evaluateJavascript("window.__nsiaScan && window.__nsiaScan();", null)
                } else {
                    view.evaluateJavascript(extractJs, null)
                }
                onClientPageFinished(url ?: "")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
            }
        }
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun post(type: String, url: String, json: String) {
            main.post { onData(type, url, json) }
        }
    }

    private fun onData(type: String, url: String, json: String) {
        if (!url.startsWith("https://clients.nsiafinance.com")) return
        if (store.add(type, url, json)) {
            main.removeCallbacks(saveRunnable)
            main.postDelayed(saveRunnable, 2000)
            main.removeCallbacks(renderRunnable)
            main.postDelayed(renderRunnable, 800)
        }
    }

    private fun onClientPageFinished(url: String) {
        if (!crawling) return
        if (DataStore.isLoginUrl(url)) {
            stopCrawl("Session expirée : reconnecte-toi puis relance la synchronisation.")
            return
        }
        // laisse le temps aux tableaux chargés en AJAX d'apparaître
        main.removeCallbacks(crawlNextRunnable)
        main.postDelayed(crawlNextRunnable, 3500)
    }

    // ---------- Synchronisation automatique ----------

    override fun onSync() {
        val links = store.candidateLinks()
        if (links.isEmpty()) {
            toast("Connecte-toi d'abord dans l'onglet Espace client, puis réessaie.")
            nav.selectedItemId = R.id.nav_site
            return
        }
        crawlQueue.clear()
        crawlQueue.addAll(links)
        crawlTotal = links.size
        crawling = true
        nav.selectedItemId = R.id.nav_site
        crawlNext()
    }

    private fun crawlNext() {
        main.removeCallbacks(crawlNextRunnable)
        if (!crawling) return
        val next = crawlQueue.removeFirstOrNull()
        if (next == null) {
            crawling = false
            store.markSynced()
            store.save()
            toolbar.subtitle = null
            toast("Synchronisation terminée")
            nav.selectedItemId = R.id.nav_dash
            return
        }
        toolbar.subtitle = "Synchronisation ${crawlTotal - crawlQueue.size}/$crawlTotal"
        web.loadUrl(next)
        // sécurité si la page ne finit jamais de charger
        main.postDelayed(crawlNextRunnable, 20000)
    }

    private fun stopCrawl(message: String) {
        crawling = false
        crawlQueue.clear()
        main.removeCallbacks(crawlNextRunnable)
        toolbar.subtitle = null
        toast(message)
    }

    // ---------- Export / effacement ----------

    override fun onExport() {
        try {
            val file = File(cacheDir, "nsia-capital-donnees.json")
            file.writeText(store.exportJson())
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Exporter les données"))
        } catch (e: Exception) {
            toast("Export impossible : ${e.message}")
        }
    }

    override fun onClear() {
        AlertDialog.Builder(this)
            .setTitle("Effacer les données ?")
            .setMessage("Les données lues sur le site seront supprimées du téléphone. Ta connexion au site n'est pas touchée.")
            .setPositiveButton("Effacer") { _, _ ->
                store.clear()
                renderer.render(store)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onOpenLink(url: String) {
        nav.selectedItemId = R.id.nav_site
        web.loadUrl(url)
    }

    // ---------- WebView BRVM ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupBrvmWebView() {
        with(brvm.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        brvm.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host.endsWith("brvm.org") || host.endsWith("sikafinance.com")) return false
                openExternal(request.url)
                return true
            }
        }
    }

    // ---------- Utilitaires ----------

    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", userAgent)
                setTitle(name)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Téléchargement : $name")
        } catch (e: Exception) {
            toast("Téléchargement impossible")
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            toast("Impossible d'ouvrir ce lien")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
