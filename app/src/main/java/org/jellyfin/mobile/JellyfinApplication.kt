package org.jellyfin.mobile

import android.app.Application
import android.webkit.WebView
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.apiModule
import org.jellyfin.mobile.app.applicationModule
import org.jellyfin.mobile.data.databaseModule
import org.jellyfin.mobile.utils.JellyTree
import org.jellyfin.mobile.utils.isWebViewSupported
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.fragment.koin.fragmentFactory
import org.koin.core.context.startKoin
import timber.log.Timber

@Suppress("unused")
class JellyfinApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Setup logging
        Timber.plant(JellyTree())

        val koin = startKoin {
            androidContext(this@JellyfinApplication)
            fragmentFactory()

            modules(
                applicationModule,
                apiModule,
                databaseModule,
            )
        }.koin

        if (BuildConfig.DEBUG) {
            val clearCacheOnStartup = koin.get<AppPreferences>().clearCacheOnStartup
            if (clearCacheOnStartup) {
                for (directory in externalCacheDirs + cacheDir) {
                    directory?.listFiles()?.forEach { it.deleteRecursively() }
                }
            }

            if (isWebViewSupported()) {
                if (clearCacheOnStartup) {
                    WebView(this).apply {
                        clearCache(true)
                        destroy()
                    }
                }
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
    }
}
