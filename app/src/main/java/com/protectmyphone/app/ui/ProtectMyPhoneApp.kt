package com.protectmyphone.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import com.protectmyphone.app.pairing.IncomingPairingRequest
import com.protectmyphone.app.pairing.PairingQrCode
import com.protectmyphone.app.pairing.PairingQrScanner
import com.protectmyphone.app.pairing.PairingFingerprintConfirmation

private val CanvasBackground = Color(0xFFF5F6F2)
private val Ink = Color(0xFF172A2B)
private val MutedInk = Color(0xFF687877)
private val Forest = Color(0xFF173F3B)
private val ForestLight = Color(0xFF245B53)
private val Mint = Color(0xFFD9F2E3)
private val Lime = Color(0xFFD6F179)
private val CardWhite = Color(0xFFFFFFFF)
private val Amber = Color(0xFFE5A442)
private val Red = Color(0xFFB9493E)

/** A display model only. Connect these callbacks to the Android/platform layer. */
data class ProtectedDeviceUi(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val batteryPercent: Int?,
    val networkName: String,
    val wifiName: String?,
    val lastLocation: String,
    val lastSeen: String,
    val isOnline: Boolean,
    val isCurrentPhone: Boolean = false,
    val liveLocationEnabled: Boolean = false,
    val updateIntervalMinutes: Int = 15,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationUpdatedAt: String = "No location reported yet",
    val soundStatus: String = "No sound command sent",
    val lockMessageStatus: String = "No lock-screen message command sent",
    val isDemo: Boolean = false,
)

data class DeviceLocationPin(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: String,
    val isThisPhone: Boolean,
)

enum class DeviceCommand { STATUS, LOCATE, LOCK, SOUND, STOP_SOUND }
enum class UiActionResult { QUEUED, PAIRED, REMOVED, NOT_READY, FAILED }

data class ProtectUiActions(
    val onCommand: (deviceId: String, command: DeviceCommand) -> UiActionResult = { _, _ -> UiActionResult.NOT_READY },
    val onLiveLocationChanged: (deviceId: String, enabled: Boolean, intervalMinutes: Int) -> UiActionResult = { _, _, _ -> UiActionResult.NOT_READY },
    val onShareLocationLink: (deviceId: String, mapUrl: String) -> Unit = { _, _ -> },
    val onOpenLocationInMaps: (mapUrl: String) -> Unit = {},
    val onRefreshThisPhoneLocation: () -> Unit = {},
    val locationPins: List<DeviceLocationPin> = emptyList(),
    val onPairDevice: (name: String, phoneNumber: String) -> UiActionResult = { _, _ -> UiActionResult.NOT_READY },
    val pendingPairingRequests: List<IncomingPairingRequest> = emptyList(),
    val pendingFingerprintConfirmations: List<PairingFingerprintConfirmation> = emptyList(),
    val onAcceptPairing: (sessionId: String) -> UiActionResult = { UiActionResult.NOT_READY },
    val onConfirmPairing: (sessionId: String, localFingerprint: String, peerFingerprint: String) -> UiActionResult = { _, _, _ -> UiActionResult.NOT_READY },
    val onCancelPairing: (sessionId: String) -> Unit = {},
    val onSetLockScreenMessage: (deviceId: String, message: String) -> UiActionResult = { _, _ -> UiActionResult.NOT_READY },
    val onRemovePairing: (deviceId: String) -> UiActionResult = { UiActionResult.NOT_READY },
    val onRequestSetup: () -> Unit = {},
    val onEnableLocationAccess: () -> Unit = {},
    val onEnableRemoteLock: () -> Unit = {},
    val remoteLockEnabled: Boolean = false,
    val onExitApp: () -> Unit = {},
)

