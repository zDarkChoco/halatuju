package com.mbk.halatuju.core.geocoding.di

import android.content.Context
import com.mbk.halatuju.core.geocoding.Geocoder
import com.mbk.halatuju.core.geocoding.OpenOsmGeocoder
import com.mbk.halatuju.core.model.AppInfo
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
abstract class GeocodingBindsModule {

    @Binds
    @Singleton
    abstract fun bindGeocoder(impl: OpenOsmGeocoder): Geocoder
}

@Module
@InstallIn(SingletonComponent::class)
object GeocodingProvidesModule {

    @Provides
    @Singleton
    @Named("geocoding")
    fun provideGeocodingOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val cacheDir = File(context.cacheDir, "geocoding_http_cache")
        return OkHttpClient.Builder()
            .cache(Cache(cacheDir, 16L * 1024L * 1024L))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", AppInfo.USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}
