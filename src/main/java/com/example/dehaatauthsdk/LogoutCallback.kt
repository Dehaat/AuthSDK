package com.example.dehaatauthsdk

interface LogoutCallback {
    fun onLogoutSuccess()
    fun onLogoutFailure(exception: Exception?)
}