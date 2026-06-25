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

private val Context.settingsStore by preferencesDataStore(name = "silence_settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SIGNALING_URL = stringPreferencesKey("signaling_url")
    }

    val signalingUrl: Flow<String> = context.settingsStore.data.map { prefs ->
        prefs[Keys.SIGNALING_URL] ?: DEFAULT_SIGNALING_URL
    }

    suspend fun setSignalingUrl(url: String) {
        context.settingsStore.edit { it[Keys.SIGNALING_URL] = url }
    }

    companion object {
        const val DEFAULT_SIGNALING_URL = "ws://45.83.179.102:8088/ws" // public relay host
    }
}
