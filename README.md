# BlockingApp

Android app that blocks websites **by domain, on the device**, using a local-only `VpnService`.
The user types the domains to block and the DNS server to use. The app can additionally lock
itself down (cannot be uninstalled, VPN forced on) by becoming **Device Owner**, behind an app
password, with a PC "fail-safe" to undo it.

It is a personal self-restriction tool (the owner blocks sites for themself). No traffic leaves the
phone except normal DNS forwarding to the DNS server the user chose.

- Kotlin + Jetpack Compose (Material 3), single module `:app`
- `minSdk 24`, `compileSdk/targetSdk 37`, AGP 9.5, Kotlin 2.2
- No third-party dependencies beyond the default Compose template. **Keep it that way** unless the
  owner agrees (they declined a Gradle sync for an icon library once, so the UI is text-only).

## How blocking works

`BlockingVpnService` (`app/src/main/java/com/example/blockingapp/BlockingVpnService.kt`):

1. Builds a TUN with address `10.111.222.1/32`, `addDnsServer(<user DNS>)` and **`addRoute(<user DNS>, 32)`**.
   Only traffic to the DNS server's IP goes through the VPN; everything else bypasses it
   (no speed cost, no packet forwarding for normal traffic).
2. Reads IPv4 packets from the TUN. It handles only **UDP to port 53 addressed to the DNS server**.
3. Parses the DNS question name.
   - Blocked (name equals a listed domain or ends with `.<domain>`): replies **NXDOMAIN**
     (header rewritten, question kept, additional/EDNS records dropped).
   - Otherwise: forwards the query from a `protect()`-ed `DatagramSocket` to the real DNS server
     (4s timeout) and writes the answer back as a hand-built IPv4/UDP packet (UDP checksum 0).
4. Packets are handled on a cached thread pool; writes to the TUN are synchronized.

The blocklist is read from `Prefs` on every query, so list edits apply immediately.
Changing the **DNS server** requires restarting the VPN ("Save and apply" does that).

### Known limitations (by design / not done)
- **IPv4 and UDP only.** No IPv6 DNS, no DNS-over-TCP. The DNS server field must be IPv4.
- **Bypasses:** Chrome "Secure DNS" (DoH), Android **Private DNS** set to a hostname, apps with
  hard-coded IPs or their own DoH. DNS blocking cannot stop these. DNS cache can also delay effects.
- Blocks whole hostnames only, not URL paths.
- No foreground notification. The service relies on `START_STICKY` and, when protected, on Android's
  always-on VPN restarting it.

## Code map

| File | Role |
|---|---|
| `MainActivity.kt` | Activity, `Session` (lock state), `startVpn`/`stopVpn`. Always-dark, edge-to-edge. |
| `LockScreens.kt` | Create-password and login screens. |
| `BlockerApp.kt` | Main UI: status banner + 4 tabs (Blocklist, DNS, Tamper, Password). |
| `BlockerState.kt` | Compose state holder for domains, DNS, protection state, messages. |
| `BlockingVpnService.kt` | The VPN + DNS filter described above. |
| `Prefs.kt` | `SharedPreferences` storage (domains, DNS, `protected` flag), domain/IP helpers, `VpnState.running`. |
| `PasswordStore.kt` | App password: PBKDF2WithHmacSHA1, 100k iterations, random salt. |
| `DevicePolicy.kt` | Device Owner features (enable/disable protection, remove owner), adb command strings. |
| `AdminReceiver.kt` + `res/xml/device_admin.xml` | `DeviceAdminReceiver` required for device owner. |
| `ui/theme/` | Dark color scheme (dynamic color deliberately off). |

## App password and locking

- First launch forces creating a password (min 6 chars). Everything is behind it.
- `MainActivity.onStop` locks the app **unless** `isChangingConfigurations` (rotation / resize / fold
  recreate the activity and must not lock) or `Session.suppressLock` (set while Android's VPN
  permission dialog is showing, which also stops the activity).
- Forgotten password: no recovery in the app. Use the PC fail-safe below or factory reset.

