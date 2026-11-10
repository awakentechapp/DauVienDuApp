package com.awaken.sightseeingtravel.core.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.awaken.sightseeingtravel.core.model.UserProfileDto
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Encrypted JWT Token & User Session Store for Tourist, Admin, and SuperAdmin roles.
 */
class TokenStore(context: Context) {

    private val gson = Gson()

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        null
    }

    // If Android Keystore or encrypted preferences are unavailable, retain the session only
    // for this process. Never downgrade JWT persistence to plaintext SharedPreferences.
    @Volatile private var volatileAccessToken: String? = null
    @Volatile private var volatileRefreshToken: String? = null
    @Volatile private var volatilePreferredLanguage: String = "vi"
    @Volatile private var volatileUserJson: String? = null
    @Volatile private var hasVolatileAccessToken = false
    @Volatile private var hasVolatileRefreshToken = false
    @Volatile private var hasVolatilePreferredLanguage = false
    @Volatile private var hasVolatileUserJson = false

    init {
        // Earlier debug builds persisted synthetic JWT strings. They are not real sessions,
        // and replaying them after an app update causes an immediate API 401 on launch.
        val savedAccessToken = runCatching { prefs?.getString(KEY_ACCESS_TOKEN, null) }.getOrNull()
        val savedRefreshToken = runCatching { prefs?.getString(KEY_REFRESH_TOKEN, null) }.getOrNull()
        if (savedAccessToken?.startsWith(LEGACY_FAKE_ACCESS_TOKEN_PREFIX) == true ||
            savedRefreshToken?.startsWith(LEGACY_FAKE_REFRESH_TOKEN_PREFIX) == true
        ) {
            volatileAccessToken = null
            volatileRefreshToken = null
            volatileUserJson = null
            hasVolatileAccessToken = true
            hasVolatileRefreshToken = true
            hasVolatileUserJson = true
            runCatching {
                prefs?.edit()
                    ?.remove(KEY_ACCESS_TOKEN)
                    ?.remove(KEY_REFRESH_TOKEN)
                    ?.remove(KEY_USER_JSON)
                    ?.apply()
            }
        }
    }

    private val _currentUser = MutableStateFlow(loadPersistedUser())
    val currentUser: StateFlow<UserProfileDto?> = _currentUser.asStateFlow()

    var accessToken: String?
        get() = if (hasVolatileAccessToken) volatileAccessToken
            else runCatching { prefs?.getString(KEY_ACCESS_TOKEN, null) }.getOrNull()
        set(value) {
            volatileAccessToken = value
            hasVolatileAccessToken = true
            runCatching { prefs?.edit()?.putString(KEY_ACCESS_TOKEN, value)?.apply() }
        }

    var refreshToken: String?
        get() = if (hasVolatileRefreshToken) volatileRefreshToken
            else runCatching { prefs?.getString(KEY_REFRESH_TOKEN, null) }.getOrNull()
        set(value) {
            volatileRefreshToken = value
            hasVolatileRefreshToken = true
            runCatching { prefs?.edit()?.putString(KEY_REFRESH_TOKEN, value)?.apply() }
        }

    var preferredLanguage: String
        get() = if (hasVolatilePreferredLanguage) volatilePreferredLanguage
            else runCatching { prefs?.getString(KEY_PREFERRED_LANG, "vi") }.getOrNull() ?: "vi"
        set(value) {
            volatilePreferredLanguage = value
            hasVolatilePreferredLanguage = true
            runCatching { prefs?.edit()?.putString(KEY_PREFERRED_LANG, value)?.apply() }
        }

    fun saveSession(accessToken: String, refreshToken: String, user: UserProfileDto) {
        volatileAccessToken = accessToken
        volatileRefreshToken = refreshToken
        volatileUserJson = gson.toJson(user)
        volatilePreferredLanguage = user.preferredLanguage.ifBlank { "vi" }
        hasVolatileAccessToken = true
        hasVolatileRefreshToken = true
        hasVolatileUserJson = true
        hasVolatilePreferredLanguage = true
        runCatching {
            prefs?.edit()
                ?.putString(KEY_ACCESS_TOKEN, accessToken)
                ?.putString(KEY_REFRESH_TOKEN, refreshToken)
                ?.putString(KEY_USER_JSON, volatileUserJson)
                ?.putString(KEY_PREFERRED_LANG, volatilePreferredLanguage)
                ?.apply()
        }
        _currentUser.value = user
    }

    fun updateUser(user: UserProfileDto) {
        volatileUserJson = gson.toJson(user)
        volatilePreferredLanguage = user.preferredLanguage.ifBlank { "vi" }
        hasVolatileUserJson = true
        hasVolatilePreferredLanguage = true
        runCatching {
            prefs?.edit()
                ?.putString(KEY_USER_JSON, volatileUserJson)
                ?.putString(KEY_PREFERRED_LANG, volatilePreferredLanguage)
                ?.apply()
        }
        _currentUser.value = user
    }

    fun clearSession() {
        volatileAccessToken = null
        volatileRefreshToken = null
        volatileUserJson = null
        hasVolatileAccessToken = true
        hasVolatileRefreshToken = true
        hasVolatileUserJson = true
        runCatching {
            prefs?.edit()
                ?.remove(KEY_ACCESS_TOKEN)
                ?.remove(KEY_REFRESH_TOKEN)
                ?.remove(KEY_USER_JSON)
                ?.apply()
        }
        _currentUser.value = null
    }

    private fun loadPersistedUser(): UserProfileDto? {
        val raw = if (hasVolatileUserJson) volatileUserJson
            else runCatching { prefs?.getString(KEY_USER_JSON, null) }.getOrNull()
        raw ?: return null
        volatileUserJson = raw
        return try {
            gson.fromJson(raw, UserProfileDto::class.java)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "sightseeing_secure_auth_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_JSON = "user_profile_json"
        private const val KEY_PREFERRED_LANG = "preferred_language"
        private const val LEGACY_FAKE_ACCESS_TOKEN_PREFIX = "jwt-session-"
        private const val LEGACY_FAKE_REFRESH_TOKEN_PREFIX = "jwt-refresh-"
    }
}
