package com.example.dehaatauthsdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.dehaatauthsdk.ClientInfo.getAuthClientInfo
import com.example.dehaatauthsdk.DeHaatAuth.OperationState.*
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

    private var isPageLoaded = false
    private lateinit var timeoutHandler: Handler
    private val TIMEOUT = 30L
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ACTIVITY STATE","onCreate was called")
        initialize()
    }

    private fun initialize() =
        getAuthClientInfo()?.let {
            initializeWebView()
            timeoutHandler = Handler(Looper.getMainLooper())
            _initialConfiguration = Configuration.getInstance(
                applicationContext,
                it.getClientId(), it.getIsDebugMode()
            )
            initialConfiguration.discoveryUri?.let {
                fetchEndpointsFromDiscoveryUrl(it)
            } ?: handleErrorAndFinishActivity(Exception(Constants.DISCOVERY_URL_NULL))
        } ?: finish()

    private fun initializeWebView(){
        _webView= getWebViewWithInitialSetup()
    }


    private fun getWebViewWithInitialSetup()  =
        WebView(this).apply {
            webViewClient = MyWebViewClient()
            enableWebViewSettings()
            keepScreenOn = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            loadWithOverviewMode = true
            pluginState = WebSettings.PluginState.ON
            setAppCacheEnabled(true)
        }
    }

    private  fun startAuthorizationServiceCreation() {

    }

    private  fun fetchEndpointsFromDiscoveryUrl(discoveryUrl: Uri) {
        AuthorizationServiceConfiguration.fetchFromUrl(
            discoveryUrl,
            handleConfigurationRetrievalResult,
            initialConfiguration.connectionBuilder
        )
    }

    private fun createNewAuthorizationService(): AuthorizationService {
        disposeCurrentServiceIfExist()
        return AuthorizationService(
            applicationContext,
            AppAuthConfiguration.Builder()
                .setConnectionBuilder(initialConfiguration.connectionBuilder)
                .build()
        )
    }

    private var handleConfigurationRetrievalResult =
        AuthorizationServiceConfiguration.RetrieveConfigurationCallback { config, exception ->
            if (config == null || isFinishing) {
                handleErrorAndFinishActivity(exception)
            } else {
                _mAuthServiceConfiguration = config
                chooseOperationAndProcess()
            }
        }

    private fun triggerAuthUrlInCustomTab(){
        mAuthService = createNewAuthorizationService()
        val authIntent = mAuthService.createCustomTabsIntentBuilder(mAuthRequest.toUri()).build()
        val authRequestIntent = mAuthService.getAuthorizationRequestIntent(mAuthRequest, authIntent)
        startActivityForResult(authRequestIntent, EMAIL_LOGIN_REQUEST_CODE)
    }

    private fun triggerAuthUrlInWebview() {
        mAuthService = createNewAuthorizationService()
        loadUrlInWebview(mAuthRequest.toUri().toString())
    }

    private fun triggerLogoutUrlInWebview() =
        loadUrlInWebview(mLogoutRequest.toUri().toString())

    private fun createAuthRequest() {
        initialConfiguration.clientId?.let { clientId ->
            _mAuthRequest =
                AuthorizationRequest.Builder(
                    mAuthServiceConfiguration,
                    clientId,
                    ResponseTypeValues.CODE,
                    Uri.parse(getRedirectUri())
                ).setScope(initialConfiguration.scope).build()

        } ?: handleErrorAndFinishActivity(Exception(Constants.CLIENT_ID_NULL))

    }

    private fun getRedirectUri() =
        getAuthClientInfo()?.let {
            when (it.getClientId()) {
                Constants.FARMER_CLIENT_ID -> Constants.FARMER_REDIRECT_URI
                Constants.DBA_CLIENT_ID -> Constants.DBA_REDIRECT_URI
                Constants.AIMS_CLIENT_ID -> Constants.AIMS_REDIRECT_URI
                else -> Constants.FARMER_REDIRECT_URI
            }
        } ?: ""

    private fun chooseOperationAndProcess() =
        getAuthClientInfo()?.let {
            when (it.getOperationState()) {
                EMAIL_LOGIN  -> {
                    createAuthRequest()
                    triggerAuthUrlInCustomTab()
                }
                MOBILE_LOGIN -> {
                    createAuthRequest()
                    triggerAuthUrlInWebview()
                }
                LOGOUT -> {
                    createLogoutRequest(it.getIdToken())
                    triggerLogoutUrlInWebview()
                }
                else -> handleErrorAndFinishActivity(java.lang.Exception(""))
            }
        } ?: finish()

    private fun startEmailLogin() {
        val authIntent =
            mAuthService.createCustomTabsIntentBuilder(mAuthRequest.toUri()).build()
        val intent = mAuthService.getAuthorizationRequestIntent(mAuthRequest, authIntent)
        startActivityForResult(intent, EMAIL_LOGIN_REQUEST_CODE)
    }

    private fun disposeCurrentServiceIfExist() =
        if (!::mAuthService.isInitialized) {
        } else {
            mAuthService.dispose()
        }

    inner class MyWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            isPageLoaded = false
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            isPageLoaded = true
            url?.let {
                if (checkIfUrlIsRedirectUrl(it))
                    handleRedirection(it)
                 else {
                    when {
                        checkIfUrlIsAuthorizationUrl(it) -> injectCredentials()

                        checkIfUrlIsLogoutUrl(it) -> loadUrlInWebview(it)

                        else -> handleWrongUrl(it)
                    }
                }
            } ?: handleErrorAndFinishActivity(Exception(Constants.URL_NULL))
            super.onPageFinished(view, url)
        }
    }


    private fun handleWrongUrl(url:String){
        when {
            checkIfUrlIsAuthorizationFailUrl(url) -> handleAuthUrlFailure()
            else->
                handleErrorAndFinishActivity(Exception(Constants.UNKNOWN_URL + url))
        }
    }

    private fun injectCredentials() =
        getAuthClientInfo()?.let {
            inputUserCredentialsAndClickSignIn(
                it.getMobileNumber(),
                it.getOtp()
            )
        } ?: finish()

    private fun handleAuthUrlFailure() =
        handleErrorAndFinishActivity(Exception(Constants.AUTHORIZATION_FAIL))

    private fun handleRedirection(url: String) =
        if (_mLogoutRequest != null) {
            handleLogoutRedirectUrl(url)
        } else if (_mAuthRequest != null) {
            handleLoginRedirectUrl(url)
        } else
            handleErrorAndFinishActivity(java.lang.Exception(""))

    private fun checkIfUrlIsRedirectUrl(url: String) =
        url.contains(getRedirectUri())

    private fun checkIfUrlIsAuthorizationUrl(url: String) =
        _mAuthRequest != null && url.contains(mAuthRequest.toUri().toString())

    private fun checkIfUrlIsAuthorizationFailUrl(url: String) =
        url.contains(Constants.AUTHORIZATION_FAIL_URL)

    private fun checkIfUrlIsLogoutUrl(url: String) =
        _mLogoutRequest != null && url.contains(mLogoutRequest.toUri().toString())

    private fun inputUserCredentialsAndClickSignIn(userName: String, password: String) =
        loadUrlInWebview(
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
        } ?: handleErrorAndFinishActivity(Exception(Constants.REDIRECT_URL_FAIL))
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
        } ?: handleErrorAndFinishActivity(Exception(Constants.LOGOUT_RESPONSE_NULL))
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

    private fun performTokenRequest(
        request: TokenRequest,
        callback: TokenResponseCallback
    ) {
        val clientAuthentication =
            ClientSecretBasic(initialConfiguration.tokenEndpointUri.toString())

        if (::mAuthService.isInitialized)
            mAuthService.performTokenRequest(request, clientAuthentication, callback)
    }


    private var handleTokenResponseCallback =
        TokenResponseCallback { response, exception ->
            if(isFinishing)
                handleErrorAndFinishActivity(java.lang.Exception(""))
            else {
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
                } ?: handleErrorAndFinishActivity(exception)
            }
        }

    private fun createLogoutRequest(idToken: String) {
        _mLogoutRequest =
            EndSessionRequest.Builder(mAuthServiceConfiguration)
                .setIdTokenHint(idToken)
                .setPostLogoutRedirectUri(Uri.parse(getRedirectUri()))
                .build()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == EMAIL_LOGIN_REQUEST_CODE && data != null)
            handleEmailLoginUrlResponseIntent(data)
        else
            handleErrorAndFinishActivity()
    }

    private fun handleEmailLoginUrlResponseIntent(intent: Intent?) =
        intent?.let {
            AuthorizationResponse.fromIntent(it)?.let { response ->
                performTokenRequest(
                    response.createTokenExchangeRequest(),
                    handleTokenResponseCallback
                )
            } ?: handleErrorAndFinishActivity(Exception(Constants.EMAIL_LOGIN_RESPONSE_NULL))
        } ?: handleErrorAndFinishActivity(Exception(Constants.EMAIL_LOGIN_RESPONSE_NULL))

    private fun loadUrlInWebview(url: String) {
        webView.loadUrl(url)
        timeoutHandler.postDelayed(getTimeoutRunnable(), TIMEOUT * 1000)
    }

    //if url load is not finished in webview in 30 seconds then destroy webview and finish the process
    private fun getTimeoutRunnable() =
        Runnable {
            if (!isPageLoaded) {
                if (_webView != null) webView.destroy()
                handleErrorAndFinishActivity(Exception(Constants.TIME_OUT))
            }
        }

    private fun handleTokenSuccess(tokenInfo: TokenInfo) {
        getAuthClientInfo()?.let { it.getLoginCallback().onSuccess(tokenInfo) }
        finish()
    }

    private fun handleLogoutSuccess() {
        getAuthClientInfo()?.let { it.getLogoutCallback().onLogoutSuccess() }
        finish()
    }

    private fun handleErrorAndFinishActivity(exception: Exception? = null) {
        getAuthClientInfo()?.let {
            when (it.getOperationState()) {
                EMAIL_LOGIN, MOBILE_LOGIN, RENEW_TOKEN -> {
                    it.getLoginCallback()
                        .onFailure(exception)
                }
                LOGOUT ->
                    it.getLogoutCallback().onLogoutFailure(exception)
            }
        }
        finish()
    }

    override fun onDestroy() {
         disposeCurrentServiceIfExist()
        _webView = null
        _initialConfiguration = null
        _mLogoutRequest = null
        _mAuthRequest = null
        _mAuthServiceConfiguration = null
        ClientInfo.setAuthClientInfo(null)
        super.onDestroy()
    }

    companion object {
        private const val EMAIL_LOGIN_REQUEST_CODE = 100
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("ACTIVITY STATE","onNewIntent was called")
    }

}