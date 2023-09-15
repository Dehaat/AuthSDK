package com.example.dehaatauthsdk

import android.os.Bundle
import android.view.View
import androidx.databinding.DataBindingUtil
import com.example.dehaatauthsdk.databinding.ActivityWebviewBinding

class WebViewLoginActivity :LoginActivity(){

    private lateinit var binding : ActivityWebviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        binding = DataBindingUtil.setContentView(this, R.layout.activity_webview)
        super.onCreate(savedInstanceState)
    }

    override fun getWebViewWithInitialSetup() =
        binding.webView.apply {
            webViewClient = MyWebViewClient()
            enableWebViewSettings()
            keepScreenOn = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }


    override fun onPageStartedInWebView() {
        binding.progressBar.visibility = View.VISIBLE
    }

    override fun onPageFinishedInWebView(url: String?) {
        url?.let {
            if (checkIfUrlIsRedirectUrl(it))
                binding.webView.visibility = View.GONE
            else binding.progressBar.visibility = View.GONE
        }
        super.onPageFinishedInWebView(url)
    }
}