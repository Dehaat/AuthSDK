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

open class LoginActivity : Activity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            initialConfiguration.discoveryUri?.let { uri ->
                fetchEndpointsFromDiscoveryUrl(uri)
            } ?: handleErrorAndFinishActivity(Exception(Constants.DISCOVERY_URL_NULL))
        } ?: finish()

    private fun initializeWebView(){
        _webView= getWebViewWithInitialSetup()
    }


    open fun getWebViewWithInitialSetup()  =
        WebView(this).apply {
            webViewClient = MyWebViewClient()
            enableWebViewSettings()
            keepScreenOn = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

    @SuppressLint("SetJavaScriptEnabled")
    protected fun WebView.enableWebViewSettings() {
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
        }
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
                .apply {
                    getAuthClientInfo()?.let {
                        if (it.getIsDebugMode()) setSkipIssuerHttpsCheck(true)
                    }
                }
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

    private fun triggerAuthUrlInWebView() {
        mAuthService = createNewAuthorizationService()
        loadUrlInWebView(mAuthRequest.toUri().toString())
    }

    private fun triggerLogoutUrlInWebView() =
        loadUrlInWebView(mLogoutRequest.toUri().toString())

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
                EMAIL_LOGIN,MOBILE_LOGIN -> {
                    createAuthRequest()
                    triggerAuthUrlInWebView()
                }
                LOGOUT -> {
                    createLogoutRequest()
                    triggerLogoutUrlInWebView()
                }
                else -> handleErrorAndFinishActivity(java.lang.Exception(""))
            }
        } ?: finish()

    private fun disposeCurrentServiceIfExist() {
        if (::mAuthService.isInitialized) {
            mAuthService.dispose()
        }
    }

    inner class MyWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            isPageLoaded = false
            onPageStartedInWebView()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            isPageLoaded = true
            onPageFinishedInWebView(url)
            super.onPageFinished(view, url)
        }
    }

    open fun onPageStartedInWebView(){}

    open fun onPageFinishedInWebView(url:String?){
        url?.let {
            if (checkIfUrlIsRedirectUrl(it))
                handleRedirection(it)
            else {
                when {
                    checkIfUrlIsAuthorizationUrl(it) -> injectCredentials()

                    checkIfUrlIsLogoutUrl(it) -> loadUrlInWebView(it)

                    else -> handleWrongUrl(it)
                }
            }
        } ?: handleErrorAndFinishActivity(Exception(Constants.URL_NULL))
    }


    private fun handleWrongUrl(url:String){
        when {
            checkIfUrlIsForgotPassword(url) || checkIfUrlIsAuthorizationFailUrl(url) -> {
                getAuthClientInfo()?.let {
                    if(it.getOperationState() != EMAIL_LOGIN) handleAuthUrlFailure()
                }?:handleErrorAndFinishActivity(Exception(Constants.UNKNOWN_URL + url))
            }
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
        when {
            _mLogoutRequest != null -> {
                handleLogoutRedirectUrl(url)
            }
            _mAuthRequest != null -> {
                handleLoginRedirectUrl(url)
            }
            else -> handleErrorAndFinishActivity(java.lang.Exception(""))
        }

    protected fun checkIfUrlIsRedirectUrl(url: String) =
        url.contains(getRedirectUri())

    private fun checkIfUrlIsAuthorizationUrl(url: String) =
        _mAuthRequest != null && url.contains(mAuthRequest.toUri().toString())

    private fun checkIfUrlIsAuthorizationFailUrl(url: String) =
        url.contains(Constants.AUTHORIZATION_FAIL_URL)

    private fun checkIfUrlIsForgotPassword(url: String) =
        url.contains(Constants.RESET_CREDENTIALS)

    private fun checkIfUrlIsLogoutUrl(url: String) =
        _mLogoutRequest != null && url.contains(mLogoutRequest.toUri().toString())

    private fun inputUserCredentialsAndClickSignIn(userName: String, password: String) =
        loadUrlInWebView(
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
                        if (accessToken != null && refreshToken != null) {
                            val tokenInfo = TokenInfo(
                                it.accessToken!!,
                                it.refreshToken!!,
                            )
                            handleTokenSuccess(tokenInfo)
                        } else {
                            handleErrorAndFinishActivity(Exception(Constants.TOKEN_RESPONSE_NULL))
                        }
                    }
                } ?: handleErrorAndFinishActivity(exception)
            }
        }

    private fun createLogoutRequest() {
        _mLogoutRequest =
            EndSessionRequest.Builder(mAuthServiceConfiguration)
                .setPostLogoutRedirectUri(Uri.parse(getRedirectUri()))
                .build()
    }
    private fun loadUrlInWebView(url: String) {
        webView.loadUrl(url)
        timeoutHandler.postDelayed(getTimeoutRunnable(), 30L * 1000)
    }

    //if url load is not finished in web view in 30 seconds then destroy web view and finish the process
    private fun getTimeoutRunnable() =
        Runnable {
            if (!isPageLoaded) {
                if (_webView != null) webView.destroy()
                handleErrorAndFinishActivity(Exception(Constants.TIME_OUT))
            }
        }

    private fun handleTokenSuccess(tokenInfo: TokenInfo) {
        getAuthClientInfo()?.getLoginCallback()?.onSuccess(tokenInfo)
        finish()
    }

    private fun handleLogoutSuccess() {
        getAuthClientInfo()?.getLogoutCallback()?.onLogoutSuccess()
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
        _webView?.destroy()
        _webView = null
        _initialConfiguration = null
        _mLogoutRequest = null
        _mAuthRequest = null
        _mAuthServiceConfiguration = null
        ClientInfo.setAuthClientInfo(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("ACTIVITY STATE","onNewIntent was called")
    }

}