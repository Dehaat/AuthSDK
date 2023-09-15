package com.example.dehaatauthsdk

object ClientInfo {
    private var deHaatAuth: DeHaatAuth? = null

    fun setAuthClientInfo(deHaatAuth: DeHaatAuth?) {
        this.deHaatAuth = deHaatAuth
    }

    fun getAuthClientInfo() = deHaatAuth
}