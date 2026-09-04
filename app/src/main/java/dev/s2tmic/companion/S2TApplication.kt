package dev.s2tmic.companion

import android.app.Application
import dev.s2tmic.companion.data.ApiKeyStore
import dev.s2tmic.companion.data.OverlaySettingsStore

class S2TApplication : Application() {
    val apiKeyStore by lazy { ApiKeyStore(this) }
    val overlaySettingsStore by lazy { OverlaySettingsStore(this) }
}
