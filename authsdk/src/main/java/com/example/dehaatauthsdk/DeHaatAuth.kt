package com.example.dehaatauthsdk

import android.content.Context
import android.content.Intent
import com.auth0.android.jwt.JWT
import com.example.dehaatauthsdk.ClientInfo.getAuthClientInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

class DeHaatAuth {

    enum class OperationState {
        MOBILE_LOGIN, EMAIL_LOGIN, RENEW_TOKEN, LOGOUT
    }

    private lateinit var loginResponseCallback: LoginResponseCallback
    private lateinit var logoutResponseCallback: LogoutCallback
    private lateinit var refreshToken: String
    private var mobileNumber = "9897646336"
    private var otp = "1234"
    private var clientId: String
    private var isDebugMode: Boolean = false
    private var operationState: OperationState
    private var keycloakDomain: String = ""

    private constructor(builder: MobileLoginBuilder) {
        operationState = OperationState.MOBILE_LOGIN
        mobileNumber = builder.mobileNumber
        otp = builder.otp
        clientId = builder.clientId
        isDebugMode = builder.isDebugMode
        loginResponseCallback = builder.loginResponseCallback
        keycloakDomain = builder.keycloakDomain
    }

    private constructor(builder: EmailLoginBuilder) {
        operationState = OperationState.EMAIL_LOGIN
        clientId = builder.clientId
        isDebugMode = builder.isDebugMode
        loginResponseCallback = builder.loginResponseCallback
        keycloakDomain = builder.keycloakDomain
    }

    private constructor(builder: RenewTokenBuilder) {
        operationState = OperationState.RENEW_TOKEN
        clientId = builder.clientId
        isDebugMode = builder.isDebugMode
        refreshToken = builder.refreshToken
        loginResponseCallback = builder.loginResponseCallback
        keycloakDomain = builder.keycloakDomain
    }

    private constructor(builder: LogoutBuilder) {
        operationState = OperationState.LOGOUT
        clientId = builder.clientId
        isDebugMode = builder.isDebugMode
        logoutResponseCallback = builder.logoutCallback
        keycloakDomain = builder.keycloakDomain
    }

    fun getMobileNumber() = mobileNumber

    fun getOtp() = otp

    fun getLoginCallback() = loginResponseCallback

    fun getLogoutCallback() = logoutResponseCallback

    fun getOperationState() = operationState

    fun getRefreshToken() = refreshToken

    fun getClientId() = clientId

    fun getIsDebugMode() = isDebugMode

    fun getClientKeycloakDomain() = keycloakDomain

