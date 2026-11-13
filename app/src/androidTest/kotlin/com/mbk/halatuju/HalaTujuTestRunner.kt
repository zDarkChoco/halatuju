package com.mbk.halatuju

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom instrumentation runner using [HiltTestApplication] so `@HiltAndroidTest` and `@Inject`
 * work across instrumented tests while preserving production `@AndroidEntryPoint` bindings.
 */
class HalaTujuTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
