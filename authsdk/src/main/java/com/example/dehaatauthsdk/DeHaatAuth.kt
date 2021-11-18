package com.example.dehaatauthsdk

import android.content.Context
import android.content.Intent
import org.keycloak.TokenVerifier
import org.keycloak.representations.AccessToken

class DeHaatAuth {

    enum class OperationState {
        MOBILE_LOGIN, EMAIL_LOGIN, RENEW_TOKEN, LOGOUT
    }

    private lateinit var loginResponseCallback: LoginResponseCallback
    private lateinit var logoutResponseCallback: LogoutCallback
    private lateinit var refreshToken: String
    private lateinit var idToken: String
    private var mobileNumber = "9897646336"
    private var otp = "1234"
    private var clientId: String
    private var operationState: OperationState

    private constructor(builder: MobileLoginBuilder) {
        operationState = OperationState.MOBILE_LOGIN
        mobileNumber = builder.mobileNumber
        otp = builder.otp
        clientId = builder.clientId
        loginResponseCallback = builder.loginResponseCallback
    }

    private constructor(builder: EmailLoginBuilder) {
        operationState = OperationState.EMAIL_LOGIN
        clientId = builder.clientId
        loginResponseCallback = builder.loginResponseCallback
    }

    private constructor(builder: RenewTokenBuilder) {
        operationState = OperationState.RENEW_TOKEN
        clientId = builder.clientId
        refreshToken = builder.refreshToken
        loginResponseCallback = builder.loginResponseCallback
    }

    private constructor(builder: LogoutBuilder) {
        operationState = OperationState.LOGOUT
        clientId = builder.clientId
        idToken = builder.idToken
        logoutResponseCallback = builder.logoutCallback
    }

    fun getMobileNumber() = mobileNumber

    fun getOtp() = otp

    fun getLoginCallback() = loginResponseCallback

    fun getLogoutCallback() = logoutResponseCallback

    fun getOperationState() = operationState

    fun getRefreshToken() = refreshToken

    fun getIdToken() = idToken

    fun getClientId() = clientId

    companion object {

        class MobileLoginBuilder {
            lateinit var loginResponseCallback: LoginResponseCallback
            var mobileNumber = "9897646336"
            var otp = "1234"
            lateinit var clientId: String

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

            fun responseCallback(loginResponseCallback: LoginResponseCallback): MobileLoginBuilder {
                this.loginResponseCallback = loginResponseCallback
                return this
            }

            fun build() = DeHaatAuth(this)
        }

        class EmailLoginBuilder {
            lateinit var loginResponseCallback: LoginResponseCallback
            lateinit var clientId: String

            fun clientId(clientId: String): EmailLoginBuilder {
                this.clientId = clientId
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

            fun refreshToken(refreshToken: String): RenewTokenBuilder {
                this.refreshToken = refreshToken
                return this
            }

            fun clientId(clientId: String): RenewTokenBuilder {
                this.clientId = clientId
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
            lateinit var idToken: String
            lateinit var clientId: String

            fun idToken(idToken: String): LogoutBuilder {
                this.idToken = idToken
                return this
            }

            fun clientId(clientId: String): LogoutBuilder {
                this.clientId = clientId
                return this
            }

            fun responseCallback(logoutCallback: LogoutCallback): LogoutBuilder {
                this.logoutCallback = logoutCallback
                return this
            }

            fun build() = DeHaatAuth(this)
        }

        fun isSessionValid(accessTokenString: String?, refreshTokenString: String?, clientId: String): Boolean {
            val accessToken: AccessToken? = if (TextUtils.isNullCase(accessTokenString))
                null
            else
                TokenVerifier.create(accessTokenString, AccessToken::class.java).token

            val refreshToken: AccessToken? = if (TextUtils.isNullCase(refreshTokenString))
                null
            else
                TokenVerifier.create(refreshTokenString, AccessToken::class.java).token

            return accessToken?.issuedFor.equals(clientId) &&
                    refreshToken?.isExpired != true
        }
    }

    fun initialize(context: Context) {
        ClientInfo.setAuthSDK(this)

        val intent = Intent(context, LoginActivity::class.java)

        if (operationState == OperationState.RENEW_TOKEN)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }
}