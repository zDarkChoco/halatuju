package com.mbk.halatuju.core.map.di

import com.mbk.halatuju.core.map.OpenFreeMapTileSource
import com.mbk.halatuju.core.map.TileSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MapModule {

    @Binds
    @Singleton
    abstract fun bindTileSource(impl: OpenFreeMapTileSource): TileSource
}
