package com.example.blockingapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TAB_TITLES = listOf("Blocklist", "Zone", "Settings")
private const val SETTINGS_TAB = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockerApp() {
    val ctx = LocalContext.current
    val state = remember { BlockerState(ctx) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val running = VpnState.running

    // Action waiting for the password dialog; runs right away if the password was already entered.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val withPassword: (() -> Unit) -> Unit = { action ->
        if (Session.unlocked) action() else pending = action
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Session.suppressLock = false
        if (result.resultCode == Activity.RESULT_OK) startVpn(ctx)
    }

    // Starts with the saved DNS only; changing the DNS needs the password (Settings tab).
    val start: () -> Unit = {
        if (!Prefs.isIpv4(state.savedDns)) {
            state.dnsMessage = "Set a DNS server first"
            tab = SETTINGS_TAB
        } else {
            val intent = VpnService.prepare(ctx)
            if (intent != null) {
                Session.suppressLock = true
                permissionLauncher.launch(intent)
            } else {
                startVpn(ctx)
            }
        }
    }
    val stop: () -> Unit = { withPassword { stopVpn(ctx) } }
    val applyDns: () -> Unit = {
        withPassword { if (state.saveDns() && VpnState.running) startVpn(ctx) }
    }
    val enableProtection: () -> Unit = { if (state.enableProtection()) start() }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.statusBarsPadding()) {
                    StatusBanner(state, running, start, stop)
                    PrimaryTabRow(selectedTabIndex = tab) {
                        TAB_TITLES.forEachIndexed { i, title ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, maxLines = 1) })
                        }
                    }
                }
            }
        }
    ) { inner ->
        Box(
            Modifier.padding(inner).imePadding().fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            when (tab) {
                0 -> BlocklistTab(
                    state,
                    onRemoveDomain = { d -> withPassword { state.removeDomain(d) } },
                    onRemoveApp = { p -> withPassword { state.removeApp(p) } }
                )
                1 -> ZoneTab(state, withPassword)
                else -> SettingsTab(
                    state,
                    running = running,
                    withPassword = withPassword,
                    onApplyDns = applyDns,
                    onEnableProtection = enableProtection
                )
            }
        }
    }

    pending?.let { action ->
        PasswordDialog(
            onDismiss = { pending = null },
            onSuccess = {
                pending = null
                action()
            }
        )
    }
}

@Composable
private fun StatusBanner(state: BlockerState, running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val blocking = running && Zone.inside
    Surface(
        color = if (blocking) colors.primaryContainer else colors.surfaceVariant,
        contentColor = if (blocking) colors.onPrimaryContainer else colors.onSurfaceVariant
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !running -> "Blocking is OFF"
                        blocking -> "Blocking is ON"
                        else -> "Blocking is PAUSED"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        !running -> "Tap Start to begin blocking"
                        blocking -> "${state.domains.size} sites · ${state.apps.size} apps · DNS ${state.savedDns}"
                        else -> "You're outside your zone. It turns back on when you return."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(12.dp))
            when {
                !running -> Button(onClick = onStart) { Text("Start") }
                state.isProtected -> FilledTonalButton(onClick = {}, enabled = false) { Text("Protected") }
                else -> FilledTonalButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

/** Sites and apps, switched with a segmented button so both fit under one tab. */
@Composable
private fun BlocklistTab(state: BlockerState, onRemoveDomain: (String) -> Unit, onRemoveApp: (String) -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("Sites (${state.domains.size})", "Apps (${state.apps.size})")

    Column(
        Modifier.widthIn(max = 900.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = section == i,
                    onClick = { section = i },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size)
                ) { Text(label, maxLines = 1) }
            }
        }
        if (section == 0) SitesSection(state, onRemoveDomain) else AppsSection(state, onRemoveApp)
    }
}

