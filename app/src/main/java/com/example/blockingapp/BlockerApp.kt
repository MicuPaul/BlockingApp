package com.example.blockingapp

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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

private val TAB_TITLES = listOf("Blocklist", "DNS", "Tamper", "Password")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockerApp() {
    val ctx = LocalContext.current
    val state = remember { BlockerState(ctx) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val running = VpnState.running

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Session.suppressLock = false
        if (result.resultCode == Activity.RESULT_OK) startVpn(ctx)
    }

    val start: () -> Unit = {
        if (state.saveDns()) {
            val intent = VpnService.prepare(ctx)
            if (intent != null) {
                Session.suppressLock = true
                permissionLauncher.launch(intent)
            } else {
                startVpn(ctx)
            }
        }
    }
    val stop: () -> Unit = { stopVpn(ctx) }
    val applyDns: () -> Unit = { if (state.saveDns() && running) startVpn(ctx) }
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
                0 -> BlocklistTab(state)
                1 -> DnsTab(state, running, applyDns)
                2 -> TamperTab(state, enableProtection)
                else -> PasswordTab()
            }
        }
    }
}

@Composable
private fun StatusBanner(state: BlockerState, running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (running) colors.primaryContainer else colors.surfaceVariant,
        contentColor = if (running) colors.onPrimaryContainer else colors.onSurfaceVariant
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "Blocking is ON" else "Blocking is OFF",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (running) "${state.domains.size} sites blocked · DNS ${state.dns}"
                    else "Tap Start to begin blocking",
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

@Composable
private fun BlocklistTab(state: BlockerState) {
    var newDomain by remember { mutableStateOf("") }
    fun add() {
        if (state.addDomain(newDomain)) newDomain = ""
    }

    Column(
        Modifier.widthIn(max = 900.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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

        Text(
            "Blocked sites (${state.domains.size}) · subdomains are blocked too",
            style = MaterialTheme.typography.titleSmall
        )

        if (state.domains.isEmpty()) {
            Surface(
                Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No blocked sites yet", style = MaterialTheme.typography.titleMedium)
                    Text("Add one above to get started.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            // One column on phones, more on tablets and unfolded foldables.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.domains, key = { it }) { domain ->
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
                            Text(
                                domain,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { state.removeDomain(domain) }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
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

@Composable
private fun DnsTab(state: BlockerState, running: Boolean, onApplyDns: () -> Unit) {
    ScrollPage {
        SectionCard("DNS server") {
            Text(
                "Every lookup that isn't blocked is forwarded to this server. Pick a filtering DNS if you want it to block extra sites for you.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = state.dns,
                onValueChange = { state.dns = it },
                label = { Text("DNS server (IPv4)") },
                supportingText = { Text(state.message ?: "Example: 9.9.9.9") },
                isError = state.message != null,
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
}

@Composable
private fun TamperTab(state: BlockerState, onEnableProtection: () -> Unit) {
    ScrollPage {
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
                    OutlinedButton(onClick = state::removeOwner, modifier = Modifier.fillMaxWidth()) {
                        Text("Remove device owner (allows uninstall)")
                    }
                }
                else -> {
                    Text(
                        "Protection is ON. The app can't be uninstalled and the VPN stays on.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = state::disableProtection, modifier = Modifier.fillMaxWidth()) {
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
}

@Composable
private fun PasswordTab() {
    val ctx = LocalContext.current
    var newPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    ScrollPage {
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
                    if (newPassword.length >= 6) {
                        PasswordStore.set(ctx, newPassword)
                        newPassword = ""
                        message = "Password changed"
                    } else {
                        message = "Use at least 6 characters"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Change password") }
        }

        OutlinedButton(onClick = { Session.unlocked = false }, modifier = Modifier.fillMaxWidth()) {
            Text("Lock app now")
        }
    }
}
