package com.mbk.halatuju.core.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.model.ServiceEndpoints
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.core.model.UnitSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun settingsStorePersistsAndUpdatesFields() = runTest {
        val ds = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("settings.preferences_pb") }
        )
        val store = DataStoreSettingsStore(ds)

        store.updateThemeMode(ThemeMode.DARK)
        store.updateUnitSystem(UnitSystem.IMPERIAL)
        store.updateOverspeedToleranceKmh(10)
        store.updateAvoidances(RoutingAvoidances(avoidTolls = true, avoidFerries = true))
        store.updateEndpoints(
            ServiceEndpoints(valhallaBaseUrl = "https://valhalla.local:8002")
        )
        store.updateDeveloperDebugOverlayEnabled(true)

        val current = store.settings.first()
        assertEquals(ThemeMode.DARK, current.themeMode)
        assertEquals(UnitSystem.IMPERIAL, current.unitSystem)
        assertEquals(10, current.overspeedToleranceKmh)
        assertTrue(current.avoidances.avoidTolls)
        assertTrue(current.avoidances.avoidFerries)
        assertEquals("https://valhalla.local:8002", current.endpoints.valhallaBaseUrl)
        assertTrue(current.developerDebugOverlayEnabled)
    }

    @Test
    fun roomPlaceRepositoryManagesRecentsAndHomeWorkFavorites() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HalaTujuDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repo = RoomPlaceRepository(db.placeDao())
            val klcc = Place("klcc", "Petronas Twin Towers", "KLCC, Kuala Lumpur", KlDefaults.KLCC)
            val sentral = Place("klsentral", "KL Sentral", "Brickfields, Kuala Lumpur", KlDefaults.KL_SENTRAL)

            repo.recordRecent(klcc, 1_000L)
            repo.recordRecent(sentral, 2_000L)
            val recents = repo.observeRecents().first()
            assertEquals(listOf("klsentral", "klcc"), recents.map { it.id })

            repo.saveFavorite(klcc, FavoriteKind.WORK, 3_000L)
            repo.saveFavorite(sentral, FavoriteKind.HOME, 4_000L)
            val favorites = repo.observeFavorites().first()
            assertEquals(listOf(FavoriteKind.HOME, FavoriteKind.WORK), favorites.map { it.favoriteKind })

            repo.removeFavorite("HOME")
            val afterDelete = repo.observeFavorites().first()
            assertEquals(listOf(FavoriteKind.WORK), afterDelete.map { it.favoriteKind })
        } finally {
            db.close()
        }
    }
}