## Tamper protection (Device Owner)

Setup, once, from a PC (the device must have **no accounts and no extra users/profiles**):

```
adb shell dpm set-device-owner com.example.blockingapp/.AdminReceiver
```

Then Tamper tab -> "Check again" -> "Enable protection". `DevicePolicy.enableProtection` does:
- `setUninstallBlocked(self, true)`
- user restrictions: `no_config_vpn`, `no_control_apps`, `no_safe_boot`, and `no_config_private_dns` (API 29+)
- `setAlwaysOnVpnPackage(self, lockdown = false)`

**Do NOT turn lockdown on.** Lockdown blocks every connection not going through the VPN, and this
VPN only routes the DNS server's IP, so lockdown cuts off the whole internet (symptom: even
`ping 8.8.8.8` fails with "Do you want to ping broadcast?"). Always-on without lockdown is correct.

While protected the Stop button is replaced by a disabled "Protected" button.

### PC fail-safe (works with accounts on the phone)

```
adb shell dpm remove-active-admin com.example.blockingapp/.AdminReceiver
```

This only works because the manifest sets **`android:testOnly="true"`** (Android refuses
`remove-active-admin` for a non-test device owner). Consequences:
- Install with Android Studio Run or `adb install -t`; a plain APK tap / Play Store install fails.
- Anyone with USB debugging can remove the protection. This is intentional (recovery without a reset).
- Remove `testOnly` only if you also provide another recovery path.

Verified on an emulator: after the command the device owner, restrictions and uninstall block are
gone and the account is untouched. `BlockerState.refreshPolicy()` / `DevicePolicy.syncState()` reset
the stale `protected` flag so the UI recovers after a PC-side removal.
`set-device-owner` itself is refused when any account exists ("already some accounts on the device").

## Build, run, test

```
./gradlew :app:installDebug
```

Manual test recipe (works on an emulator, no UI needed for the checks):
1. Add a domain, set a DNS (e.g. `9.9.9.9`), tap Start, accept the VPN dialog.
2. `adb shell ip addr show tun0` exists while running.
3. `adb shell ping -c 1 blocked.example` -> `unknown host`; an unblocked host resolves.
4. Stop the VPN and confirm the blocked host resolves again (control test).
5. Protection: `uninstall` is refused (`DELETE_FAILED_DEVICE_POLICY_MANAGER`), `settings get secure
   always_on_vpn_app` shows the package, `always_on_vpn_lockdown` is `0`, internet still works.
6. Run the fail-safe and verify the device owner is gone.

To test device owner "with an account" without a real login, a throwaway debug-only
`AbstractAccountAuthenticator` + broadcast receiver was added under `app/src/debug`, used, then deleted
(not committed). Do the same if you need to repeat it.

Gotchas seen while testing:
- After the device owner is removed, Android resets the app's VPN consent; the next Start shows the
  "Connection request" dialog and **nothing starts until it is accepted** (looks like "blocking doesn't work").
- Reinstalling the app stops the VPN; re-tap Start.
- `adb shell wm size` changes recreate the activity (useful to test responsive layout); reset with `wm size reset`.

## Status / not yet done

- **Not yet tested on the owner's real phone** (Samsung, Android 16, One UI 8.5). Things to watch there:
  Auto Blocker can block USB commands and installs (turn it off); extra users such as Secure Folder
  block `set-device-owner`; Samsung battery management may kill the service (set the app to
  Unrestricted / not in sleeping apps).
- Ideas not implemented: `setUserControlDisabledPackages` to prevent force-stop/hibernation, a button for
  the battery-optimization exemption dialog, foreground notification, IPv6/TCP DNS, unit tests for the DNS
  packet code, `allowBackup`/`data_extraction_rules` review (the password hash and blocklist live in
  SharedPreferences and are currently backup-eligible).
- Only the template's example tests exist.

## Working with the owner

- Prefer small, verified steps; test on the emulator before touching the real phone.
- Do not enable device-owner protection on the real phone before checking the fail-safe works there.
- Use the IDE tools for file edits (do not edit files through shell redirection).