/** Blocked items as cards with a Remove button; an empty-state panel when there are none. */
@Composable
private fun BlockedGrid(
    items: List<String>,
    emptyTitle: String,
    modifier: Modifier,
    title: (String) -> String,
    subtitle: ((String) -> String)? = null,
    onRemove: (String) -> Unit
) {
    if (items.isEmpty()) {
        Surface(
            modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(emptyTitle, style = MaterialTheme.typography.titleMedium)
                Text("Add one above to get started.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    // One column on phones, more on tablets and unfolded foldables.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it }) { item ->
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title(item), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        subtitle?.let {
                            Text(
                                it(item),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    TextButton(onClick = { onRemove(item) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SitesSection(state: BlockerState, onRemove: (String) -> Unit) {
    var newDomain by remember { mutableStateOf("") }
    fun add() {
        if (state.addDomain(newDomain)) newDomain = ""
    }

    OutlinedTextField(
        value = newDomain,
        onValueChange = { newDomain = it; state.domainError = null },
        label = { Text("Add a website to block") },
        placeholder = { Text("example.com") },
        singleLine = true,
        isError = state.domainError != null,
        supportingText = state.domainError?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        trailingIcon = { TextButton(onClick = ::add) { Text("Add") } },
        modifier = Modifier.fillMaxWidth()
    )

    Text("Subdomains are blocked too", style = MaterialTheme.typography.titleSmall)

    BlockedGrid(
        items = state.domains,
        emptyTitle = "No blocked sites yet",
        modifier = Modifier.weight(1f),
        title = { it },
        onRemove = onRemove
    )
}

@Composable
private fun ColumnScope.AppsSection(state: BlockerState, onRemove: (String) -> Unit) {
    val ctx = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    var recheck by remember { mutableIntStateOf(0) }
    val canEnforce = remember(recheck, AppBlockerService.connected) { AccessibilityGuard.canEnforce(ctx) }

    if (!AppBlockerService.connected) {
        SectionCard("Allow app blocking") {
            Text(
                "Android needs your permission: Accessibility > Installed apps > BlockingApp > On. Until then the apps below are not blocked.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (canEnforce) {
                Button(onClick = { AccessibilityGuard.ensureEnabled(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Turn on")
                }
            } else {
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open accessibility settings") }
            }
        }
    }
    if (!canEnforce) {
        SectionCard("Keep app blocking on") {
            Text(
                "Right now it can be switched off in the accessibility settings. To have the app turn it back on by itself while blocking is ON, run this once from a PC:",
                style = MaterialTheme.typography.bodyMedium
            )
            CodeBox(AccessibilityGuard.GRANT_COMMAND)
            OutlinedButton(onClick = { recheck++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Check again")
            }
        }
    }

    Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Add an app to block")
    }

    Text("Closed while blocking is ON", style = MaterialTheme.typography.titleSmall)

    BlockedGrid(
        items = state.apps,
        emptyTitle = "No blocked apps yet",
        modifier = Modifier.weight(1f),
        title = { InstalledApps.label(ctx, it) },
        subtitle = { it },
        onRemove = onRemove
    )

    if (picking) {
        AppPickerDialog(
            exclude = state.apps.toSet(),
            onPick = { state.addApp(it); picking = false },
            onDismiss = { picking = false }
        )
    }
}

@Composable
private fun AppPickerDialog(exclude: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { InstalledApps.launchable(ctx) }
    }
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block an app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val all = apps
                if (all == null) {
                    Text("Loading apps…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val q = query.trim()
                    val shown = all.filter {
                        it.pkg !in exclude && (q.isEmpty() || it.label.contains(q, ignoreCase = true))
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(shown, key = { it.pkg }) { app ->
                            Column(
                                Modifier.fillMaxWidth().clickable { onPick(app.pkg) }.padding(vertical = 10.dp)
                            ) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ZoneTab(state: BlockerState, withPassword: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var recheck by remember { mutableIntStateOf(0) }
    val hasLocation = remember(recheck) { Zone.hasLocationAccess(ctx) }
    val hasBackground = remember(recheck) { Zone.hasBackgroundAccess(ctx) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { recheck++ }
    // On Android 11+ this opens the settings page, which stops the activity.
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        Session.suppressLock = false
        recheck++
    }

    val zone = state.zone
    var radiusText by rememberSaveable { mutableStateOf((zone?.radius ?: 300).toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }

    fun radius(): Int? = radiusText.trim().toIntOrNull()?.takeIf { it in Zone.MIN_RADIUS..Zone.MAX_RADIUS }
        .also { if (it == null) message = "Radius must be ${Zone.MIN_RADIUS} to ${Zone.MAX_RADIUS} m" }

    fun setHere() {
        val r = radius() ?: return
        withPassword {
            locating = true
            message = null
            Zone.currentLocation(ctx) { loc ->
                locating = false
                if (loc == null) {
                    message = "Couldn't get your location. Turn location on and try again."
                } else {
                    state.saveZone(ZoneArea(loc.latitude, loc.longitude, r))
                    Zone.update(ctx, loc)
                    message = "Zone saved (location accurate to ${loc.accuracy.toInt()} m)"
                }
            }
        }
    }

    ScrollPage {
        SectionCard("Blocking zone") {
            Text(
                "Blocking only applies within a radius of a place, e.g. home. Outside it, sites and apps are unblocked; back inside, blocking turns on again. Settings still need the password everywhere.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                when {
                    zone == null -> "No zone set: blocking applies everywhere."
                    !VpnState.running -> "Zone: ${zone.radius} m. Blocking is off."
                    Zone.inside -> "Zone: ${zone.radius} m. You're inside, blocking is ON."
                    else -> "Zone: ${zone.radius} m. You're outside, blocking is paused."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            when {
                !hasLocation -> {
                    Text("The zone needs location access.", style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = {
                            locationLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow location") }
                }
                !hasBackground -> {
                    Text(
                        "Set location to \"Allow all the time\". Otherwise the app can't tell when you leave, and blocking stays on everywhere.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            Session.suppressLock = true
                            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow all the time") }
                }
            }

            OutlinedTextField(
                value = radiusText,
                onValueChange = { radiusText = it; message = null },
                label = { Text("Radius (meters)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = ::setHere, enabled = hasLocation && !locating, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        locating -> "Getting your location…"
                        zone == null -> "Set zone around my location"
                        else -> "Move zone to my location"
                    }
                )
            }
            if (zone != null) {
                OutlinedButton(
                    onClick = {
                        val r = radius() ?: return@OutlinedButton
                        withPassword {
                            state.saveZone(zone.copy(radius = r))
                            message = "Radius saved"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save radius") }
                // Blocking everywhere only makes it stricter, so no password.
                OutlinedButton(
                    onClick = {
                        state.saveZone(null)
                        Zone.update(ctx, null)
                        message = "Zone removed: blocking applies everywhere"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Remove zone (block everywhere)") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "If location is off or unknown, the app assumes you're inside and keeps blocking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A scrolling, width-limited page used by the settings-style tabs. */
@Composable
private fun ScrollPage(content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        content()
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CodeBox(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }
    }
}

/** DNS, protection, battery and password, one card under the other. */
@Composable
private fun SettingsTab(
    state: BlockerState,
    running: Boolean,
    withPassword: (() -> Unit) -> Unit,
    onApplyDns: () -> Unit,
    onEnableProtection: () -> Unit
) {
    ScrollPage {
        DnsCard(state, running, onApplyDns)
        ProtectionCards(
            state,
            onEnableProtection = onEnableProtection,
            onDisableProtection = { withPassword(state::disableProtection) },
            onRemoveOwner = { withPassword(state::removeOwner) }
        )
        BatteryCard()
        PasswordCard(withPassword)
    }
}

@Composable
private fun DnsCard(state: BlockerState, running: Boolean, onApplyDns: () -> Unit) {
    SectionCard("DNS server") {
        Text(
            "Every lookup that isn't blocked is forwarded to this server. Pick a filtering DNS if you want it to block extra sites for you. Saving needs the password.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = state.dns,
            onValueChange = { state.dns = it },
            label = { Text("DNS server (IPv4)") },
            supportingText = { Text(state.dnsMessage ?: "Example: 9.9.9.9") },
            isError = state.dnsMessage != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onApplyDns() }),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onApplyDns, modifier = Modifier.fillMaxWidth()) {
            Text(if (running) "Save and apply" else "Save")
        }
    }
}

@Composable
private fun ProtectionCards(
    state: BlockerState,
    onEnableProtection: () -> Unit,
    onDisableProtection: () -> Unit,
    onRemoveOwner: () -> Unit
) {
    SectionCard("Tamper protection") {
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        when {
            !state.isOwner -> {
                Text(
                    "Not set up. Remove all accounts from the phone, connect it to a PC with USB debugging on, then run:",
                    style = MaterialTheme.typography.bodyMedium
                )
                CodeBox(DevicePolicy.ADB_COMMAND)
                OutlinedButton(onClick = state::refreshPolicy, modifier = Modifier.fillMaxWidth()) {
                    Text("Check again")
                }
            }
            !state.isProtected -> {
                Text(
                    "Device owner is active. Enabling protection blocks uninstalling, keeps the VPN always on, and locks VPN and Private DNS settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onEnableProtection, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable protection")
                }
                OutlinedButton(onClick = onRemoveOwner, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove device owner (allows uninstall)")
                }
            }
            else -> {
                Text(
                    "Protection is ON. The app can't be uninstalled and the VPN stays on.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onDisableProtection, modifier = Modifier.fillMaxWidth()) {
                    Text("Disable protection")
                }
            }
        }
    }

    if (state.isOwner) {
        SectionCard("Fail-safe") {
            Text(
                "If you get locked out, connect the phone to a PC with USB debugging on and run:",
                style = MaterialTheme.typography.bodyMedium
            )
            CodeBox(DevicePolicy.REMOVE_COMMAND)
        }
    }
}

/** Battery "Unrestricted" keeps Android (and Samsung's battery saver) from stopping the VPN. */
@Composable
private fun BatteryCard() {
    val ctx = LocalContext.current
    val power = remember { ctx.getSystemService(PowerManager::class.java) }
    var recheck by remember { mutableIntStateOf(0) }
    val unrestricted = remember(recheck) { power.isIgnoringBatteryOptimizations(ctx.packageName) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        recheck++
    }

    SectionCard("Battery") {
        if (unrestricted) {
            Text("Unrestricted. Android won't stop blocking to save battery.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "Optimized. Android may stop blocking in the background to save battery.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = {
                    @SuppressLint("BatteryLife")
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                    launcher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Set to Unrestricted") }
        }
    }
}

@Composable
private fun PasswordCard(withPassword: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var newPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    SectionCard("App password") {
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it; message = null },
            label = { Text("New password") },
            supportingText = message?.let { { Text(it) } },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (newPassword.length < 6) {
                    message = "Use at least 6 characters"
                } else {
                    withPassword {
                        PasswordStore.set(ctx, newPassword)
                        newPassword = ""
                        message = "Password changed"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Change password") }
        if (Session.unlocked) {
            OutlinedButton(onClick = { Session.unlocked = false }, modifier = Modifier.fillMaxWidth()) {
                Text("Lock now (ask for the password again)")
            }
        }
    }
}
