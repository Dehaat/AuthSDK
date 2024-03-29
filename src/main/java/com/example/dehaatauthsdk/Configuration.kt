package com.example.dehaatauthsdk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import android.text.TextUtils
import net.openid.appauth.connectivity.ConnectionBuilder
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import okio.Buffer
import okio.buffer
import okio.source
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets

/**
 * Reads and validates the demo app configuration from `res/raw/auth_config.json`. Configuration
 * changes are detected by comparing the hash of the last known configuration to the read
 * configuration. When a configuration change is detected, the app state is reset.
 */
class Configuration(private val mContext: Context, private val _clientId:String,
  private val isDebugMode:Boolean) {
    private val mPrefs: SharedPreferences =
        mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mResources: Resources = mContext.resources
    private var mConfigJson: JSONObject? = null
    private var mConfigHash: String? = null

    /**
     * Returns a description of the configuration error, if the configuration is invalid.
     */
    private var configurationError: String? = null
    var clientId: String? = null
        private set
    private var mScope: String? = null
    private var mRedirectUri: Uri? = null
    var endSessionRedirectUri: Uri? = null
        private set
    var discoveryUri: Uri? = null
        private set
    private var authEndpointUri: Uri? = null
    var tokenEndpointUri: Uri? = null
        private set
    private var endSessionEndpoint: Uri? = null
    private var registrationEndpointUri: Uri? = null
    private var userInfoEndpointUri: Uri? = null
    private var isHttpsRequired = false
    val connectionBuilder: ConnectionBuilder
        get() = if (isHttpsRequired) {
            DefaultConnectionBuilder.INSTANCE
        } else ConnectionBuilderForTesting.INSTANCE

    /**
     * Indicates whether the configuration has changed from the last known valid state.
     */
    fun hasConfigurationChanged(): Boolean {
        val lastHash = lastKnownConfigHash
        return mConfigHash != lastHash
    }

    /**
     * Indicates whether the current configuration is valid.
     */
    val isValid: Boolean
        get() = configurationError == null

    /**
     * Indicates that the current configuration should be accepted as the "last known valid"
     * configuration.
     */
    fun acceptConfiguration() {
        mPrefs.edit().putString(KEY_LAST_HASH, mConfigHash).apply()
    }

    val scope: String
        get() = mScope!!
    val redirectUri: Uri
        get() = mRedirectUri!!
    private val lastKnownConfigHash: String?
        get() = mPrefs.getString(KEY_LAST_HASH, null)

    @Throws(InvalidConfigurationException::class)
    private fun readConfiguration() {
        val configSource = mResources.openRawResource(R.raw.auth_config).source().buffer()
        val configData = Buffer()
        mConfigJson = try {
            configSource.readAll(configData)
            JSONObject(configData.readString(StandardCharsets.UTF_8))
        } catch (ex: IOException) {
            throw InvalidConfigurationException(
                "Failed to read configuration: " + ex.message
            )
        } catch (ex: JSONException) {
            throw InvalidConfigurationException(
                "Unable to parse configuration: " + ex.message
            )
        }
        mConfigHash = configData.sha256().base64()
        clientId = _clientId
        mScope = getRequiredConfigString("authorization_scope")
        mRedirectUri = getRequiredConfigUri("redirect_uri")
        endSessionRedirectUri = getRequiredConfigUri("end_session_redirect_uri")
        if (!isRedirectUriRegistered) {
            throw InvalidConfigurationException(
                "redirect_uri is not handled by any activity in this app! "
                        + "Ensure that the appAuthRedirectScheme in your build.gradle file "
                        + "is correctly configured, or that an appropriate intent filter "
                        + "exists in your app manifest."
            )
        }
        if (getConfigString("discovery_uri") == null) {
            authEndpointUri = getRequiredConfigWebUri("authorization_endpoint_uri")
            tokenEndpointUri = getRequiredConfigWebUri("token_endpoint_uri")
            userInfoEndpointUri = getRequiredConfigWebUri("user_info_endpoint_uri")
            endSessionEndpoint = getRequiredConfigUri("end_session_endpoint")
            if (clientId == null) {
                registrationEndpointUri = getRequiredConfigWebUri("registration_endpoint_uri")
            }
        } else {
            discoveryUri = getRequiredConfigWebUri("discovery_uri")
        }

        isHttpsRequired =
            if (isDebugMode) false else mConfigJson!!.optBoolean("https_required", true)
    }

    private fun getConfigString(propName: String?): String? {
        var value = mConfigJson!!.optString(propName) ?: return null
        ClientInfo.getAuthClientInfo()?.let {
            if(it.getIsDebugMode()){
                val clientDomain = ClientInfo.getAuthClientInfo()?.getClientKeycloakDomain()
                clientDomain?.let { value = value.replace("https://oidc.agrevolution.in", it) }
            }
        }
        value = value.trim { it <= ' ' }
        return if (TextUtils.isEmpty(value)) {
            null
        } else value
    }

    @Throws(InvalidConfigurationException::class)
    private fun getRequiredConfigString(propName: String): String {
        return getConfigString(propName)
            ?: throw InvalidConfigurationException(
                "$propName is required but not specified in the configuration"
            )
    }

    @Throws(InvalidConfigurationException::class)
    fun getRequiredConfigUri(propName: String): Uri {
        val uriStr = getRequiredConfigString(propName)
        val uri: Uri = try {
            Uri.parse(uriStr)
        } catch (ex: Throwable) {
            throw InvalidConfigurationException("$propName could not be parsed", ex)
        }
        if (!uri.isHierarchical || !uri.isAbsolute) {
            throw InvalidConfigurationException(
                "$propName must be hierarchical and absolute"
            )
        }
        if (!TextUtils.isEmpty(uri.encodedUserInfo)) {
            throw InvalidConfigurationException("$propName must not have user info")
        }
        if (!TextUtils.isEmpty(uri.encodedQuery)) {
            throw InvalidConfigurationException("$propName must not have query parameters")
        }
        if (!TextUtils.isEmpty(uri.encodedFragment)) {
            throw InvalidConfigurationException("$propName must not have a fragment")
        }
        return uri
    }

    @Throws(InvalidConfigurationException::class)
    fun getRequiredConfigWebUri(propName: String): Uri {
        val uri = getRequiredConfigUri(propName)
        val scheme = uri.scheme
        if (TextUtils.isEmpty(scheme) || !("http" == scheme || "https" == scheme)) {
            throw InvalidConfigurationException(
                "$propName must have an http or https scheme"
            )
        }
        return uri
    }

    // ensure that the redirect URI declared in the configuration is handled by some activity
    // in the app, by querying the package manager speculatively
    private val isRedirectUriRegistered: Boolean
        get() {
            // ensure that the redirect URI declared in the configuration is handled by some activity
            // in the app, by querying the package manager speculatively
            val redirectIntent = Intent()
            redirectIntent.setPackage(mContext.packageName)
            redirectIntent.action = Intent.ACTION_VIEW
            redirectIntent.addCategory(Intent.CATEGORY_BROWSABLE)
            redirectIntent.data = mRedirectUri
            return mContext.packageManager.queryIntentActivities(redirectIntent, 0).isNotEmpty()
        }

    class InvalidConfigurationException : Exception {
        internal constructor(reason: String?) : super(reason)
        internal constructor(reason: String?, cause: Throwable?) : super(reason, cause)
    }

    companion object {
        private const val PREFS_NAME = "config"
        private const val KEY_LAST_HASH = "lastHash"
        private var sInstance = WeakReference<Configuration?>(null)
        fun getInstance(context: Context, _clientId:String, isDebugMode:Boolean,
                        isLogin: Boolean): Configuration {
            var config = sInstance.get()
            /* (isLogin && isDebugMode) This check is required when we want to change base url
            ** for keycloak server
             */
            if (config == null || (isLogin && isDebugMode)) {
                config = Configuration(context,_clientId,isDebugMode)
                sInstance = WeakReference(config)
            }
            return config
        }
    }

    init {
        try {
            readConfiguration()
        } catch (ex: InvalidConfigurationException) {
            configurationError = ex.message
        }
    }
}