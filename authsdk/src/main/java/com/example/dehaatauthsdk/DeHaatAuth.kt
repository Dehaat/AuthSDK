package com.example.dehaatauthsdk

import android.content.Context
import android.content.Intent

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
    private  var operationState: OperationState

    private constructor(builder:MobileLoginBuilder){
        operationState = OperationState.MOBILE_LOGIN
        mobileNumber=builder.mobileNumber
        otp = builder.otp
        clientId = builder.clientId
        loginResponseCallback= builder.loginResponseCallback
    }

    private constructor(builder:EmailLoginBuilder){
        operationState = OperationState.EMAIL_LOGIN
        clientId = builder.clientId
        loginResponseCallback= builder.loginResponseCallback
    }

    private constructor(builder:RenewTokenBuilder){
        operationState = OperationState.RENEW_TOKEN
        clientId = builder.clientId
        refreshToken = builder.refreshToken
        loginResponseCallback= builder.loginResponseCallback
    }

    private constructor(builder:LogoutBuilder){
        operationState = OperationState.LOGOUT
        clientId= builder.clientId
        idToken = builder.idToken
        logoutResponseCallback= builder.logoutCallback
    }

    fun getMobileNumber() = mobileNumber

    fun getOtp() = otp

    fun getLoginCallback() = loginResponseCallback

    fun getLogoutCallback() = logoutResponseCallback

    fun getOperationState() = operationState

    fun getRefreshToken() = refreshToken

    fun getIdToken() = idToken

    fun getClientId() = clientId

    companion object{

        class  MobileLoginBuilder {
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

        class  EmailLoginBuilder {
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

        class  RenewTokenBuilder {
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

        class  LogoutBuilder {
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
    }

    fun initialize(context: Context) {
        ClientInfo.setAuthSDK(this)
        context.startActivity(Intent(context, LoginActivity::class.java))
    }

}