package com.silence.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "silence_contacts")

/**
 * Lightweight contact store — maps display names to identity public keys (base64).
 *
 * Contacts are added by scanning a QR code from another Silence user.
 * The QR code payload is `silence://<base64_public_key>`.
 * The user assigns a display name after scanning.
 */
@Singleton
class ContactStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val CONTACT_NAMES = stringSetPreferencesKey("contact_names")
        fun pubKeyFor(name: String) = stringPreferencesKey("contact_pub_$name")
    }

    val contacts: Flow<List<Contact>> = context.dataStore.data.map { prefs ->
        val names = prefs[Keys.CONTACT_NAMES] ?: emptySet()
        names.mapNotNull { name ->
            val pubB64 = prefs[Keys.pubKeyFor(name)] ?: return@mapNotNull null
            Contact(name, pubB64)
        }.sortedBy { it.name }
    }

    suspend fun addContact(name: String, publicKeyB64: String) {
        context.dataStore.edit { prefs ->
            val names = (prefs[Keys.CONTACT_NAMES] ?: emptySet()).toMutableSet()
            names.add(name)
            prefs[Keys.CONTACT_NAMES] = names
            prefs[Keys.pubKeyFor(name)] = publicKeyB64
        }
    }

    suspend fun removeContact(name: String) {
        context.dataStore.edit { prefs ->
            val names = (prefs[Keys.CONTACT_NAMES] ?: emptySet()).toMutableSet()
            names.remove(name)
            prefs[Keys.CONTACT_NAMES] = names
            prefs.remove(Keys.pubKeyFor(name))
        }
    }
}

data class Contact(
    val name: String,
    val publicKeyB64: String
)