/** Main product UI, intentionally independent of SMS, location, and device-admin implementations. */
@Composable
fun ProtectMyPhoneApp(
    modifier: Modifier = Modifier,
    devices: List<ProtectedDeviceUi> = demoProtectedDevices(),
    actions: ProtectUiActions = ProtectUiActions(),
) {
    var page by remember { mutableStateOf(AppPage.HOME) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var selectedDeviceId by remember(devices) { mutableStateOf(devices.firstOrNull()?.id.orEmpty()) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId } ?: devices.firstOrNull()

    BackHandler {
        if (showExitConfirmation) {
            showExitConfirmation = false
        } else {
            when (page) {
                AppPage.HOME -> showExitConfirmation = true
                AppPage.DEVICE_DETAIL, AppPage.ADD_DEVICE -> page = AppPage.DEVICES
                AppPage.SETUP -> page = AppPage.SETTINGS
                AppPage.DEVICES, AppPage.ACTIVITY, AppPage.SETTINGS -> page = AppPage.HOME
            }
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest,
            onPrimary = Color.White,
            secondary = ForestLight,
            onSecondary = Color.White,
            tertiary = Color(0xFF748C59),
            background = CanvasBackground,
            surface = CardWhite,
            onSurface = Ink,
            surfaceVariant = Color(0xFFE9EEE6),
            onSurfaceVariant = MutedInk,
            error = Red,
        ),
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = CanvasBackground,
            snackbarHost = { SnackbarHost(snackbarHost) },
            bottomBar = {
                if (page.isPrimaryDestination()) {
                    BottomNavigationBar(current = page, onSelect = { page = it })
                }
            },
        ) { inset ->
            when (page) {
                AppPage.HOME -> HomePage(
                    devices = devices,
                    contentPadding = inset,
                    onOpenDevice = { id -> selectedDeviceId = id; page = AppPage.DEVICE_DETAIL },
                    onAddDevice = { page = AppPage.ADD_DEVICE },
                    onLocate = { id ->
                        val result = actions.onCommand(id, DeviceCommand.LOCATE)
                        scope.launch { snackbarHost.showSnackbar(result.message("Location request")) }
                    },
                    onOpenSetup = { page = AppPage.SETUP },
                )
                AppPage.DEVICES -> DeviceListPage(
                    devices = devices,
                    contentPadding = inset,
                    onOpenDevice = { id -> selectedDeviceId = id; page = AppPage.DEVICE_DETAIL },
                    onLocate = { id ->
                        val result = actions.onCommand(id, DeviceCommand.LOCATE)
                        scope.launch { snackbarHost.showSnackbar(result.message("Location request")) }
                    },
                    onAddDevice = { page = AppPage.ADD_DEVICE },
                )
                AppPage.ACTIVITY -> ActivityPage(contentPadding = inset)
                AppPage.SETTINGS -> SettingsPage(
                    contentPadding = inset,
                    onOpenSetup = { page = AppPage.SETUP },
                    onOpenDevices = { page = AppPage.DEVICES },
                )
                AppPage.DEVICE_DETAIL -> selectedDevice?.let { device ->
                    DeviceDetailPage(
                        device = device,
                        contentPadding = inset,
                        onBack = { page = AppPage.DEVICES },
                        onCommand = { command ->
                            val result = actions.onCommand(device.id, command)
                            scope.launch { snackbarHost.showSnackbar(result.message(command.label())) }
                        },
                        onLiveLocationChanged = { enabled, interval ->
                            val result = actions.onLiveLocationChanged(device.id, enabled, interval)
                            scope.launch { snackbarHost.showSnackbar(result.message(if (enabled) "Live location" else "Live location updates")) }
                            result
                        },
                        onSetLockScreenMessage = { message ->
                            val result = actions.onSetLockScreenMessage(device.id, message)
                            scope.launch { snackbarHost.showSnackbar(result.message("Lock screen message")) }
                        },
                        onShareLocationLink = { mapUrl -> actions.onShareLocationLink(device.id, mapUrl) },
                        onOpenLocationInMaps = actions.onOpenLocationInMaps,
                        onRefreshThisPhoneLocation = actions.onRefreshThisPhoneLocation,
                        locationPins = actions.locationPins,
                        onRemovePairing = {
                            val result = actions.onRemovePairing(device.id)
                            if (result == UiActionResult.REMOVED) page = AppPage.DEVICES
                            scope.launch { snackbarHost.showSnackbar(result.message("Pairing removal")) }
                        },
                    )
                } ?: EmptyDevicesPage(contentPadding = inset, onAddDevice = { page = AppPage.ADD_DEVICE })
                AppPage.ADD_DEVICE -> AddDevicePage(
                    contentPadding = inset,
                    onBack = { page = AppPage.DEVICES },
                    pendingRequests = actions.pendingPairingRequests,
                    fingerprintConfirmations = actions.pendingFingerprintConfirmations,
                    onAcceptPairing = { sessionId ->
                        val result = actions.onAcceptPairing(sessionId)
                        scope.launch { snackbarHost.showSnackbar(result.message("Pairing response")) }
                    },
                    onConfirmPairing = { sessionId, localFingerprint, peerFingerprint ->
                        val result = actions.onConfirmPairing(sessionId, localFingerprint, peerFingerprint)
                        scope.launch { snackbarHost.showSnackbar(result.message("Pairing confirmation")) }
                    },
                    onCancelPairing = actions.onCancelPairing,
                    onPair = { name, phone ->
                    val result = actions.onPairDevice(name, phone)
                    scope.launch { snackbarHost.showSnackbar(result.message("Pairing request")) }
                    },
                )
                AppPage.SETUP -> SetupPage(
                    contentPadding = inset,
                    onBack = { page = AppPage.SETTINGS },
                    onContinue = actions.onRequestSetup,
                    onEnableLocationAccess = actions.onEnableLocationAccess,
                    onEnableRemoteLock = actions.onEnableRemoteLock,
                    remoteLockEnabled = actions.remoteLockEnabled,
                )
            }
        }
        if (showExitConfirmation) {
            AlertDialog(
                onDismissRequest = { showExitConfirmation = false },
                title = { Text("Exit Protect My Phone?") },
                text = { Text("Your protection settings stay saved. You can open the app again any time.") },
                confirmButton = {
                    TextButton(onClick = {
                        showExitConfirmation = false
                        actions.onExitApp()
                    }) { Text("Exit") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirmation = false }) { Text("Stay") }
                },
            )
        }
    }
}

private enum class AppPage {
    HOME, DEVICES, ACTIVITY, SETTINGS, DEVICE_DETAIL, ADD_DEVICE, SETUP;

    fun isPrimaryDestination() = this == HOME || this == DEVICES || this == ACTIVITY || this == SETTINGS
}

