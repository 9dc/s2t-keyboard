package dev.s2tmic.companion

import android.app.Application
import dev.s2tmic.companion.data.ApiKeyStore

class S2TApplication : Application() {
    val apiKeyStore by lazy { ApiKeyStore(this) }
}

