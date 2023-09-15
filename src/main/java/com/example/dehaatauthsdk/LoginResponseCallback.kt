package com.example.dehaatauthsdk

import java.lang.Exception

interface LoginResponseCallback {

    fun onSuccess(tokenInfo: TokenInfo)

    fun onFailure(exception: Exception?)
}