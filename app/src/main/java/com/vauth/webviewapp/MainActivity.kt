package com.vauth.webviewapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_URL =
            "https://quypickleball-306bf.web.app/"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var container: FrameLayout

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var isFirstPageLoad = true

    private val fileChooserLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = fileUploadCallback
            fileUploadCallback = null

            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val clipData = data?.clipData

                val uris = when {
                    clipData != null ->
                        (0 until clipData.itemCount)
                            .map { clipData.getItemAt(it).uri }
                            .toTypedArray()

                    data?.data != null ->
                        arrayOf(data.data!!)

                    else -> emptyArray()
                }

                callback?.onReceiveValue(uris)
            } else {
                callback?.onReceiveValue(null)
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            geolocationCallback?.invoke(
                geolocationOrigin,
                granted,
                false
            )

            geolocationCallback = null
            geolocationOrigin = null
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val request = pendingPermissionRequest
            pendingPermissionRequest = null

            if (request == null) return@registerForActivityResult

            val granted = permissions.values.all { it }

            if (granted) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Ẩn thanh tiêu đề của Android nếu có.
            supportActionBar?.hide()

            // Khung chính chiếm toàn bộ vùng nội dung.
            container = FrameLayout(this)

            // WebView chiếm toàn bộ màn hình ứng dụng.
            webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            // Thanh tiến trình tải trang.
            progressBar = ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    8
                )
                max = 100
                visibility = View.GONE
            }

            // Màn hình chờ khi mở ứng dụng lần đầu.
            loadingOverlay = FrameLayout(this).apply {
                setBackgroundColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        R.color.loadingBackground
                    )
                )

                val spinner = ProgressBar(this@MainActivity)

                addView(
                    spinner,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                )
            }

            container.addView(webView)
            container.addView(progressBar)
            container.addView(loadingOverlay)

            setContentView(container)

            setupBackPressHandler()
            setupWebView()

            val url = BuildConfig.WEBSITE_URL
                .takeIf { it.isNotBlank() }
                ?: DEFAULT_URL

            webView.loadUrl(url)

        } catch (e: Exception) {
            Log.e(TAG, "Cannot initialize app", e)
            Toast.makeText(
                this,
                "Không thể mở ứng dụng",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true

            builtInZoomControls = true
            displayZoomControls = false

            useWideViewPort = true
            loadWithOverviewMode = true

            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            setGeolocationEnabled(true)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false

            userAgentString = "$userAgentString WebViewApp/1.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                super.onPageFinished(view, url)

                progressBar.visibility = View.GONE

                if (isFirstPageLoad) {
                    loadingOverlay.visibility = View.GONE
                    isFirstPageLoad = false
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val url = uri.toString()

                when (uri.scheme?.lowercase()) {
                    "http", "https" -> return false

                    "tel", "mailto", "sms" -> {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot open external link", e)
                        }
                        return true
                    }

                    "intent" -> {
                        try {
                            val intent = Intent.parseUri(
                                url,
                                Intent.URI_INTENT_SCHEME
                            )

                            if (intent.resolveActivity(packageManager) != null) {
                                startActivity(intent)
                            } else {
                                intent.getStringExtra("browser_fallback_url")
                                    ?.let { fallback ->
                                        view?.loadUrl(fallback)
                                    }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot handle intent URL", e)
                        }
                        return true
                    }

                    else -> return true
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    loadingOverlay.visibility = View.GONE

                    Log.e(
                        TAG,
                        "Page load error: ${error?.description}"
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progressBar.progress = newProgress

                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                return try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(
                                Intent.EXTRA_ALLOW_MULTIPLE,
                                true
                            )
                        }

                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open file chooser", e)
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin == null || callback == null) return

                // Chỉ cấp quyền vị trí cho website của ứng dụng.
                val trustedOrigin =
                    origin.startsWith(
                        "https://quypickleball-306bf.web.app"
                    ) ||
                    origin.startsWith(
                        "https://quypickleball-306bf.firebaseapp.com"
                    )

                if (!trustedOrigin) {
                    callback.invoke(origin, false, false)
                    return
                }

                geolocationOrigin = origin
                geolocationCallback = callback

                val permissions = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )

                if (hasPermissions(permissions)) {
                    callback.invoke(origin, true, false)
                    geolocationCallback = null
                    geolocationOrigin = null
                } else {
                    locationPermissionLauncher.launch(permissions)
                }
            }

            override fun onPermissionRequest(
                request: PermissionRequest?
            ) {
                if (request == null) return

                runOnUiThread {
                    val permissions = mutableListOf<String>()

                    request.resources.forEach { resource ->
                        when (resource) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                permissions.add(Manifest.permission.CAMERA)

                            PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                permissions.add(Manifest.permission.RECORD_AUDIO)
                        }
                    }

                    if (permissions.isEmpty()) {
                        request.deny()
                    } else if (hasPermissions(permissions.toTypedArray())) {
                        request.grant(request.resources)
                    } else {
                        pendingPermissionRequest = request
                        mediaPermissionLauncher.launch(
                            permissions.distinct().toTypedArray()
                        )
                    }
                }
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun hasPermissions(
        permissions: Array<String>
    ): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null

        geolocationCallback?.invoke(
            geolocationOrigin,
            false,
            false
        )
        geolocationCallback = null
        geolocationOrigin = null

        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null

        CookieManager.getInstance().flush()

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }

        super.onDestroy()
    }
}
