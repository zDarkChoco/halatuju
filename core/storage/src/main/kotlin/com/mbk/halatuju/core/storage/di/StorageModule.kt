package com.mbk.halatuju.core.storage.di

import android.content.Context
import androidx.room.Room
import com.mbk.halatuju.core.storage.DataStoreSettingsStore
import com.mbk.halatuju.core.storage.HalaTujuDatabase
import com.mbk.halatuju.core.storage.PlaceDao
import com.mbk.halatuju.core.storage.PlaceRepository
import com.mbk.halatuju.core.storage.RoomPlaceRepository
import com.mbk.halatuju.core.storage.SettingsStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageBindsModule {

    @Binds
    @Singleton
    abstract fun bindSettingsStore(impl: DataStoreSettingsStore): SettingsStore

    @Binds
    @Singleton
    abstract fun bindPlaceRepository(impl: RoomPlaceRepository): PlaceRepository
}

@Module
@InstallIn(SingletonComponent::class)
object StorageProvidesModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HalaTujuDatabase {
        return Room.databaseBuilder(
            context,
            HalaTujuDatabase::class.java,
            "halatuju.db"
        ).build()
    }

    @Provides
    fun providePlaceDao(db: HalaTujuDatabase): PlaceDao = db.placeDao()
}
