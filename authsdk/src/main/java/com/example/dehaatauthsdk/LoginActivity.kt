package com.example.dehaatauthsdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import net.openid.appauth.*
import net.openid.appauth.AuthorizationException.AuthorizationRequestErrors
import net.openid.appauth.AuthorizationService.TokenResponseCallback

class LoginActivity : Activity() {

    private lateinit var initialConfiguration: Configuration
    private lateinit var mAuthService: AuthorizationService
    private lateinit var mAuthServiceConfiguration: AuthorizationServiceConfiguration
    private lateinit var mAuthRequest: AuthorizationRequest
    private lateinit var mLogoutRequest: EndSessionRequest
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize()
    }

    private fun initialize() {
        webView = WebView(this).apply {
            webViewClient = MyWebViewClient()
            enableWebViewSettings()
        }
        initialConfiguration = Configuration.getInstance(applicationContext)
        CoroutineScope((IO)).launch {
            startAuthorizationServiceCreation()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.enableWebViewSettings() {
        settings.apply {
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }

    private fun startAuthorizationServiceCreation() {
        disposeCurrentServiceIfExist()
        mAuthService = createNewAuthorizationService()

        initialConfiguration.discoveryUri?.let {
            fetchEndpointsFromDiscoveryUrl(it)
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
                Toast.makeText(this, exception.toString(), Toast.LENGTH_SHORT).show()
                return@RetrieveConfigurationCallback
            }
            mAuthServiceConfiguration = config
            createAuthRequest()
        }

    private fun createAuthRequest() {
        mAuthRequest =
            AuthorizationRequest.Builder(
                mAuthServiceConfiguration,
                initialConfiguration.clientId!!,
                ResponseTypeValues.CODE,
                initialConfiguration.redirectUri
            ).setScope(initialConfiguration.scope).setLoginHint("Please enter email").build()

        chooseOperationAndProcess()
    }

    private fun chooseOperationAndProcess() =
        when (ClientInfo.getAuthSDK().getOperationState()) {

            DeHaatAuth.OperationState.EMAIL_LOGIN ->
                startEmailLogin()

            DeHaatAuth.OperationState.MOBILE_LOGIN ->
                loadAuthorizationEndpointInWebView(mAuthRequest.toUri().toString())

            DeHaatAuth.OperationState.RENEW_TOKEN ->
                startRenewAuthToken(ClientInfo.getAuthSDK().getRefreshToken())

            DeHaatAuth.OperationState.LOGOUT ->
                startLogout(ClientInfo.getAuthSDK().getIdToken())
        }


    private fun startEmailLogin() {
        val authIntent =
            mAuthService.createCustomTabsIntentBuilder(mAuthRequest.toUri()).build()
        val intent = mAuthService.getAuthorizationRequestIntent(mAuthRequest, authIntent)
        startActivityForResult(intent, EMAIL_LOGIN_REQUEST_CODE)
    }

    private fun loadAuthorizationEndpointInWebView(authUrl: String) {
        CoroutineScope(Main).launch {
            webView.loadUrl(authUrl)
        }
    }

    inner class MyWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            synchronized(this) {
                url?.let {
                    if (checkIfUrlIsRedirectUrl(it)) {
                        if (::mLogoutRequest.isInitialized)
                            handleLogoutRedirectUrl(it)
                        else
                            handleLoginRedirectUrl(it)
                    } else {
                        when {
                            checkIfUrlIsAuthorizationUrl(it) -> {
                                inputUserCredentialsAndClickSignIn(
                                    ClientInfo.getAuthSDK().getMobileNumber(),
                                    ClientInfo.getAuthSDK().getOtp()
                                )
                            }
                            checkIfUrlIsAuthorizationFailUrl(it) -> {
                                ClientInfo.getAuthSDK().getLoginCallback()
                                    .onFailure(KotlinNullPointerException("Invalid Credentials"))
                            }
                            else -> {
                                finish()
                            }
                        }
                    }
                }
                super.onPageStarted(view, url, favicon)
            }

        }
    }

    private fun checkIfUrlIsRedirectUrl(url: String) =
        url.contains(initialConfiguration.redirectUri.toString())

    private fun checkIfUrlIsAuthorizationUrl(url: String) =
        url.contains(mAuthRequest.toUri().toString())

    private fun checkIfUrlIsAuthorizationFailUrl(url: String) =
        url.contains("/login-actions/authenticate")

    private fun inputUserCredentialsAndClickSignIn(userName: String, password: String) =
        webView.loadUrl(
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
        }
    }


    private fun handleLogoutRedirectUrl(url: String) {
        val intent = EndSessionResponse.Builder(mLogoutRequest).setState(
            Uri.parse(url).getQueryParameter(
                "state"
            )
        ).build().toIntent()
        val response = EndSessionResponse.fromIntent(intent)

        response?.let {
            ClientInfo.getAuthSDK().getLogoutCallback().onLogoutSuccess()
        } ?: kotlin.run {
            ClientInfo.getAuthSDK().getLogoutCallback().onLogoutFailure()
        }

        finish()
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
        val tokenRequest = TokenRequest.Builder(
            mAuthRequest.configuration,
            initialConfiguration.clientId!!
        )
            .setGrantType(GrantTypeValues.REFRESH_TOKEN)
            .setScope(null)
            .setRefreshToken(refreshToken)
            .setAdditionalParameters(null)
            .build()

        performTokenRequest(tokenRequest, handleTokenResponseCallback)
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
                        ClientInfo.getAuthSDK().getLoginCallback().onSuccess(tokenInfo)
                    } else
                        ClientInfo.getAuthSDK().getLoginCallback()
                            .onFailure(KotlinNullPointerException("access token is null"))
                }
            } ?: kotlin.run {
                ClientInfo.getAuthSDK().getLoginCallback().onFailure(exception)
            }
            finish()
        }


    private fun startLogout(idToken: String) {
        mLogoutRequest =
            EndSessionRequest.Builder(mAuthServiceConfiguration)
                .setIdTokenHint(idToken)
                .setPostLogoutRedirectUri(initialConfiguration.endSessionRedirectUri)
                .build()
        CoroutineScope(Main).launch {
            webView = WebView(this@LoginActivity).apply {
                webViewClient = MyWebViewClient()
                enableWebViewSettings()
            }
            webView.loadUrl(mLogoutRequest.toUri().toString())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mAuthService.dispose()
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
            }
        } else
            finish()
    }

    companion object {
        private const val EMAIL_LOGIN_REQUEST_CODE = 100
    }

}