package com.mbk.halatuju.core.routing.di

import android.content.Context
import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.routing.KlPeakTrafficProvider
import com.mbk.halatuju.core.routing.Router
import com.mbk.halatuju.core.routing.TrafficProvider
import com.mbk.halatuju.core.routing.ValhallaRouter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RoutingBindsModule {

    @Binds
    @Singleton
    abstract fun bindRouter(impl: ValhallaRouter): Router

    @Binds
    @Singleton
    abstract fun bindTrafficProvider(impl: KlPeakTrafficProvider): TrafficProvider
}

@Module
@InstallIn(SingletonComponent::class)
object RoutingProvidesModule {

    @Provides
    @Singleton
    @Named("routing")
    fun provideRoutingOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val cacheDir = File(context.cacheDir, "valhalla_http_cache")
        return OkHttpClient.Builder()
            .cache(Cache(cacheDir, 16L * 1024L * 1024L))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", AppInfo.USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}
