package dev.s2tmic.companion

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.s2tmic.companion.accessibility.DictationAccessibilityService

class MainActivity : ComponentActivity() {
    private var microphoneGranted by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var keyStored by mutableStateOf(false)
    private var positionLocked by mutableStateOf(false)

    private val keyStore get() = (application as S2TApplication).apiKeyStore
    private val overlaySettings get() = (application as S2TApplication).overlaySettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshStatus()
        setContent {
            S2TTheme {
                SetupScreen(
                    microphoneGranted = microphoneGranted,
                    accessibilityEnabled = accessibilityEnabled,
                    keyStored = keyStored,
                    positionLocked = positionLocked,
                    onSaveKey = {
                        keyStore.save(it)
                        keyStored = keyStore.hasKey()
                    },
                    onDeleteKey = {
                        keyStore.clear()
                        keyStored = false
                    },
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onPositionLockedChange = {
                        overlaySettings.setPositionLocked(it)
                        positionLocked = it
                    },
                    onResetPosition = overlaySettings::clearPositions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        microphoneGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        keyStored = keyStore.hasKey()
        positionLocked = overlaySettings.isPositionLocked()

        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        accessibilityEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val service = info.resolveInfo.serviceInfo
                service.packageName == packageName &&
                    service.name == DictationAccessibilityService::class.java.name
            }
    }

    @Composable
    private fun SetupScreen(
        microphoneGranted: Boolean,
        accessibilityEnabled: Boolean,
        keyStored: Boolean,
        positionLocked: Boolean,
        onSaveKey: (String) -> Unit,
        onDeleteKey: () -> Unit,
        onOpenAccessibility: () -> Unit,
        onPositionLockedChange: (Boolean) -> Unit,
        onResetPosition: () -> Unit,
    ) {
        var apiKey by remember { mutableStateOf("") }
        var saveMessage by remember { mutableStateOf<String?>(null) }
        val focusManager = LocalFocusManager.current
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { refreshStatus() }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("S2T Mic", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Diktat direkt auf Gboard. Der erkannte Text landet an der aktuellen Cursorposition.",
                    style = MaterialTheme.typography.bodyLarge,
                )

                StatusCard("1 · Mikrofon", microphoneGranted) {
                    if (!microphoneGranted) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("Mikrofon erlauben")
                        }
                    }
                }

                StatusCard("2 · Groq API-Key", keyStored) {
                    Text(
                        if (keyStored) "Der Key liegt verschlüsselt im Android Keystore. Zum Ersetzen einen neuen eingeben."
                        else "Der Key wird nur lokal und verschlüsselt gespeichert.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            saveMessage = null
                        },
                        label = { Text("Groq API-Key") },
                        placeholder = { Text("gsk_…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = apiKey.isNotBlank(),
                            onClick = {
                                runCatching { onSaveKey(apiKey) }
                                    .onSuccess {
                                        apiKey = ""
                                        saveMessage = "Gespeichert"
                                        focusManager.clearFocus()
                                    }
                                    .onFailure { saveMessage = "Speichern fehlgeschlagen" }
                            },
                        ) { Text("Key speichern") }
                        if (keyStored) TextButton(onClick = onDeleteKey) { Text("Löschen") }
                    }
                    saveMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }

                StatusCard("3 · Eingabehilfe", accessibilityEnabled) {
                    Text(
                        "Aktiviere „S2T Mic Eingabehilfe“. Sie erkennt fokussierte Textfelder und darf dort Text einsetzen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenAccessibility) { Text("Eingabehilfe öffnen") }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Mic-Position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (positionLocked) "Position ist gesperrt; das Icon reagiert nur auf Tippen."
                            else "Ziehe das Icon an die gewünschte Stelle. Die Position wird automatisch gespeichert.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Position sperren")
                            Switch(checked = positionLocked, onCheckedChange = onPositionLockedChange)
                        }
                        TextButton(onClick = onResetPosition) { Text("Standardposition wiederherstellen") }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("So benutzt du es", fontWeight = FontWeight.Bold)
                        Text("Öffne ein Textfeld mit Gboard. Tippe auf das kleine Mic oben rechts, sprich und tippe erneut zum Einfügen.")
                        Text("Modell: Groq Whisper Large V3 Turbo · mehrsprachig")
                    }
                }

                Text(
                    "Hinweis: Nach dem Stoppen wird die Aufnahme zur Transkription an Groq übertragen. Ein direkt im Client gespeicherter API-Key ist für den persönlichen Gebrauch gedacht.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun StatusCard(
        title: String,
        complete: Boolean,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (complete) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (complete) Color(0xFF2E7D32) else MaterialTheme.colorScheme.tertiary,
                    )
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                content()
            }
        }
    }
}

@Composable
private fun S2TTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF6750A4),
            background = Color(0xFFFFF8F6),
            surface = Color(0xFFFFFBFF),
        ),
        content = content,
    )
}