    companion object {

        class MobileLoginBuilder {
            lateinit var loginResponseCallback: LoginResponseCallback
            var mobileNumber = "9897646336"
            var otp = "1234"
            lateinit var clientId: String
            var isDebugMode: Boolean = false
            var keycloakDomain: String = ""

            fun mobile(number: String): MobileLoginBuilder {
                this.mobileNumber = number
                return this
            }

            fun otp(otp: String): MobileLoginBuilder {
                this.otp = otp
                return this
            }

            fun clientId(clientId: String): MobileLoginBuilder {
                this.clientId = clientId
                return this
            }

            fun enableDebugMode(keycloakDomain: String): MobileLoginBuilder {
                isDebugMode = true
                this.keycloakDomain = keycloakDomain
                return this
            }

            fun responseCallback(loginResponseCallback: LoginResponseCallback): MobileLoginBuilder {
                this.loginResponseCallback = loginResponseCallback
                return this
            }

            fun build() = DeHaatAuth(this)
        }

        class EmailLoginBuilder {
            lateinit var loginResponseCallback: LoginResponseCallback
            lateinit var clientId: String
            var isDebugMode: Boolean = false
            var keycloakDomain: String = ""

            fun clientId(clientId: String): EmailLoginBuilder {
                this.clientId = clientId
                return this
            }

            fun enableDebugMode(keycloakDomain: String): EmailLoginBuilder {
                isDebugMode = true
                this.keycloakDomain = keycloakDomain
                return this
            }

            fun responseCallback(loginResponseCallback: LoginResponseCallback): EmailLoginBuilder {
                this.loginResponseCallback = loginResponseCallback
                return this
            }

            fun build() = DeHaatAuth(this)
        }

        class RenewTokenBuilder {
            lateinit var loginResponseCallback: LoginResponseCallback
            lateinit var refreshToken: String
            lateinit var clientId: String
            var isDebugMode: Boolean = false
            var keycloakDomain: String = ""

            fun refreshToken(refreshToken: String): RenewTokenBuilder {
                this.refreshToken = refreshToken
                return this
            }

            fun clientId(clientId: String): RenewTokenBuilder {
                this.clientId = clientId
                return this
            }

            fun enableDebugMode(keycloakDomain: String): RenewTokenBuilder {
                isDebugMode = true
                this.keycloakDomain = keycloakDomain
                return this
            }

            fun responseCallback(loginResponseCallback: LoginResponseCallback): RenewTokenBuilder {
                this.loginResponseCallback = loginResponseCallback
                return this
            }

            fun build() = DeHaatAuth(this)
        }

        class LogoutBuilder {
            lateinit var logoutCallback: LogoutCallback
            lateinit var clientId: String
            var isDebugMode: Boolean = false
            var keycloakDomain: String = ""

            fun clientId(clientId: String): LogoutBuilder {
                this.clientId = clientId
                return this
            }

            fun enableDebugMode(keycloakDomain: String): LogoutBuilder {
                isDebugMode = true
                this.keycloakDomain = keycloakDomain
                return this
            }

            fun responseCallback(logoutCallback: LogoutCallback): LogoutBuilder {
                this.logoutCallback = logoutCallback
                return this
            }

            fun build() = DeHaatAuth(this)
        }

        fun isSessionValid(accessToken: String?, refreshToken: String?, clientId: String) =
            isAccessTokenValid(accessToken, clientId) && isRefreshTokenValid(refreshToken)

        fun isAccessTokenValid(accessToken: String?, clientId: String) =
            accessToken != null && JWT(accessToken).claims["azp"]?.asString().equals(clientId)

        fun isRefreshTokenValid(refreshToken: String?) =
            refreshToken != null &&
                    (JWT(refreshToken).claims["typ"]?.asString().equals("Offline") ||
                            Date(Calendar.getInstance().timeInMillis).before(JWT(refreshToken).expiresAt))

        // function to get auth_id from access token
        fun getAuthId(accessToken: String, clientId: String): String? {
            return if (isAccessTokenValid(accessToken, clientId))
                JWT(accessToken).claims["sub"]?.asString()
            else
                null
        }

        suspend fun getNetworkTime(timeZone: String = "Asia/Kolkata") =
            suspendCancellableCoroutine<Date?> { continuation ->
                SNTPClient.getDate(TimeZone.getTimeZone(timeZone), object : SNTPClient.Listener {
                    override fun onTimeResponse(
                        rawDate: String?,
                        date: Date?,
                        ex: java.lang.Exception?
                    ) {
                        if (continuation.isActive) continuation.resume(date)
                    }
                })
            }
    }

    fun initialize(context: Context) =
        if (getAuthClientInfo() == null) {
            ClientInfo.setAuthClientInfo(this)
            getAuthClientInfo()?.let {
                when (operationState) {
                    OperationState.RENEW_TOKEN ->
                        RenewTokenHandler(context, it.clientId, it.isDebugMode).startRenewProcess()

                    OperationState.EMAIL_LOGIN ->
                        context.startActivity(Intent(context, WebViewLoginActivity::class.java))

                    else ->
                        context.startActivity(Intent(context, LoginActivity::class.java))
                }
                true
            } ?: false
        } else false

}