@Composable
private fun HomePage(
    devices: List<ProtectedDeviceUi>,
    contentPadding: PaddingValues,
    onOpenDevice: (String) -> Unit,
    onAddDevice: () -> Unit,
    onLocate: (String) -> Unit,
    onOpenSetup: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { HomeHeader(onOpenSetup) }
        item { ProtectionHero(onlineCount = devices.count { it.isOnline }, totalCount = devices.size, isDemo = devices.any { it.isDemo }) }
        item {
            SectionHeading(
                title = "Your devices",
                trailing = if (devices.any { it.isDemo }) "${devices.size} examples" else "${devices.size} linked",
                action = onAddDevice,
                actionLabel = "+ Add",
            )
        }
        if (devices.isEmpty()) {
            item { EmptyDevicesCard(onAddDevice) }
        } else {
            items(devices, key = { it.id }) { device ->
                DeviceSummaryCard(
                    device = device,
                    onClick = { onOpenDevice(device.id) },
                    onLocate = { onLocate(device.id) },
                )
            }
        }
        item { HowItWorksNote() }
    }
}

@Composable
private fun HomeHeader(onOpenSetup: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Protect My Phone", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .1.sp)
            Text("SMS anti-theft", color = MutedInk, fontSize = 12.sp)
        }
        Surface(
            modifier = Modifier.size(42.dp).clickable(onClick = onOpenSetup),
            shape = CircleShape,
            color = CardWhite,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("···", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)).background(Forest),
        contentAlignment = Alignment.Center,
    ) {
        Text("P", color = Lime, fontSize = 21.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ProtectionHero(onlineCount: Int, totalCount: Int, isDemo: Boolean) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Forest),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(Color(0xFF24534B).copy(alpha = .7f), radius = size.width * .32f, center = Offset(size.width * .97f, size.height * .10f))
                drawCircle(Color(0xFF2E6257).copy(alpha = .45f), radius = size.width * .19f, center = Offset(size.width * .98f, size.height * .13f))
            }
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(color = Lime)
                    Text(when { isDemo -> "SAMPLE DATA"; totalCount == 0 -> "PAIR A PHONE TO BEGIN"; else -> "SMS DEVICE NETWORK" }, color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                }
                Spacer(Modifier.height(14.dp))
                Text("Keep your phones\nwithin reach.", color = Color.White, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                Text(
                    if (isDemo) "Preview only · pair your own phone to begin"
                    else if (totalCount == 0) "Pair a trusted phone to get started."
                    else "$onlineCount of $totalCount phones recently reachable",
                    color = Color(0xFFC6D9D2), fontSize = 13.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = .12f)) {
                        Text("SMS only  ·  No account", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("⌁", color = Lime, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, trailing: String? = null, action: (() -> Unit)? = null, actionLabel: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, color = MutedInk, fontSize = 12.sp)
        }
        if (action != null && actionLabel != null) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)) {
                Text(actionLabel, color = ForestLight, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DeviceSummaryCard(device: ProtectedDeviceUi, onClick: () -> Unit, onLocate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceGlyph(isCurrent = device.isCurrentPhone)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(device.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (device.isDemo) TinyTag("SAMPLE", background = Color(0xFFFFEBC9), foreground = Color(0xFF7A551E))
                        else if (device.isCurrentPhone) TinyTag("THIS PHONE", background = Mint, foreground = Forest)
                    }
                    Text(device.phoneNumber, color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        StatusDot(if (device.isOnline) Color(0xFF38A878) else Amber, size = 7.dp)
                        Text(if (device.isOnline) "Reachable" else "Waiting", color = if (device.isOnline) ForestLight else MutedInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(device.batteryPercent?.let { "$it% battery" } ?: "Battery unknown", color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFF3F6F2)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    LocationGlyph()
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.lastLocation, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Updated ${device.lastSeen}", color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    TextButton(onClick = onLocate, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Locate", color = ForestLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceGlyph(isCurrent: Boolean = false) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(if (isCurrent) Color(0xFFE9F0E5) else Color(0xFFF2F3EE)),
        contentAlignment = Alignment.Center,
    ) {
        PhoneGlyph(Modifier.size(24.dp))
    }
}

@Composable
private fun PhoneGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        drawRoundRect(
            color = Forest,
            topLeft = Offset(size.width * .22f, size.height * .04f),
            size = Size(size.width * .56f, size.height * .92f),
            cornerRadius = CornerRadius(size.width * .1f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = Forest,
            start = Offset(size.width * .38f, size.height * .16f),
            end = Offset(size.width * .62f, size.height * .16f),
            strokeWidth = stroke,
        )
        drawCircle(color = Forest, radius = stroke * .65f, center = Offset(size.width * .5f, size.height * .85f))
    }
}

@Composable
private fun LocationGlyph() {
    Box(Modifier.size(27.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
        Text("⌖", color = Forest, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
private fun TinyTag(text: String, background: Color, foreground: Color) {
    Surface(shape = RoundedCornerShape(30.dp), color = background) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = foreground, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .4.sp)
    }
}

@Composable
private fun HowItWorksNote() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFE9EEE6)).padding(15.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("i", color = Forest, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp, top = 1.dp))
        Text("Commands travel by SMS. A phone needs power, a working SIM, and cellular coverage to receive them.", color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun DeviceListPage(
    devices: List<ProtectedDeviceUi>,
    contentPadding: PaddingValues,
    onOpenDevice: (String) -> Unit,
    onLocate: (String) -> Unit,
    onAddDevice: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = contentPadding.calculateTopPadding() + 18.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            PageHeading(
                eyebrow = if (devices.any { it.isDemo }) "SAMPLE DEVICES" else "CONNECTED PHONES",
                title = "Your devices",
                subtitle = if (devices.any { it.isDemo }) "Examples only · pair your own phone to replace these." else "Manage every phone paired to this one.",
            )
        }
        if (devices.isEmpty()) item { EmptyDevicesCard(onAddDevice) }
        items(devices, key = { it.id }) { device ->
            DeviceSummaryCard(device, onClick = { onOpenDevice(device.id) }, onLocate = { onLocate(device.id) })
        }
        item {
            OutlinedButton(
                onClick = onAddDevice,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Forest),
            ) { Text("+  Pair another phone", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun DeviceDetailPage(
    device: ProtectedDeviceUi,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onCommand: (DeviceCommand) -> Unit,
    onLiveLocationChanged: (Boolean, Int) -> UiActionResult,
    onSetLockScreenMessage: (String) -> Unit,
    onShareLocationLink: (String) -> Unit,
    onOpenLocationInMaps: (String) -> Unit,
    onRefreshThisPhoneLocation: () -> Unit,
    locationPins: List<DeviceLocationPin>,
    onRemovePairing: () -> Unit,
) {
    var requestedTracking by remember(device.id) { mutableStateOf<Boolean?>(null) }
    var showRemovePairingDialog by remember(device.id) { mutableStateOf(false) }
    var interval by remember(device.id, device.updateIntervalMinutes) { mutableStateOf(device.updateIntervalMinutes.coerceIn(5, 60)) }
    val intervalMinutes = interval
    val tracking = requestedTracking ?: device.liveLocationEnabled
    val trackingPending = requestedTracking != null && requestedTracking != device.liveLocationEnabled
    LaunchedEffect(device.liveLocationEnabled, requestedTracking) {
        if (requestedTracking == device.liveLocationEnabled) requestedTracking = null
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 26.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item { BackHeader(title = "Device details", onBack = onBack) }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.name, color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    if (device.isDemo) TinyTag("SAMPLE", background = Color(0xFFFFEBC9), foreground = Color(0xFF7A551E))
                }
                Text("${device.phoneNumber}  ·  ${if (device.isOnline) "Recently reachable" else "Last seen ${device.lastSeen}"}", color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item { LocationCard(device, locationPins, onShareLocationLink, onOpenLocationInMaps, onRefreshThisPhoneLocation) }
        item { QuickActionsCard(device.soundStatus, onCommand) }
        item { LockScreenMessageCard(device.lockMessageStatus, onSend = onSetLockScreenMessage) }
        item {
            LiveLocationCard(
                enabled = tracking,
                pending = trackingPending,
                intervalMinutes = intervalMinutes,
                onToggle = { checked ->
                    if (onLiveLocationChanged(checked, intervalMinutes) == UiActionResult.QUEUED) requestedTracking = checked
                },
                onIntervalChange = { value ->
                    if (!tracking || onLiveLocationChanged(true, value) == UiActionResult.QUEUED) interval = value
                },
            )
        }
        item { DeviceStatusCard(device, onRefresh = { onCommand(DeviceCommand.STATUS) }) }
        if (!device.isDemo) {
            item {
                OutlinedButton(
                    onClick = { showRemovePairingDialog = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                ) { Text("Remove pairing", fontWeight = FontWeight.SemiBold) }
            }
        }
        item { SmsReliabilityNote() }
    }
    if (showRemovePairingDialog) {
        AlertDialog(
            onDismissRequest = { showRemovePairingDialog = false },
            title = { Text("Remove ${device.name}?") },
            text = { Text("This removes the trusted key and this phone’s entry from this device. It does not remove the pairing from the other phone; remove it there too if you want to revoke trust on both sides.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemovePairingDialog = false
                    onRemovePairing()
                }) { Text("Remove pairing", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePairingDialog = false }) { Text("Keep pairing") }
            },
        )
    }
}

@Composable
private fun LocationCard(
    device: ProtectedDeviceUi,
    locationPins: List<DeviceLocationPin>,
    onShareLocationLink: (String) -> Unit,
    onOpenLocationInMaps: (String) -> Unit,
    onRefreshThisPhoneLocation: () -> Unit,
) {
    Card(shape = RoundedCornerShape(25.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column {
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp))) {
                FamilyLocationMap(locationPins, Modifier.fillMaxSize())
                Surface(
                    Modifier.align(Alignment.TopStart).padding(13.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = Color.White.copy(alpha = .95f),
                ) { Text("LAST KNOWN LOCATION", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = Forest, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.lastLocation, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Last location · ${device.locationUpdatedAt}", color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                val latitude = device.latitude
                val longitude = device.longitude
                if (latitude != null && longitude != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { onOpenLocationInMaps("https://maps.google.com/?q=$latitude,$longitude") }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Open in Maps", color = ForestLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { onShareLocationLink("https://maps.google.com/?q=$latitude,$longitude") }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Share pin", color = ForestLight, fontSize = 10.sp)
                        }
                    }
                }
            }
            if (locationPins.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    locationPins.take(4).forEach { pin ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(if (pin.isThisPhone) Color(0xFFB9493E) else Forest))
                            Text(pin.name, Modifier.weight(1f).padding(start = 8.dp), color = Ink, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pin.updatedAt, color = MutedInk, fontSize = 9.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Online map tiles can reveal the viewed area · location commands use SMS", Modifier.weight(1f), color = MutedInk, fontSize = 9.sp, lineHeight = 12.sp)
                TextButton(onClick = onRefreshThisPhoneLocation, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("Locate this phone", color = ForestLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("Adds the phone you’re holding to this map.", color = MutedInk, fontSize = 9.sp, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
            Text("© OpenStreetMap contributors · OpenMapTiles", color = MutedInk, fontSize = 8.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        }
    }
}

@Composable
private fun QuickActionsCard(soundStatus: String, onCommand: (DeviceCommand) -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column(Modifier.padding(17.dp)) {
            Text("Quick actions", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Commands are sent to this phone by SMS.", color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, bottom = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                QuickActionTile("⌖", "Locate", Color(0xFFEAF3E9), onClick = { onCommand(DeviceCommand.LOCATE) }, modifier = Modifier.weight(1f))
                QuickActionTile("▣", "Lock", Color(0xFFF1EEE6), onClick = { onCommand(DeviceCommand.LOCK) }, modifier = Modifier.weight(1f))
                QuickActionTile("◖", "Sound now", Color(0xFFF8ECE6), onClick = { onCommand(DeviceCommand.SOUND) }, modifier = Modifier.weight(1f))
                QuickActionTile("⌁", "Status", Color(0xFFEBEEF6), onClick = { onCommand(DeviceCommand.STATUS) }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { onCommand(DeviceCommand.STOP_SOUND) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Forest),
            ) { Text("Stop sound", fontWeight = FontWeight.SemiBold) }
            Text(soundStatus, color = MutedInk, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun LockScreenMessageCard(status: String, onSend: (String) -> Unit) {
    var message by remember { mutableStateOf("This phone is protected. Please call the owner.") }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Lock screen message", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Requires fully managed Device Owner provisioning. Device Admin activation by itself cannot set lock-screen owner information.", color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp)
            OutlinedTextField(value = message, onValueChange = { message = it.take(140) }, label = { Text("Recovery message") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp))
            Button(onClick = { onSend(message) }, enabled = message.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
                Text("Set message on phone", fontWeight = FontWeight.SemiBold)
            }
            Text(status, color = MutedInk, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun QuickActionTile(symbol: String, label: String, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(17.dp)).background(tint).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(symbol, color = Forest, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun LiveLocationCard(
    enabled: Boolean,
    pending: Boolean,
    intervalMinutes: Int,
    onToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) Mint else Color(0xFFF1F3EF)), contentAlignment = Alignment.Center) {
                    Text("↗", color = Forest, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live location updates", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            pending -> "SMS queued · waiting for phone reply"
                            enabled -> "On · checks about every $intervalMinutes min"
                            else -> "Off · location is only sent on request"
                        },
                        color = MutedInk,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) {
                Spacer(Modifier.height(15.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("UPDATE PACE", color = MutedInk, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                    Spacer(Modifier.weight(1f))
                    TinyTag("${intervalMinutes} MIN", background = Mint, foreground = Forest)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(5, 15, 30, 60).forEach { choice ->
                        IntervalChoice(minutes = choice, selected = intervalMinutes == choice, onClick = { onIntervalChange(choice) }, modifier = Modifier.weight(1f))
                    }
                }
            Text("This phone checks its own location and sends an SMS after it moves about 50 m. The controlling phone does not poll. Faster checks can use more battery and SMS.", color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp)
            } else {
                Spacer(Modifier.height(13.dp))
                Text("Off · only a one-time location request can update this device’s saved location. Turn on updates when you want this phone to report movement by SMS.", color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun IntervalChoice(minutes: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Forest else Color(0xFFF2F5F0),
    ) {
        Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text("${minutes}m", color = if (selected) Color.White else MutedInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DeviceStatusCard(device: ProtectedDeviceUi, onRefresh: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last reported status", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Refresh", color = Forest, fontSize = 11.sp) }
            }
            StatusRow("Battery", device.batteryPercent?.let { "$it%" } ?: "Unknown", "▰", (device.batteryPercent ?: 0) > 20)
            StatusRow("Mobile network", device.networkName, "◉", device.isOnline)
            StatusRow("Wi-Fi", device.wifiName ?: "Not connected", "⌁", !device.wifiName.isNullOrBlank())
            StatusRow("Last response", device.lastSeen, "◷", device.isOnline)
            Text(
                if (device.isDemo) "Example status only; no phone has reported these values."
                else "One status SMS returns battery, cellular, Wi-Fi and the phone’s last known location when available.",
                color = MutedInk,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, symbol: String, positive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(31.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFF2F5F0)), contentAlignment = Alignment.Center) {
            Text(symbol, color = Forest, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = MutedInk, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (positive) Ink else MutedInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmsReliabilityNote() {
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFFF1EEE6)).padding(14.dp), verticalAlignment = Alignment.Top) {
        Text("!", color = Color(0xFF8C6222), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(end = 9.dp))
        Text("SMS cannot reach a phone that is off, has no cellular service, or no longer has its SIM. The location shown may be old.", color = Color(0xFF715E3E), fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun AddDevicePage(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    pendingRequests: List<IncomingPairingRequest>,
    fingerprintConfirmations: List<PairingFingerprintConfirmation>,
    onAcceptPairing: (String) -> Unit,
    onConfirmPairing: (String, String, String) -> Unit,
    onCancelPairing: (String) -> Unit,
    onPair: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var agree by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BackHeader("Pair a phone", onBack)
        Column {
            Text("Pair with a\ntrusted phone.", color = Ink, fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
            Text("Both phones exchange a one-time pairing code by SMS. Your keys stay on your devices.", color = MutedInk, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 9.dp))
        }
        if (pendingRequests.isNotEmpty()) {
            Text("PAIRING REQUESTS", color = ForestLight, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            pendingRequests.forEach { request -> PendingPairingCard(request, onAcceptPairing, onCancelPairing) }
        }
        if (fingerprintConfirmations.isNotEmpty()) {
            Text("VERIFY BOTH PHONES", color = ForestLight, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            fingerprintConfirmations.forEach { confirmation -> FingerprintConfirmationCard(confirmation, onConfirmPairing, onCancelPairing) }
        }
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Phone name") }, placeholder = { Text("e.g. Mum’s phone") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Phone number") }, placeholder = { Text("Include country code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agree, onCheckedChange = { agree = it })
                    Text("I own this phone or have permission to protect it.", color = MutedInk, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Button(
                    onClick = { onPair(name.ifBlank { "Protected phone" }, number.trim()) },
                    enabled = number.trim().length >= 7 && agree,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White),
                ) { Text("Send secure pairing SMS", fontWeight = FontWeight.SemiBold) }
            }
        }
        StepsCard()
        SmsReliabilityNote()
    }
}

@Composable
private fun PendingPairingCard(
    request: IncomingPairingRequest,
    onAccept: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(request.peerLabel, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(request.peerPhoneNumber, color = MutedInk, fontSize = 12.sp)
            Text("Only accept if you recognize this person and expected the pairing request.", color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onAccept(request.sessionId) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
                    Text("Accept & reply", fontSize = 12.sp)
                }
                TextButton(onClick = { onCancel(request.sessionId) }) { Text("Decline", color = Red) }
            }
        }
    }
}

@Composable
private fun FingerprintConfirmationCard(
    confirmation: PairingFingerprintConfirmation,
    onConfirm: (String, String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    var peerFingerprint by remember(confirmation.sessionId) { mutableStateOf("") }
    var explicitlyCompared by remember(confirmation.sessionId) { mutableStateOf(false) }
    var showManualEntry by remember(confirmation.sessionId) { mutableStateOf(false) }
    var showScanner by remember(confirmation.sessionId) { mutableStateOf(false) }
    var scanMessage by remember(confirmation.sessionId) { mutableStateOf<String?>(null) }
    val localQr = remember(confirmation.fullFingerprint) { PairingQrCode.createBitmap(confirmation.fullFingerprint) }
    val cameraPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scanMessage = null
            showScanner = true
        } else {
            scanMessage = "Camera access is needed to scan. You can enter the fingerprint manually instead."
        }
    }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Confirm ${confirmation.peerLabel}", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(confirmation.peerPhoneNumber, color = MutedInk, fontSize = 12.sp)
            Text("Show this QR to the other phone", color = MutedInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = localQr.asImageBitmap(),
                    contentDescription = "Pairing fingerprint QR for ${confirmation.peerLabel}",
                    modifier = Modifier.size(216.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(8.dp),
                )
            }
            Text("Then scan the QR displayed on that phone. The QR contains only the verification fingerprint, never the pairing key.", color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp)
            OutlinedButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        showScanner = true
                        scanMessage = null
                    } else {
                        cameraPermissionRequest.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(15.dp),
            ) { Text("Scan other phone’s QR") }
            scanMessage?.let { Text(it, color = if (peerFingerprint.equals(confirmation.fullFingerprint, ignoreCase = true)) ForestLight else Red, fontSize = 11.sp, lineHeight = 15.sp) }
            if (showManualEntry) {
                Text("You can compare the full fingerprint in person if scanning isn’t available.", color = MutedInk, fontSize = 11.sp, lineHeight = 15.sp)
                OutlinedTextField(
                    value = peerFingerprint,
                    onValueChange = {
                        peerFingerprint = it.filter(Char::isLetterOrDigit).uppercase().take(64)
                        explicitlyCompared = false
                        scanMessage = null
                    },
                    label = { Text("Other phone’s fingerprint") },
                    placeholder = { Text("64 characters") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp),
                )
            } else {
                TextButton(onClick = { showManualEntry = true }) { Text("Enter fingerprint manually", color = ForestLight) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = explicitlyCompared, onCheckedChange = { explicitlyCompared = it })
                Text("I verified this QR in person and want to trust this phone.", color = MutedInk, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Button(
                onClick = { onConfirm(confirmation.sessionId, confirmation.fullFingerprint, peerFingerprint) },
                enabled = explicitlyCompared && peerFingerprint.equals(confirmation.fullFingerprint, ignoreCase = true),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
            ) { Text("Confirm trusted phone", fontWeight = FontWeight.SemiBold) }
            TextButton(
                onClick = { onRemove(confirmation.sessionId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Remove this pending QR", color = Red) }
        }
    }
    if (showScanner) {
        PairingQrScanner(
            onQrDetected = { rawValue ->
                val scannedFingerprint = PairingQrCode.parse(rawValue)
                when {
                    scannedFingerprint == null -> {
                        scanMessage = "That QR isn’t a Protect My Phone fingerprint. Try the QR shown by the other phone."
                        showScanner = false
                        true
                    }
                    !scannedFingerprint.equals(confirmation.fullFingerprint, ignoreCase = true) -> {
                        scanMessage = "The QR doesn’t match this pairing. Stop and verify the phones before continuing."
                        showScanner = false
                        true
                    }
                    else -> {
                        peerFingerprint = scannedFingerprint
                        explicitlyCompared = true
                        scanMessage = "QR matches. Review the pairing, then confirm to trust this phone."
                        showScanner = false
                        true
                    }
                }
            },
            onClose = { showScanner = false },
        )
    }
}

@Composable
private fun StepsCard() {
    Card(shape = RoundedCornerShape(23.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE9F0E8))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How pairing works", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            StepLine("01", "A time-limited SMS invites the other phone.")
            StepLine("02", "Scan the matching fingerprint QR in person on both phones.")
            StepLine("03", "Commands are accepted only from paired devices.")
        }
    }
}

@Composable
private fun StepLine(number: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = ForestLight, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(28.dp))
        Text(label, color = MutedInk, fontSize = 12.sp)
    }
}

@Composable
private fun SetupPage(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onEnableLocationAccess: () -> Unit,
    onEnableRemoteLock: () -> Unit,
    remoteLockEnabled: Boolean,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackHeader("Protection setup", onBack)
        PageHeading(eyebrow = "BEFORE YOU NEED IT", title = "Set up protection", subtitle = "Choose the access this phone needs. We explain why before Android asks.")
        PermissionInfoCard("SMS access", "Receives authenticated commands and sends replies. Android may require an approved SMS role; Play Store rules can limit SMS access.", "Needed for SMS control", Mint)
        PermissionInfoCard("Location access", "Lets this phone answer remote SMS location requests. Allow all-time access for replies while idle and change-based updates.", "Optional · location", Color(0xFFE7F0EC))
        OutlinedButton(
            onClick = onEnableLocationAccess,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(17.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Forest),
        ) { Text("Enable location features", fontWeight = FontWeight.SemiBold) }
        PermissionInfoCard(
            "Device admin",
            "Allows remote screen lock. You can remove this access from Android settings at any time.",
            if (remoteLockEnabled) "Enabled" else "Optional · remote lock",
            if (remoteLockEnabled) Mint else Color(0xFFF1EEE6),
        )
        OutlinedButton(
            onClick = onEnableRemoteLock,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(17.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Forest),
        ) { Text(if (remoteLockEnabled) "Remote screen lock enabled ✓" else "Enable remote screen lock", fontWeight = FontWeight.SemiBold) }
        ManagedModeCard()
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
            Text("Review Android permissions", fontWeight = FontWeight.SemiBold)
        }
        Text("No app can reliably run after a force-stop or power itself back on after shutdown. Reboot startup also depends on Android settings and device state.", color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun PermissionInfoCard(title: String, description: String, tag: String, tint: Color) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(tint), contentAlignment = Alignment.Center) {
                Text(when (title) { "SMS access" -> "↔"; "Location access" -> "⌖"; else -> "▣" }, color = Forest, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(description, color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 5.dp))
                TinyTag(tag, tint, Forest)
            }
        }
    }
}

@Composable
private fun ManagedModeCard() {
    Card(shape = RoundedCornerShape(23.dp), colors = CardDefaults.cardColors(containerColor = Forest)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced controls", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TinyTag("MANAGED MODE", background = Lime, foreground = Forest)
            }
            Text("Wi-Fi controls and hiding the power menu may require a fully managed, owner-enrolled Android phone. Ordinary apps cannot reliably disable mobile data or stop a physical shutdown.", color = Color(0xFFD1DFD8), fontSize = 11.sp, lineHeight = 16.sp)
            Text("This mode is intended for devices you own and set up from a fresh reset.", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ActivityPage(contentPadding: PaddingValues) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = contentPadding.calculateTopPadding() + 18.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item { PageHeading("ACTIVITY", "A clear history.", "Verified SMS actions and replies will appear here after pairing is connected.") }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nothing here yet", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Pair a phone to start a local history of requests and responses. No sample activity is shown as real.", color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        item { HowItWorksNote() }
    }
}

@Composable
private fun ActivityEntry(title: String, detail: String, symbol: String, tint: Color) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(tint), contentAlignment = Alignment.Center) { Text(symbol, color = Forest, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text("›", color = MutedInk, fontSize = 22.sp)
        }
    }
}

@Composable
private fun SettingsPage(contentPadding: PaddingValues, onOpenSetup: () -> Unit, onOpenDevices: () -> Unit) {
    var infoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = contentPadding.calculateTopPadding() + 18.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item { PageHeading("PROTECT MY PHONE", "Your protection.", "SMS anti-theft · simple controls stored on this phone.") }
        item {
            SettingsRow("Permissions & setup", "Review SMS, location and device admin", "›", onOpenSetup)
        }
        item {
            SettingsRow("Privacy", "No account, cloud dashboard or location server", "›") {
                infoDialog = "Your privacy" to "Protect My Phone has no account or location server. Pairing keys stay on this phone; SMS content and phone numbers pass through your mobile carrier."
            }
        }
        item { SettingsRow("Trusted contacts", "Manage which paired phones can send commands", "›", onOpenDevices) }
        item {
            SettingsRow("Power use", "Updates only when requested or enabled", "›") {
                infoDialog = "Battery use" to "Location is requested only when you ask for it, unless you explicitly enable change-based updates for a paired phone. Android may still delay background work under battery restrictions."
            }
        }
        item {
            Card(shape = RoundedCornerShape(23.dp), colors = CardDefaults.cardColors(containerColor = Forest)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Built for the moment you need it.", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Keep this app installed, its permissions enabled, and the protected phone’s SIM active.", color = Color(0xFFD1DFD8), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
    }
    infoDialog?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            title = { Text(title, color = Ink, fontWeight = FontWeight.Bold) },
            text = { Text(body, color = MutedInk) },
            confirmButton = { TextButton(onClick = { infoDialog = null }) { Text("Done", color = Forest) } },
            containerColor = CardWhite,
        )
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, trailing: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text(trailing, color = ForestLight, fontSize = 23.sp)
        }
    }
}

@Composable
private fun PageHeading(eyebrow: String, title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
        Text(eyebrow, color = ForestLight, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text(title, color = Ink, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
        Text(subtitle, color = MutedInk, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(40.dp).clickable(onClick = onBack), shape = CircleShape, color = CardWhite) {
            Box(contentAlignment = Alignment.Center) { Text("‹", color = Ink, fontSize = 28.sp, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BottomNavigationBar(current: AppPage, onSelect: (AppPage) -> Unit) {
    Surface(modifier = Modifier.navigationBarsPadding(), color = CardWhite, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            NavItem(Icons.Filled.Home, "Home", current == AppPage.HOME) { onSelect(AppPage.HOME) }
            NavItem(Icons.Filled.Devices, "Devices", current == AppPage.DEVICES) { onSelect(AppPage.DEVICES) }
            NavItem(Icons.Filled.History, "Activity", current == AppPage.ACTIVITY) { onSelect(AppPage.ACTIVITY) }
            NavItem(Icons.Filled.Settings, "Settings", current == AppPage.SETTINGS) { onSelect(AppPage.SETTINGS) }
        }
    }
}

@Composable
private fun NavItem(image: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(50), color = if (selected) Mint else Color.Transparent) {
            Icon(
                imageVector = image,
                contentDescription = null,
                tint = if (selected) Forest else MutedInk,
                modifier = Modifier.padding(horizontal = 17.dp, vertical = 4.dp).size(20.dp),
            )
        }
        Text(label, color = if (selected) Forest else MutedInk, fontSize = 9.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
private fun EmptyDevicesPage(contentPadding: PaddingValues, onAddDevice: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        EmptyDevicesCard(onAddDevice)
    }
}

@Composable
private fun EmptyDevicesCard(onAddDevice: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PhoneGlyph(Modifier.size(34.dp))
            Text("No phones paired yet", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text("Add a trusted phone to send secure commands over SMS.", color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
            Button(onClick = onAddDevice, modifier = Modifier.padding(top = 15.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
                Text("Pair a phone")
            }
        }
    }
}

private fun DeviceCommand.label(): String = when (this) {
    DeviceCommand.STATUS -> "Status request"
    DeviceCommand.LOCATE -> "Location request"
    DeviceCommand.LOCK -> "Lock command"
    DeviceCommand.SOUND -> "Sound command"
    DeviceCommand.STOP_SOUND -> "Stop sound command"
}

private fun UiActionResult.message(subject: String): String = when (this) {
    UiActionResult.QUEUED -> "$subject SMS queued · waiting for a reply"
    UiActionResult.PAIRED -> "Phone paired and trusted."
    UiActionResult.REMOVED -> "Pairing removed from this phone."
    UiActionResult.NOT_READY -> "$subject isn't connected yet. Nothing was sent."
    UiActionResult.FAILED -> "$subject SMS wasn't queued. Check the error details and try again."
}

private fun demoProtectedDevices() = listOf(
    ProtectedDeviceUi(
        id = "pixel-8", name = "Pixel 8", phoneNumber = "+1 202-555-0142", batteryPercent = 78,
        networkName = "Example Mobile · LTE", wifiName = "Demo Wi-Fi", lastLocation = "Central Park, New York",
        lastSeen = "2 min ago", isOnline = true, isCurrentPhone = false, liveLocationEnabled = false,
        latitude = 40.7829, longitude = -73.9654,
        isDemo = true,
    ),
    ProtectedDeviceUi(
        id = "family-tablet", name = "Family tablet", phoneNumber = "+1 202-555-0188", batteryPercent = 42,
        networkName = "Example Mobile · 5G", wifiName = null, lastLocation = "Golden Gate Park, San Francisco",
        lastSeen = "18 min ago", isOnline = false, liveLocationEnabled = false,
        latitude = 37.7694, longitude = -122.4862,
        isDemo = true,
    ),
)
