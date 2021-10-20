package com.example.dehaatauthsdk

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import net.openid.appauth.Preconditions
import net.openid.appauth.connectivity.ConnectionBuilder
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * An example implementation of [ConnectionBuilder] that permits connecting to http
 * links, and ignores certificates for https connections. *THIS SHOULD NOT BE USED IN PRODUCTION
 * CODE*. It is intended to facilitate easier testing of AppAuth against development servers
 * only.
 */
class ConnectionBuilderForTesting private constructor() : ConnectionBuilder {
    companion object {
        val INSTANCE = ConnectionBuilderForTesting()
        private const val TAG = "ConnBuilder"
        private val CONNECTION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15)
            .toInt()
        private val READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10).toInt()
        private const val HTTP = "http"
        private const val HTTPS = "https"

        private val ANY_CERT_MANAGER = arrayOf<TrustManager>(
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate>? {
                    return null
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            }
        )

        @SuppressLint("BadHostnameVerifier")
        private val ANY_HOSTNAME_VERIFIER = HostnameVerifier { _, _ -> true }
        private var TRUSTING_CONTEXT: SSLContext? = null

        init {
            val context: SSLContext? = try {
                SSLContext.getInstance("SSL")
            } catch (e: NoSuchAlgorithmException) {
                Log.e("ConnBuilder", "Unable to acquire SSL context")
                null
            }
            var initializedContext: SSLContext? = null
            if (context != null) {
                try {
                    context.init(null, ANY_CERT_MANAGER, SecureRandom())
                    initializedContext = context
                } catch (e: KeyManagementException) {
                    Log.e(TAG, "Failed to initialize trusting SSL context")
                }
            }
            TRUSTING_CONTEXT = initializedContext
        }
    }

    @Throws(IOException::class)
    override fun openConnection(uri: Uri): HttpURLConnection {
        Preconditions.checkNotNull(uri, "url must not be null")
        Preconditions.checkArgument(
            HTTP == uri.scheme || HTTPS == uri.scheme,
            "scheme or uri must be http or https"
        )
        val conn = URL(uri.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECTION_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        if (conn is HttpsURLConnection && TRUSTING_CONTEXT != null) {
            conn.sslSocketFactory =
                TRUSTING_CONTEXT?.socketFactory
            conn.hostnameVerifier = ANY_HOSTNAME_VERIFIER
        }
        return conn
    }
}