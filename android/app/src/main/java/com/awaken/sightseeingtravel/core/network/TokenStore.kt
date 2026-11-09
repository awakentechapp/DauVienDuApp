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

    private val prefs: SharedPreferences = try {
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
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val _currentUser = MutableStateFlow(loadPersistedUser())
    val currentUser: StateFlow<UserProfileDto?> = _currentUser.asStateFlow()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()
        }

    var preferredLanguage: String
        get() = prefs.getString(KEY_PREFERRED_LANG, "vi") ?: "vi"
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_LANG, value).apply()
        }

    fun saveSession(accessToken: String, refreshToken: String, user: UserProfileDto) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_JSON, gson.toJson(user))
            .putString(KEY_PREFERRED_LANG, user.preferredLanguage.ifBlank { "vi" })
            .apply()
        _currentUser.value = user
    }

    fun updateUser(user: UserProfileDto) {
        prefs.edit()
            .putString(KEY_USER_JSON, gson.toJson(user))
            .putString(KEY_PREFERRED_LANG, user.preferredLanguage.ifBlank { "vi" })
            .apply()
        _currentUser.value = user
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_JSON)
            .apply()
        _currentUser.value = null
    }

    private fun loadPersistedUser(): UserProfileDto? {
        val raw = prefs.getString(KEY_USER_JSON, null) ?: return null
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
    }
}
