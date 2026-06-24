package com.silence.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authStore by preferencesDataStore(name = "silence_auth")

data class AuthState(val username: String?, val password: String?) {
    val isLoggedIn: Boolean get() = username != null && password != null
}

@Singleton
class AuthStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val USERNAME = stringPreferencesKey("auth_username")
        val PASSWORD = stringPreferencesKey("auth_password")
    }

    val authState: Flow<AuthState> = context.authStore.data.map { prefs ->
        AuthState(
            username = prefs[Keys.USERNAME],
            password = prefs[Keys.PASSWORD]
        )
    }

    suspend fun saveCredentials(username: String, password: String) {
        context.authStore.edit { prefs ->
            prefs[Keys.USERNAME] = username
            prefs[Keys.PASSWORD] = password
        }
    }

    suspend fun clearCredentials() {
        context.authStore.edit { it.clear() }
    }
}
