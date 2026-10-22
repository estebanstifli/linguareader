package com.linguareader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Almacén de preferencias del usuario en Android: `files/datastore/user_preferences.preferences_pb`, una sola
 * instancia por proceso (DataStore no admite dos sobre el mismo fichero). El repositorio que lo lee y escribe
 * ([UserPreferencesRepository]) es común a todas las plataformas.
 */
val Context.userPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")
