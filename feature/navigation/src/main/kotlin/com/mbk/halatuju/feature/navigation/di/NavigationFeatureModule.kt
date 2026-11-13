package com.mbk.halatuju.feature.navigation.di

import com.mbk.halatuju.core.navigation.NavigationForegroundController
import com.mbk.halatuju.feature.navigation.AndroidNavigationForegroundController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NavigationFeatureModule {

    @Binds
    @Singleton
    abstract fun bindNavigationForegroundController(
        impl: AndroidNavigationForegroundController
    ): NavigationForegroundController
}
