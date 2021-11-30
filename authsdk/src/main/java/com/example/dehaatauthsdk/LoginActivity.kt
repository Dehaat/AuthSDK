package com.example.dehaatauthsdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.dehaatauthsdk.DeHaatAuth.OperationState.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.openid.appauth.*
import net.openid.appauth.AuthorizationException.AuthorizationRequestErrors
import net.openid.appauth.AuthorizationService.TokenResponseCallback

class LoginActivity : Activity() {
    private lateinit var mAuthService: AuthorizationService

    private var _initialConfiguration: Configuration? = null
    private val initialConfiguration get() = _initialConfiguration!!

    private var _mAuthServiceConfiguration: AuthorizationServiceConfiguration? = null
    private val mAuthServiceConfiguration get() = _mAuthServiceConfiguration!!
    private var _mAuthRequest: AuthorizationRequest? = null
    private val mAuthRequest get() = _mAuthRequest!!
    private var _mLogoutRequest: EndSessionRequest? = null
    private val mLogoutRequest get() = _mLogoutRequest!!

    private var _webView: WebView? = null
    private val webView get() = _webView!!

    private lateinit var job: Job
    private var isPageLoaded = false
    private lateinit var timeoutHandler: Handler
    private val TIMEOUT = 30L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize()
    }

    private fun initialize() {
        setUpWebView()
        timeoutHandler = Handler(Looper.getMainLooper())
        _initialConfiguration = Configuration.getInstance(applicationContext)
        job = CoroutineScope((IO)).launch {
            startAuthorizationServiceCreation()
        }
    }

    private fun setUpWebView() {
        _webView = WebView(this).apply {
            webViewClient = MyWebViewClient()
            enableWebViewSettings()
        }
        _webView?.keepScreenOn = true
        _webView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.enableWebViewSettings() {
        settings.apply {
            loadsImagesAutomatically = true
            useWideViewPort = true
            allowContentAccess = true
            allowFileAccess = true
            databaseEnabled = true
            domStorageEnabled = true
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            pluginState = WebSettings.PluginState.ON
            setAppCacheEnabled(true)
        }
    }

    private fun startAuthorizationServiceCreation() {
        disposeCurrentServiceIfExist()
        mAuthService = createNewAuthorizationService()
        initialConfiguration.discoveryUri?.let {
            fetchEndpointsFromDiscoveryUrl(it)
        } ?: kotlin.run {
            handleErrorAndFinishActivity(Exception(Constants.DISCOVERY_URL_NULL))
        }
    }

    private fun disposeCurrentServiceIfExist() {
        if (::mAuthService.isInitialized) {
            mAuthService.dispose()
        }
    }

    private fun fetchEndpointsFromDiscoveryUrl(discoveryUrl: Uri) {
        AuthorizationServiceConfiguration.fetchFromUrl(
            discoveryUrl,
            handleConfigurationRetrievalResult,
            initialConfiguration.connectionBuilder
        )
    }

    private fun createNewAuthorizationService() =
        AuthorizationService(
            applicationContext,
            AppAuthConfiguration.Builder()
                .setConnectionBuilder(initialConfiguration.connectionBuilder)
                .build()
        )

    private var handleConfigurationRetrievalResult =
        AuthorizationServiceConfiguration.RetrieveConfigurationCallback { config, exception ->
            if (config == null) {
                handleErrorAndFinishActivity(exception)
            } else {
                _mAuthServiceConfiguration = config
                createAuthRequest()
            }
        }

    private fun createAuthRequest() {
        initialConfiguration.clientId?.let { clientId ->
            _mAuthRequest =
                AuthorizationRequest.Builder(
                    mAuthServiceConfiguration,
                    clientId,
                    ResponseTypeValues.CODE,
                    initialConfiguration.redirectUri
                ).setScope(initialConfiguration.scope).setLoginHint(Constants.ENTER_EMAIL).build()
            chooseOperationAndProcess()
        } ?: kotlin.run {
            handleErrorAndFinishActivity(Exception(Constants.CLIENT_ID_NULL))
        }
    }

    private fun chooseOperationAndProcess() =
        when (ClientInfo.getAuthSDK().getOperationState()) {
            EMAIL_LOGIN ->
                startEmailLogin()

            MOBILE_LOGIN ->
                loadAuthorizationEndpointInWebView(mAuthRequest.toUri().toString())

            RENEW_TOKEN ->
                startRenewAuthToken(ClientInfo.getAuthSDK().getRefreshToken())

            LOGOUT ->
                startLogout(ClientInfo.getAuthSDK().getIdToken())
        }


    private fun startEmailLogin() {
        val authIntent =
            mAuthService.createCustomTabsIntentBuilder(mAuthRequest.toUri()).build()
        val intent = mAuthService.getAuthorizationRequestIntent(mAuthRequest, authIntent)
        startActivityForResult(intent, EMAIL_LOGIN_REQUEST_CODE)
    }

    private fun loadAuthorizationEndpointInWebView(authUrl: String) {
        runOnUiThread {
            loadUrl(authUrl)
        }
    }

    inner class MyWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            isPageLoaded = false
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            isPageLoaded = true
            url?.let {
                if (checkIfUrlIsRedirectUrl(it)) {
                    if (_mLogoutRequest != null) {
                        handleLogoutRedirectUrl(it)
                    } else {
                        handleLoginRedirectUrl(it)
                    }
                } else {
                    when {
                        checkIfUrlIsAuthorizationUrl(it) -> {
                            inputUserCredentialsAndClickSignIn(
                                ClientInfo.getAuthSDK().getMobileNumber(),
                                ClientInfo.getAuthSDK().getOtp()
                            )
                        }
                        checkIfUrlIsAuthorizationFailUrl(it) -> {
                            handleErrorAndFinishActivity(
                                Exception(Constants.AUTHORIZATION_FAIL)
                            )
                        }
                        else -> {
                            handleErrorAndFinishActivity(
                                Exception(Constants.UNKNOWN_URL + url)
                            )
                        }
                    }
                }
            } ?: kotlin.run {
                handleErrorAndFinishActivity(
                    Exception(Constants.URL_NULL)
                )
            }
            super.onPageFinished(view, url)
        }
    }

    private fun checkIfUrlIsRedirectUrl(url: String) =
        url.contains(initialConfiguration.redirectUri.toString())

    private fun checkIfUrlIsAuthorizationUrl(url: String) =
        url.contains(mAuthRequest.toUri().toString())

    private fun checkIfUrlIsAuthorizationFailUrl(url: String) =
        url.contains(Constants.AUTHORIZATION_FAIL_URL)

    private fun inputUserCredentialsAndClickSignIn(userName: String, password: String) =
        loadUrl(
            "javascript: {" +
                    "document.getElementById('mobile').value = '" + userName + "';" +
                    "document.getElementById('code').value = '" + password + "';" +
                    "document.getElementsByClassName('pf-c-button')[0].click();" +
                    "};"
        )

    private fun handleLoginRedirectUrl(url: String) {
        val intent = extractResponseDataFromRedirectUrl(url)
        val response = AuthorizationResponse.fromIntent(intent)
        response?.let {
            performTokenRequest(
                response.createTokenExchangeRequest(),
                handleTokenResponseCallback
            )
        } ?: kotlin.run {
            handleErrorAndFinishActivity(
                Exception(Constants.REDIRECT_URL_FAIL)
            )
        }
    }

    private fun handleLogoutRedirectUrl(url: String) {
        val intent = EndSessionResponse.Builder(mLogoutRequest).setState(
            Uri.parse(url).getQueryParameter(
                Constants.STATE
            )
        ).build().toIntent()
        val response = EndSessionResponse.fromIntent(intent)

        response?.let {
            handleLogoutSuccess()
        } ?: kotlin.run {
            handleErrorAndFinishActivity(Exception(Constants.LOGOUT_RESPONSE_NULL))
        }
    }

    private fun extractResponseDataFromRedirectUrl(url: String): Intent {
        val redirectUrl = Uri.parse(url)
        if (redirectUrl.queryParameterNames.contains(AuthorizationException.PARAM_ERROR))
            return AuthorizationException.fromOAuthRedirect(redirectUrl).toIntent()
        else {
            val response = AuthorizationResponse.Builder(mAuthRequest)
                .fromUri(redirectUrl)
                .build()

            if (mAuthRequest.getState() == null && response.state != null
                || mAuthRequest.getState() != null && mAuthRequest.getState() != response.state
            )
                return AuthorizationRequestErrors.STATE_MISMATCH.toIntent()

            return response.toIntent()
        }
    }


    private fun startRenewAuthToken(refreshToken: String) {
        initialConfiguration.clientId?.let { clientId ->
            val tokenRequest = TokenRequest.Builder(
                mAuthRequest.configuration,
                clientId
            ).setGrantType(GrantTypeValues.REFRESH_TOKEN)
                .setScope(null)
                .setRefreshToken(refreshToken)
                .setAdditionalParameters(null)
                .build()

            performTokenRequest(tokenRequest, handleTokenResponseCallback)
        } ?: kotlin.run {
            handleErrorAndFinishActivity(Exception(Constants.CLIENT_ID_NULL))
        }
    }


    private fun performTokenRequest(
        request: TokenRequest,
        callback: TokenResponseCallback
    ) {
        val clientAuthentication =
            ClientSecretBasic(initialConfiguration.tokenEndpointUri.toString())

        mAuthService.performTokenRequest(
            request,
            clientAuthentication,
            callback
        )
    }

    private var handleTokenResponseCallback =
        TokenResponseCallback { response, exception ->
            response?.let {
                with(it) {
                    if (accessToken != null && refreshToken != null && idToken != null) {
                        val tokenInfo = TokenInfo(
                            it.accessToken!!,
                            it.refreshToken!!,
                            it.idToken!!
                        )
                        handleTokenSuccess(tokenInfo)
                    } else {
                        handleErrorAndFinishActivity(Exception(Constants.TOKEN_RESPONSE_NULL))
                    }
                }
            } ?: kotlin.run {
                handleErrorAndFinishActivity(exception)
            }
        }


    private fun startLogout(idToken: String) {
        _mLogoutRequest =
            EndSessionRequest.Builder(mAuthServiceConfiguration)
                .setIdTokenHint(idToken)
                .setPostLogoutRedirectUri(initialConfiguration.endSessionRedirectUri)
                .build()

        runOnUiThread {
            loadUrl(mLogoutRequest.toUri().toString())
        }
    }

    override fun onDestroy() {
        mAuthService.dispose()
        job.cancel(null)
        _webView = null
        _initialConfiguration = null
        _mLogoutRequest = null
        _mAuthRequest = null
        _mAuthServiceConfiguration = null
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == EMAIL_LOGIN_REQUEST_CODE && data != null) {
            val response = AuthorizationResponse.fromIntent(data)
            response?.let {
                performTokenRequest(
                    response.createTokenExchangeRequest(),
                    handleTokenResponseCallback
                )
            } ?: kotlin.run {
                handleErrorAndFinishActivity(Exception("email login response is null"))
            }
        } else {
            handleErrorAndFinishActivity(Exception("email login response is null"))
        }
    }

    private fun loadUrl(url: String) {
        webView.loadUrl(url)
        val run = Runnable { // Do nothing if we already have an error
            // Dismiss any current alerts and progress
            if (!isPageLoaded) {
                webView.destroy()
                handleErrorAndFinishActivity(Exception(Constants.TIME_OUT))
            }
        }
        timeoutHandler.postDelayed(run, TIMEOUT * 1000)
    }

    private fun handleTokenSuccess(tokenInfo: TokenInfo) {
        ClientInfo.getAuthSDK().getLoginCallback().onSuccess(tokenInfo)
        finish()
    }

    private fun handleLogoutSuccess() {
        ClientInfo.getAuthSDK().getLogoutCallback().onLogoutSuccess()
        finish()
    }

    private fun handleErrorAndFinishActivity(exception: Exception? = null) {
        when (ClientInfo.getAuthSDK().getOperationState()) {
            EMAIL_LOGIN, MOBILE_LOGIN, RENEW_TOKEN -> {
                ClientInfo.getAuthSDK().getLoginCallback()
                    .onFailure(exception)
            }
            LOGOUT ->
                ClientInfo.getAuthSDK().getLogoutCallback().onLogoutFailure(exception)
        }
        finish()
    }

    companion object {
        private const val EMAIL_LOGIN_REQUEST_CODE = 100
    }

}