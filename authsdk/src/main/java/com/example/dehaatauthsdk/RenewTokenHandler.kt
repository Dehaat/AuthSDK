package com.example.dehaatauthsdk

import android.content.Context
import android.net.Uri
import com.example.dehaatauthsdk.ClientInfo.getAuthClientInfo
import net.openid.appauth.*

class RenewTokenHandler constructor(
    private val context: Context,
    _clientId: String, isDebugMode: Boolean
) {

    private lateinit var mAuthService: AuthorizationService

    private var _initialConfiguration: Configuration? = null
    private val initialConfiguration get() = _initialConfiguration!!

    private var _mAuthServiceConfiguration: AuthorizationServiceConfiguration? = null
    private val mAuthServiceConfiguration get() = _mAuthServiceConfiguration!!

    init {
        _initialConfiguration = Configuration.getInstance(context, _clientId, isDebugMode)
    }

    fun startRenewProcess() =
        initialConfiguration.discoveryUri?.let {
            fetchEndpointsFromDiscoveryUrl(it)
        } ?: handleRenewTokenFailure(KotlinNullPointerException("Discovery Url is null"))

    private fun disposeCurrentServiceIfExist() {
        if (::mAuthService.isInitialized) {
            mAuthService.dispose()
        }
    }

    private fun getNewAuthorizationService() =
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

    private var handleConfigurationRetrievalResult =
        AuthorizationServiceConfiguration.RetrieveConfigurationCallback { config, exception ->
            if (config == null) {
                handleRenewTokenFailure(exception)
            } else {
                _mAuthServiceConfiguration = config
                startRenewAuthToken()
            }
        }

    private fun startRenewAuthToken() =
        getAuthClientInfo()?.let {
            initialConfiguration.clientId?.let { clientId ->
                val tokenRequest = TokenRequest.Builder(mAuthServiceConfiguration, clientId)
                    .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                    .setScope(null)
                    .setRefreshToken(it.getRefreshToken())
                    .setAdditionalParameters(null)
                    .build()

                performTokenRequest(tokenRequest, handleTokenResponseCallback)
            } ?: handleRenewTokenFailure(KotlinNullPointerException("Client id is null"))
        }

    private fun performTokenRequest(
        request: TokenRequest,
        callback: AuthorizationService.TokenResponseCallback
    ) {
        disposeCurrentServiceIfExist()
        mAuthService = getNewAuthorizationService()
        val clientAuthentication =
            ClientSecretBasic(initialConfiguration.tokenEndpointUri.toString())

        if (::mAuthService.isInitialized)
            mAuthService.performTokenRequest(request, clientAuthentication, callback)
    }

    private var handleTokenResponseCallback =
        AuthorizationService.TokenResponseCallback { response, exception ->
            response?.let {
                with(it) {
                    if (accessToken != null && refreshToken != null) {
                        val tokenInfo = TokenInfo(
                            it.accessToken!!,
                            it.refreshToken!!
                        )
                        handleTokenSuccess(tokenInfo)
                    } else {
                        handleRenewTokenFailure(KotlinNullPointerException("access token is null"))
                    }
                }
            } ?: handleRenewTokenFailure(exception)

        }

    private fun handleTokenSuccess(tokenInfo: TokenInfo) =
        getAuthClientInfo()?.let {
            val loginCallback = it.getLoginCallback()
            ClientInfo.setAuthClientInfo(null)
            loginCallback.onSuccess(tokenInfo)
        }

    private fun handleRenewTokenFailure(exception: Exception?) =
        getAuthClientInfo()?.let {
            val loginCallback = it.getLoginCallback()
            ClientInfo.setAuthClientInfo(null)
            loginCallback.onFailure(exception)
        }
}