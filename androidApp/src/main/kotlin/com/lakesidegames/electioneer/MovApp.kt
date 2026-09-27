package com.lakesidegames.electioneer

import android.app.Application
import com.lakesidegames.electioneer.BuildConfig
import io.sentry.android.core.SentryAndroid

// Phase 6 crash gate (#24): Sentry starts only when a DSN is baked in at
// release time. Debug and pre-launch builds run with no reporter.
class MovApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isNotBlank()) {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
            }
        }
    }
}
