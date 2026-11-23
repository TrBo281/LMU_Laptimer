package com.lmulaptimer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lmulaptimer.BuildConfig
import com.lmulaptimer.data.AutoSync
import com.lmulaptimer.data.DemoCandidates
import com.lmulaptimer.data.Repo
import com.lmulaptimer.data.discoverBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.io.ByteArrayInputStream

@Composable
fun SettingsScreen(
    repo: Repo,
    night: Boolean,
    onNightChanged: (Boolean) -> Unit,
    onSynced: () -> Unit,
) {
    var url by remember { mutableStateOf(repo.baseUrl) }
    var token by remember { mutableStateOf(repo.token) }
    var autoSync by remember { mutableStateOf(repo.autoSync) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var storedLaps by remember { mutableIntStateOf(0) }
    var storedSessions by remember { mutableIntStateOf(0) }
    var demo by remember { mutableStateOf(DemoCandidates(emptyList(), 0, 0)) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var tyrePressure by remember { mutableStateOf(String.format(Locale.US, "%.0f", repo.tyreTargetPressureKpa)) }
    var tyreTemp by remember { mutableStateOf(String.format(Locale.US, "%.0f", repo.tyreMaxTempC)) }
    var tyreWear by remember { mutableStateOf(String.format(Locale.US, "%.0f", repo.tyreWearWarnPct)) }
    var bridgeVersion by remember { mutableStateOf("–") }
    var bridgeSource by remember { mutableStateOf("–") }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var storage by remember { mutableStateOf<JSONObject?>(null) }
    var cacheCount by remember { mutableIntStateOf(0) }
    var cacheBytes by remember { mutableStateOf(0L) }
    var audioOn by remember { mutableStateOf(repo.audioEnabled) }
    var audioVolume by remember { mutableIntStateOf(repo.audioVolume) }
    var notifyOn by remember { mutableStateOf(repo.liveNotifyEnabled) }
    var hueHost by remember { mutableStateOf("") }
    var hueUser by remember { mutableStateOf("") }
    var hueLight by remember { mutableStateOf("1") }
    var hueEnabled by remember { mutableStateOf(false) }
    var hueStatus by remember { mutableStateOf("") }
    var discovering by remember { mutableStateOf(false) }
    var discoveryStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val scanQr = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            status = "QR-Scan abgebrochen."
        } else {
            val parts = contents.split('|', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                url = parts[0].trim()
                token = parts[1].trim()
                repo.baseUrl = url
                repo.token = token
                status = "Pairing übernommen: URL und Token gesetzt."
            } else {
                status = "QR-Code enthält nicht das Format URL|Token."
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanQr.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Bridge-QR-Code scannen")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
            )
        } else {
            status = "Kamera-Berechtigung wird für den QR-Scan benötigt."
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Android 16+ gates local-network access behind a runtime permission.
    val localNetworkPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        discoveryStatus = if (granted) "Lokales Netzwerk erlaubt – jetzt erneut suchen." else "Ohne lokale Netzwerk-Berechtigung findet mDNS die Bridge nicht."
    }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val text = withContext(Dispatchers.IO) { repo.exportArchive() }
                val ok = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(text.toByteArray(Charsets.UTF_8))
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                status = if (ok) "Backup gespeichert." else "Backup konnte nicht geschrieben werden."
                busy = false
            }
        }
    }

    val exportCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val text = withContext(Dispatchers.IO) { repo.exportLapsCsv() }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(text.toByteArray(Charsets.UTF_8))
                        }
                    }.isSuccess
                }
                status = if (ok) "CSV gespeichert." else "CSV konnte nicht geschrieben werden."
                busy = false
            }
        }
    }

    val exportZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Kein Ausgabestream")
                        output.use { stream -> repo.exportFullBackup(stream) }
                    }.isSuccess
                }
                status = if (result) "Vollbackup gespeichert (Metadaten + Samples)." else "Vollbackup konnte nicht geschrieben werden."
                busy = false
            }
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val bytes = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bytes == null) {
                    status = "Backup konnte nicht gelesen werden."
                } else {
                    val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            if (isZip) repo.importFullBackup(ByteArrayInputStream(bytes))
                            else repo.importArchive(bytes.toString(Charsets.UTF_8))
                        }.getOrNull()
                    }
                    if (result == null) {
                        status = "Datei ist kein gültiges LMU-Laptimer-Backup."
                    } else {
                        storedLaps = withContext(Dispatchers.IO) { repo.countLaps() }
                        storedSessions = withContext(Dispatchers.IO) { repo.sessions().size }
                        demo = withContext(Dispatchers.IO) { repo.demoCandidates() }
                        status = "${result.first} Sessions, ${result.second} Runden importiert."
                        onSynced()
                    }
                }
                busy = false
            }
        }
    }

    LaunchedEffect(url, token) {
        qr = if (url.isBlank() || token.isBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) { makeQr("${url.trim()}|${token.trim()}") }
        }
    }

    suspend fun refreshArchive() {
        storedLaps = withContext(Dispatchers.IO) { repo.countLaps() }
        storedSessions = withContext(Dispatchers.IO) { repo.sessions().size }
        demo = withContext(Dispatchers.IO) { repo.demoCandidates() }
    }

    suspend fun refreshStorage() {
        storage = repo.storageInfo()
        cacheCount = withContext(Dispatchers.IO) { repo.cachedSampleCount() }
        cacheBytes = withContext(Dispatchers.IO) { repo.cachedSampleBytes() }
    }

    LaunchedEffect(Unit) {
        refreshArchive()
        refreshStorage()
        val hue = repo.hueConfig()
        if (hue != null) {
            hueHost = hue.optString("bridge_ip", "")
            hueLight = hue.optString("light", "1")
            hueEnabled = hue.optBoolean("enabled", false)
            hueStatus = if (hue.optBoolean("configured", false)) "konfiguriert" else "nicht konfiguriert"
        }
    }

    fun persist() {
        repo.baseUrl = url
        repo.token = token
    }

    fun setAutoSync(enabled: Boolean) {
        autoSync = enabled
        repo.autoSync = enabled
        status = if (enabled) "Auto-Sync aktiviert." else "Auto-Sync ausgeschaltet."
        if (enabled) {
            AutoSync.schedule(context)
            AutoSync.runNow(context)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            AutoSync.cancel(context)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bridge", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(
            "Die Bridge läuft auf dem PC mit LMU. Beide Geräte müssen im selben WLAN sein.",
            fontSize = 12.sp,
            color = TextDim,
        )

        Panel(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; persist() },
                label = { Text("Bridge-URL", fontSize = 11.sp) },
                placeholder = { Text("http://192.168.178.39:8756", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; persist() },
                label = { Text("Token", fontSize = 11.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "URL und Token stehen auf der Pairing-Seite der Bridge: http://127.0.0.1:8756/pair",
                fontSize = 11.sp,
                color = TextDim,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    persist()
                    busy = true
                    status = "Prüfe Verbindung…"
                    scope.launch {
                        status = repo.testConnection()
                        val health = repo.health()
                        bridgeVersion = health?.optString("version", "–") ?: "–"
                        bridgeSource = health?.optString("source", "–") ?: "–"
                        busy = false
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextMain),
                modifier = Modifier.weight(1f),
            ) {
                Text("Testen", fontSize = 13.sp)
            }
            Button(
                onClick = {
                    persist()
                    busy = true
                    status = "Synchronisiere…"
                    scope.launch {
                        val result = repo.sync()
                        refreshArchive()
                        status = when {
                            result.error != null -> result.error
                            result.sessions == 0 && result.laps == 0 ->
                                "Alles aktuell – keine neuen Runden."
                            result.sessions == 0 && result.laps == 1 ->
                                "1 Runde aktualisiert."
                            result.sessions == 0 -> "${result.laps} Runden aktualisiert."
                            result.sessions == 1 && result.laps == 1 ->
                                "1 Session, 1 Runde importiert."
                            result.sessions == 1 -> "1 Session, ${result.laps} Runden importiert."
                            else -> "${result.sessions} Sessions, ${result.laps} Runden importiert."
                        }
                        busy = false
                        onSynced()
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Bg),
                modifier = Modifier.weight(1f),
            ) {
                Text("Synchronisieren", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }

        if (status.isNotBlank()) {
            Panel(Modifier.fillMaxWidth()) {
                Text(status, fontSize = 13.sp, color = TextMain)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Auto-Sync")
                    Text(
                        if (autoSync) "Prüft alle 15 Minuten, sobald die Bridge erreichbar ist."
                        else "Aus – Sessions werden nur manuell importiert.",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
                Switch(checked = autoSync, onCheckedChange = { setAutoSync(it) })
            }
            Text(
                "Der Hintergrund-Sync braucht WLAN und eine eingetragene Bridge-URL. " +
                    "Beim Einschalten läuft sofort ein erster Versuch; neue Sessions melden sich per Benachrichtigung.",
                fontSize = 11.sp,
                color = TextDim,
            )
            if (autoSync && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                TextButton(
                    onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                ) {
                    Text("Benachrichtigungen erlauben", color = Cyan, fontSize = 12.sp)
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Nachtmodus")
                    Text(
                        if (night) "Aktiv: rote, gedimmte Palette für Nachtstints."
                        else "Aus: Lime-Pitwall-Palette.",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
                Switch(checked = night, onCheckedChange = onNightChanged)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Reifenziele")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingField(
                    "Zieldruck",
                    tyrePressure,
                    {
                        tyrePressure = it
                        it.replace(',', '.').toDoubleOrNull()?.let { value ->
                            repo.tyreTargetPressureKpa = value
                        }
                    },
                    "kPa",
                    Modifier.weight(1f),
                )
                SettingField(
                    "Max. Temp",
                    tyreTemp,
                    {
                        tyreTemp = it
                        it.replace(',', '.').toDoubleOrNull()?.let { value ->
                            repo.tyreMaxTempC = value
                        }
                    },
                    "°C",
                    Modifier.weight(1f),
                )
            }
            SettingField(
                "Verschleiß-Warnung",
                tyreWear,
                {
                    tyreWear = it
                    it.replace(',', '.').toDoubleOrNull()?.let { value ->
                        repo.tyreWearWarnPct = value
                    }
                },
                "%",
                Modifier.fillMaxWidth(),
            )
            Text(
                "Diese Ziele färben Druck, Temperatur und Verschleiß im Live-Tab.",
                fontSize = 11.sp,
                color = TextDim,
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Lokales Archiv")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Value("Sessions", "$storedSessions")
                Value("Runden", "$storedLaps")
            }
            Text(
                "Das Handy ist die einzige Datenquelle. Die Bridge behält ihre Dateien, bis die App eine Session bestätigt.",
                fontSize = 11.sp,
                color = TextDim,
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Backup & Export")
            Text(
                "Sessions, Runden und Setups als JSON sichern oder auf einem anderen Gerät wieder einlesen.",
                fontSize = 11.sp,
                color = TextDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { exportBackup.launch("lmu-laptimer-backup.json") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Backup speichern", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { importBackup.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Backup laden", fontSize = 12.sp)
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Demo-Daten")
            if (demo.sessions == 0) {
                Text(
                    "Keine Demo-Sessions im Archiv erkannt.",
                    fontSize = 12.sp,
                    color = TextDim,
                )
            } else {
                Text(
                    "${demo.sessions} Sessions / ${demo.laps} Runden als Demo erkannt " +
                        "(Circuit de la Sarthe, Ferrari 499P, 3,10 L und 0,031 VE pro Runde).",
                    fontSize = 12.sp,
                    color = TextMain,
                )
                Text(
                    "Diese Sessions zählen aktuell als Bestzeiten mit. Das Löschen entfernt nur die erkannten Demo-Sessions endgültig.",
                    fontSize = 11.sp,
                    color = TextDim,
                )
                Button(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Bg),
                ) {
                    Text("Demo-Daten löschen", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Ohne LMU testen")
            Text(
                "Auf dem PC: cd bridge, dann .venv\\Scripts\\python.exe run.py --source demo. " +
                    "Die Bridge erzeugt dann synthetische Le-Mans-Runden.",
                fontSize = 12.sp,
                color = TextDim,
            )
            OutlinedButton(
                onClick = { repo.baseUrl = "http://10.0.2.2:8756"; url = repo.baseUrl },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
            ) {
                Text("Emulator-URL setzen (10.0.2.2)", fontSize = 12.sp)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Pairing & Versionen")
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bitmap = qr
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Pairing-QR-Code",
                        modifier = Modifier.width(140.dp).height(140.dp),
                    )
                } else {
                    Text(
                        "URL und Token eintragen – dann erscheint hier der QR-Code.",
                        fontSize = 11.sp,
                        color = TextDim,
                        modifier = Modifier.width(140.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("App ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = TextMain)
                    Text("Bridge $bridgeVersion", fontSize = 12.sp, color = TextMain)
                    Text("Quelle: $bridgeSource", fontSize = 11.sp, color = TextDim)
                    Text(
                        "Der QR-Code enthält URL|Token zum Abgleich auf einem zweiten Gerät.",
                        fontSize = 10.sp,
                        color = TextDim,
                    )
                    OutlinedButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                scanQr.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Bridge-QR-Code scannen")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(true)
                                )
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    ) {
                        Text("QR-Code scannen", fontSize = 11.sp)
                    }
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Label("Speicher & lokaler Cache")
            val st = storage
            if (st != null) {
                Text(
                    "Bridge: ${fmtMb(st.optLong("total_bytes", 0L))} belegt · " +
                        "${st.optInt("sessions", 0)} Sessions · ${st.optInt("laps", 0)} Runden",
                    fontSize = 12.sp,
                    color = TextMain,
                )
                Text(
                    "Davon Samples: ${fmtMb(st.optLong("samples_bytes", 0L))} · " +
                        "Retention ${st.optInt("retention_days", 0).let { if (it <= 0) "aus" else "$it Tage" }}",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            } else {
                Text("Bridge-Speicher nicht erreichbar (URL/Token prüfen).", fontSize = 11.sp, color = TextDim)
            }
            Text(
                "Lokaler Sample-Cache: $cacheCount Runden · ${fmtMb(cacheBytes)}",
                fontSize = 11.sp,
                color = Cyan,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val purged = repo.purgeAllSamples()
                            status = if (purged > 0) "Samples von $purged Sessions auf der Bridge aufgeräumt." else "Keine Bridge-Samples aufgeräumt."
                            refreshStorage()
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Bridge-Samples aufräumen", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) { repo.clearSampleCache() }
                            refreshStorage()
                            status = "$removed lokale Sample-Caches gelöscht."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cache leeren", fontSize = 11.sp)
                }
            }
            Text(
                "Aufräumen löscht nur Telemetrie bereits synchronisierter Sessions. Rundenzeiten und Setups bleiben.",
                fontSize = 10.sp,
                color = TextDim,
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Export")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportCsv.launch("lmu-laptimer-laps.csv") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Runden-CSV", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { exportZip.launch("lmu-laptimer-export.zip") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Vollbackup (ZIP)", fontSize = 11.sp)
                }
            }
            Text(
                "Die ZIP enthält backup.json plus samples/<lap-id>.json für jede lokal gecachte Runde. Über Backup laden wird alles inklusive Samples wiederhergestellt.",
                fontSize = 10.sp,
                color = TextDim,
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Audio-Spotter")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Signaltöne im Live-Betrieb", fontSize = 12.sp, color = TextMain)
                    Text(
                        "Beeps bei Gelb/Rot/Blau, Rundenwechsel, wenig Sprit, Boxenein-/ausfahrt und neuen Strafen.",
                        fontSize = 10.sp,
                        color = TextDim,
                    )
                }
                Switch(
                    checked = audioOn,
                    onCheckedChange = {
                        audioOn = it
                        repo.audioEnabled = it
                        status = if (it) "Audio-Spotter aktiviert." else "Audio-Spotter ausgeschaltet."
                    },
                )
            }
            Text("Lautstärke $audioVolume %", fontSize = 10.sp, color = TextDim)
            Slider(
                value = audioVolume.toFloat(),
                onValueChange = {
                    audioVolume = it.toInt().coerceIn(0, 100)
                    repo.audioVolume = audioVolume
                },
                valueRange = 0f..100f,
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Live-Benachrichtigung")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dauerhafte Statuszeile", fontSize = 12.sp, color = TextMain)
                    Text(
                        "Zeigt Strecke, Flagge, letzte Runde, Position und Sprit – praktisch im Dock oder Browser-Vordergrund.",
                        fontSize = 10.sp,
                        color = TextDim,
                    )
                }
                Switch(
                    checked = notifyOn,
                    onCheckedChange = { enabled ->
                        notifyOn = enabled
                        repo.liveNotifyEnabled = enabled
                        if (enabled) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            status = "Live-Benachrichtigung aktiviert."
                        } else {
                            LiveNotifier.cancel(context)
                            status = "Live-Benachrichtigung ausgeschaltet."
                        }
                    },
                )
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Bridge automatisch finden")
            Text(
                "Sucht per mDNS nach _lmu-laptimer._tcp im lokalen Netz. Der Token wird nie im Netz veröffentlicht und muss hier eingetragen bleiben.",
                fontSize = 10.sp,
                color = TextDim,
            )
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 36 &&
                        ContextCompat.checkSelfPermission(
                            context,
                            "android.permission.ACCESS_LOCAL_NETWORK",
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        localNetworkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
                        discoveryStatus = "Berechtigung für lokales Netzwerk erteilen, dann erneut suchen."
                    } else {
                    scope.launch {
                        discovering = true
                        discoveryStatus = "Suche im lokalen Netz…"
                        val found = withContext(Dispatchers.IO) { discoverBridge(context) }
                        discovering = false
                        if (found != null) {
                            url = found.url
                            repo.baseUrl = found.url
                            discoveryStatus = "Gefunden: ${found.url} – Token prüfen und speichern."
                            status = "Bridge per mDNS gefunden."
                        } else {
                            discoveryStatus = "Keine Bridge gefunden. Läuft die Bridge mit Discovery und sind Handy und PC im selben WLAN?"
                        }
                    }
                    }
                },
                enabled = !discovering,
            ) {
                Text(if (discovering) "Suche…" else "Bridge suchen", fontSize = 12.sp)
            }
            if (discoveryStatus.isNotBlank()) {
                Text(discoveryStatus, fontSize = 11.sp, color = if (discoveryStatus.startsWith("Gefunden")) Lime else Warn)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Hue-Steuerung")
            Text(
                "Die Hue-Konfiguration liegt auf dem Bridge-PC in data/hue.json; der Schlüssel bleibt dort. Die Bridge färbt das Licht nach Flagge.",
                fontSize = 10.sp,
                color = TextDim,
            )
            OutlinedTextField(
                value = hueHost,
                onValueChange = { hueHost = it },
                label = { Text("Hue Bridge IP", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hueUser,
                onValueChange = { hueUser = it },
                label = { Text("Hue Benutzername / Key", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hueLight,
                    onValueChange = { hueLight = it },
                    label = { Text("Licht-ID", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Text("Aktiv", fontSize = 11.sp, color = TextDim)
                Switch(checked = hueEnabled, onCheckedChange = { hueEnabled = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val config = JSONObject()
                                .put("bridge_ip", hueHost.trim())
                                .put("username", hueUser.trim())
                                .put("light", hueLight.trim().ifBlank { "1" })
                                .put("enabled", hueEnabled)
                            val ok = repo.saveHue(config)
                            hueStatus = if (ok) "Konfiguration auf der Bridge gespeichert." else "Bridge nicht erreichbar."
                            busy = false
                        }
                    },
                    enabled = !busy && hueUser.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Bg),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Speichern", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val result = repo.testHue()
                            hueStatus = when {
                                result == null -> "Bridge nicht erreichbar."
                                result.optBoolean("ok", false) -> "Hue-Licht reagiert."
                                else -> "Hue-Test fehlgeschlagen: ${result.optString("error", "unbekannt")}"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Test", fontSize = 12.sp)
                }
            }
            if (hueStatus.isNotBlank()) Text(hueStatus, fontSize = 11.sp, color = TextDim)
            Text(
                "Der Hue-Key wird von der Bridge nicht zurückgelesen. Zum Ändern erneut eintragen; Speichern ist bis dahin gesperrt.",
                fontSize = 10.sp,
                color = TextDim,
            )
        }

    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Demo-Daten endgültig löschen?") },
            text = {
                Text(
                    "${demo.sessions} Sessions mit ${demo.laps} Runden werden aus dem lokalen " +
                        "Archiv entfernt. Echte LMU-Sessions bleiben erhalten.",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) { repo.deleteDemoSessions() }
                            refreshArchive()
                            status = "$deleted Demo-Sessions gelöscht."
                            onSynced()
                        }
                    }
                ) {
                    Text("Endgültig löschen", color = Bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Abbrechen")
                }
            },
        )
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    suffix: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        suffix = { Text(suffix, fontSize = 10.sp, color = TextDim) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun fmtMb(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

private fun makeQr(text: String, size: Int = 480): Bitmap? = try {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(
                x,
                y,
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
            )
        }
    }
    bitmap
} catch (_: Exception) {
    null
}
