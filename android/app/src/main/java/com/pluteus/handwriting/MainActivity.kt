package com.pluteus.handwriting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.Locale

/**
 * 走り書きメモ Android版。
 * 本体はPWA版（index.html）をWebViewAssetLoader経由でそのまま表示し、
 * 音声入力だけAndroid標準のSpeechRecognizerに橋渡しする。
 * Web側は window.HandwritingAndroid.startSpeech() を呼び、
 * 結果は window.__onNativeSpeechResult(text) 等のコールバックで受け取る。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecognition()
            } else {
                jsError("マイクの使用が許可されませんでした。アプリの設定からマイクを許可してください。")
                jsEnd()
            }
        }

    inner class SpeechBridge {
        @JavascriptInterface
        fun startSpeech() {
            mainHandler.post { startSpeechInternal() }
        }

        @JavascriptInterface
        fun stopSpeech() {
            mainHandler.post {
                try {
                    recognizer?.stopListening()
                } catch (e: Exception) {
                    // 既に停止している場合は無視
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Web側がマイクを求めた場合、RECORD_AUDIO許可があるときだけ渡す
                val audio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (audio && ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
        }
        webView.addJavascriptInterface(SpeechBridge(), "HandwritingAndroid")
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun startSpeechInternal() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecognition()
    }

    private fun startRecognition() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            jsError("この端末では音声認識サービスが利用できません。Googleアプリ等の音声認識が有効か確認してください。")
            jsEnd()
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    listening = false
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) jsResult(text)
                    else jsError("音声を認識できませんでした。")
                    jsEnd()
                }

                override fun onError(error: Int) {
                    listening = false
                    jsError(errorMessage(error))
                    jsEnd()
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            listening = true
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            jsError("OS標準の音声認識を開始できませんでした。")
            jsEnd()
        }
    }

    private fun errorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "録音でエラーが発生しました。"
            SpeechRecognizer.ERROR_CLIENT -> "音声認識でエラーが発生しました。"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイクの使用が許可されませんでした。アプリの設定からマイクを許可してください。"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークエラーで音声認識できませんでした。"
            SpeechRecognizer.ERROR_NO_MATCH -> "音声を認識できませんでした。"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "音声認識が使用中です。少し待ってからお試しください。"
            SpeechRecognizer.ERROR_SERVER -> "音声認識サーバーでエラーが発生しました。"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声を認識できませんでした。"
            else -> "音声認識でエラーが発生しました。"
        }
    }

    private fun jsResult(text: String) {
        webView.evaluateJavascript(
            "window.__onNativeSpeechResult && window.__onNativeSpeechResult(" + JSONObject.quote(text) + ");",
            null
        )
    }

    private fun jsError(message: String) {
        webView.evaluateJavascript(
            "window.__onNativeSpeechError && window.__onNativeSpeechError(" + JSONObject.quote(message) + ");",
            null
        )
    }

    private fun jsEnd() {
        webView.evaluateJavascript(
            "window.__onNativeSpeechEnd && window.__onNativeSpeechEnd();",
            null
        )
    }

    override fun onDestroy() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // 無視
        }
        recognizer = null
        super.onDestroy()
    }
}
