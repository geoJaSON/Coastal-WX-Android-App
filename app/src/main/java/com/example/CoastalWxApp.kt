package com.example

import android.app.Application
import androidx.work.Configuration
import com.example.worker.WeatherSyncWorker

class CoastalWxApp : Application(), Configuration.Provider {

    // On-demand initialization (the default androidx.startup initializer is removed in the
    // manifest) so WorkManager.getInstance works anywhere a real Application exists,
    // including Robolectric tests.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        WeatherSyncWorker.ensureScheduled(this)
    }
}
