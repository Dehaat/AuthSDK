package com.example.dehaatauthsdk

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.openid.appauth.*

class RenewTokenHandler constructor(private val context: Context) {

    private lateinit var mAuthService: AuthorizationService

    private var _initialConfiguration: Configuration? = null
    private val initialConfiguration get() = _initialConfiguration!!

    private var _mAuthServiceConfiguration: AuthorizationServiceConfiguration? = null
    private val mAuthServiceConfiguration get() = _mAuthServiceConfiguration!!
    private var _mAuthRequest: AuthorizationRequest? = null
    private val mAuthRequest get() = _mAuthRequest!!

    private lateinit var job: Job

    private lateinit var refreshToken: String

    init {
        _initialConfiguration = Configuration.getInstance(context)
    }

    fun startRenewProcess(refreshToken: String){
        this.refreshToken = refreshToken
        job = CoroutineScope((Dispatchers.IO)).launch {
            startAuthorizationServiceCreation()
        }
    }


    private fun startAuthorizationServiceCreation() {
        disposeCurrentServiceIfExist()
        mAuthService = createNewAuthorizationService()
        initialConfiguration.discoveryUri?.let {
            fetchEndpointsFromDiscoveryUrl(it)
        }?: kotlin.run {
            handleErrorAndFinishActivity(KotlinNullPointerException("Discovery Url is null"))
        }
    }

    private fun disposeCurrentServiceIfExist() {
        if (::mAuthService.isInitialized) {
            mAuthService.dispose()
        }
    }

    private fun createNewAuthorizationService() =
        AuthorizationService(
            context,
            AppAuthConfiguration.Builder()
                .setConnectionBuilder(initialConfiguration.connectionBuilder)
                .build()
        )


    private fun fetchEndpointsFromDiscoveryUrl(discoveryUrl: Uri) {
        AuthorizationServiceConfiguration.fetchFromUrl(
            discoveryUrl,
            handleConfigurationRetrievalResult,
            initialConfiguration.connectionBuilder
        )
    }

    private fun handleErrorAndFinishActivity(exception: Exception?) {
        when (ClientInfo.getAuthSDK().getOperationState()) {
            DeHaatAuth.OperationState.EMAIL_LOGIN, DeHaatAuth.OperationState.MOBILE_LOGIN, DeHaatAuth.OperationState.RENEW_TOKEN -> {
                ClientInfo.getAuthSDK().getLoginCallback()
                    .onFailure(exception)
            }
            DeHaatAuth.OperationState.LOGOUT ->
                ClientInfo.getAuthSDK().getLogoutCallback().onLogoutFailure(exception)
        }
    }

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
                ).setScope(initialConfiguration.scope).setLoginHint("Please enter email").build()
            startRenewAuthToken(ClientInfo.getAuthSDK().getRefreshToken())
        } ?: kotlin.run {
            handleErrorAndFinishActivity(KotlinNullPointerException("Client id is null"))
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
            handleErrorAndFinishActivity(KotlinNullPointerException("Client id is null"))
        }
    }


    private fun performTokenRequest(
        request: TokenRequest,
        callback: AuthorizationService.TokenResponseCallback
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
        AuthorizationService.TokenResponseCallback { response, exception ->
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
                        handleErrorAndFinishActivity(KotlinNullPointerException("access token is null"))
                    }
                }
            } ?: kotlin.run {
                handleErrorAndFinishActivity(exception)
            }
        }

    private fun handleTokenSuccess(tokenInfo: TokenInfo) {
        ClientInfo.getAuthSDK().getLoginCallback().onSuccess(tokenInfo)

    }

}