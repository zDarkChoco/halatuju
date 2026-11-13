package com.mbk.halatuju.core.location.di

import com.mbk.halatuju.core.location.FusedLocationAdapter
import com.mbk.halatuju.core.location.LocationSource
import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.location.NoOpFusedLocationAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {

    @Binds
    @Singleton
    abstract fun bindFusedLocationAdapter(impl: NoOpFusedLocationAdapter): FusedLocationAdapter

    @Binds
    @Singleton
    abstract fun bindLocationSource(coordinator: LocationSourceCoordinator): LocationSource
}
