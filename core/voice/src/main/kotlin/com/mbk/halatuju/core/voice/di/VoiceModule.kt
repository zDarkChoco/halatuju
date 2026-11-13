package com.mbk.halatuju.core.voice.di

import com.mbk.halatuju.core.voice.AndroidTtsSpeaker
import com.mbk.halatuju.core.voice.VoiceGuidancePlayer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    @Binds
    @Singleton
    abstract fun bindVoiceGuidancePlayer(impl: AndroidTtsSpeaker): VoiceGuidancePlayer
}
