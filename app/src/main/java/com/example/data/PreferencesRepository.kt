package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppLanguage
import com.example.model.StaffRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chefLedgerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chef_ledger_prefs"
)

data class LocalAppPreferences(
    val darkMode: Boolean = true, // Default ON for high-contrast kitchen visibility
    val language: AppLanguage = AppLanguage.EN,
    val posApiToken: String = "",
    val activeTerminalRole: StaffRole = StaffRole.MANAGER,
    val activeStaffId: String = ""
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val LANGUAGE = stringPreferencesKey("language_code")
        val POS_API_TOKEN = stringPreferencesKey("pos_api_token_local")
        val ACTIVE_ROLE = stringPreferencesKey("active_terminal_role")
        val ACTIVE_STAFF_ID = stringPreferencesKey("active_staff_id")
    }

    val preferencesFlow: Flow<LocalAppPreferences> = context.chefLedgerDataStore.data.map { prefs ->
        LocalAppPreferences(
            darkMode = prefs[Keys.DARK_MODE] ?: true,
            language = AppLanguage.fromCode(prefs[Keys.LANGUAGE]),
            posApiToken = prefs[Keys.POS_API_TOKEN] ?: "",
            activeTerminalRole = StaffRole.fromRaw(prefs[Keys.ACTIVE_ROLE] ?: StaffRole.MANAGER.id),
            activeStaffId = prefs[Keys.ACTIVE_STAFF_ID] ?: ""
        )
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.chefLedgerDataStore.edit { it[Keys.DARK_MODE] = enabled }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.chefLedgerDataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    suspend fun setPosApiToken(token: String) {
        context.chefLedgerDataStore.edit { it[Keys.POS_API_TOKEN] = token.trim() }
    }

    suspend fun setActiveTerminalRole(role: StaffRole, staffId: String = "") {
        context.chefLedgerDataStore.edit {
            it[Keys.ACTIVE_ROLE] = role.id
            it[Keys.ACTIVE_STAFF_ID] = staffId
        }
    }
}
