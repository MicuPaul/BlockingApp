# BlockingApp

Android app that blocks websites **by domain, on the device**, using a local-only `VpnService`.
The user types the domains to block and the DNS server to use, and can pick apps to block (an
accessibility service sends them to the home screen while blocking is on). Blocking can be limited to a
**zone** (radius around a place). An app password protects every change that weakens blocking. The app
can additionally lock itself down (cannot be uninstalled, VPN forced on) by becoming **Device Owner**,
with a PC "fail-safe" to undo it.

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
- After a reboot or an app update, `BootReceiver` restarts the VPN if `Prefs.shouldRun` is set (set on
  every start, cleared only by Stop). It needs the existing VPN consent and can be refused as a background
  start if the app is battery-optimized, so keep it Unrestricted.

## Code map

| File | Role |
|---|---|
| `MainActivity.kt` | Activity, `Session` (password entered or not), `startVpn`/`stopVpn`. Always-dark, edge-to-edge. |
| `LockScreens.kt` | Create-password screen (first launch) and `PasswordDialog` (before protected actions). |
| `BlockerApp.kt` | Main UI: status banner + 3 tabs that fit a phone without scrolling: Blocklist (Sites / Apps segmented button, app picker), Zone, Settings (DNS, protection, fail-safe, battery, password cards). |
| `AppBlockerService.kt` + `res/xml/app_blocker_service.xml` | Accessibility service: when a listed app's window appears and `Zone.blocking` (VPN running and inside the zone), pauses media (so video apps usually don't enter picture-in-picture) and goes home. If a blocked app still ends up in PiP, it drags the PiP window onto the dismiss target with `dispatchGesture` (verified on the owner's Samsung), retrying every 300 ms because a real touch cancels the drag. Dropped approaches: a covering block activity (Samsung sends YouTube into PiP, then loops), `ACTION_DISMISS` on the PiP window (not offered on Samsung), `killBackgroundProcesses` (only kills own processes since Android 14). |
| `Zone.kt` | `ZoneArea`, `Zone` (inside/outside, fail-closed rules) and `ZoneMonitor` (location updates, run by the VPN service). |
| `AccessibilityGuard.kt` | Re-enables `AppBlockerService` by writing the secure setting (needs `WRITE_SECURE_SETTINGS` from adb). |
| `InstalledApps.kt` | Launchable apps for the picker (via `<queries>` in the manifest), excluding this app and the home app. |
| `BlockerState.kt` | Compose state holder for domains, apps, DNS (edited vs saved), zone, protection state, messages. |
| `BlockingVpnService.kt` | The VPN + DNS filter described above; while running it also runs `ZoneMonitor` and watches the accessibility setting. |
| `Prefs.kt` | `SharedPreferences` storage (domains, apps, DNS, zone, `protected` flag), domain/IP helpers, `VpnState.running`. |
| `PasswordStore.kt` | App password: PBKDF2WithHmacSHA1, 100k iterations, random salt. |
| `DevicePolicy.kt` | Device Owner features (enable/disable protection, remove owner), adb command strings. |
| `BootReceiver.kt` | Restarts blocking after `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` if it was on. |
| `AdminReceiver.kt` + `res/xml/device_admin.xml` | `DeviceAdminReceiver` required for device owner. |
| `ui/theme/` | Dark color scheme (dynamic color deliberately off). |

## App blocking

- Device Owner is **not** used for this (the owner wants Device Owner only to protect BlockingApp itself).
- Apps are blocked only while blocking is on (VPN running and inside the zone, if one is set). The user must
  enable the service once in Accessibility settings (Blocklist > Apps shows a button).
- The "X is blocked" toast is silently dropped if the app's notifications are off (Android logs
  "Suppressing toast ... by user request"); blocking still works.
- `AccessibilityGuard`: after `adb shell pm grant com.example.blockingapp android.permission.WRITE_SECURE_SETTINGS`
  (once; lost on uninstall), the app writes `enabled_accessibility_services` itself. `BlockingVpnService`
  observes that setting while running and switches the service back on if it is turned off.
- Battery: Settings tab opens `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. No public API lets an app (even a
  Device Owner) lock that setting; PC alternative: `adb shell dumpsys deviceidle whitelist +com.example.blockingapp`.
- Per-app *traffic* blocking was rejected: one `VpnService` can't route all traffic of some apps and only
  DNS for the rest without a full userspace TCP/UDP forwarder.

## Blocking zone

- Optional. Zone tab: radius (100 m to 50 km) around the current location. "Save radius" keeps the center,
  "Move zone to my location" moves it. Inside, blocking works as usual;
  outside, the VPN keeps running but `BlockingVpnService.isBlocked` and `AppBlockerService` let everything
  through (`Zone.inside` / `Zone.blocking`). Re-entering the zone re-checks the app on screen.
- Platform `LocationManager` only (fused provider on API 31+, else network; plus passive), 1 min interval.
  No Play Services, per the no-dependencies rule.
- **Fails closed**: no permission, location off (`onProviderDisabled`), a fix older than 30 min, a mock
  location, or an accuracy circle that touches the zone all count as inside.
- Needs "Allow all the time" location, otherwise updates stop with the app closed (then it just blocks).
- Set / move / resize need the password; removing the zone (block everywhere) doesn't.
- DNS answers cached before entering the zone can keep a site reachable until their TTL runs out.

## App password and locking

- First launch forces creating a password (min 6 chars). The app opens without it.
- Adding sites/apps, Start and Enable protection are free. Removing a site/app, saving the DNS, Stop,
  Disable protection, Remove device owner and Change password ask for it (`PasswordDialog`).
  Start uses the **saved** DNS (`BlockerState.savedDns`), so it can't save an edited DNS without the password.
- Once entered, the password is not asked again until `MainActivity.onStop` clears `Session.unlocked`,
  **unless** `isChangingConfigurations` (rotation / resize / fold) or `Session.suppressLock` (set while
  Android's VPN permission dialog is showing, which also stops the activity).
- Forgotten password: no recovery in the app. Use the PC fail-safe below or factory reset.

## Tamper protection (Device Owner)

Setup, once, from a PC (the device must have **no accounts and no extra users/profiles**):

```
adb shell dpm set-device-owner com.example.blockingapp/.AdminReceiver
```

Then Settings tab -> Tamper protection -> "Check again" -> "Enable protection". `DevicePolicy.enableProtection` does:
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
gone and the account is untouched. Also verified on the owner's real phone (device owner only, protection
not enabled) with one account present. `BlockerState.refreshPolicy()` / `DevicePolicy.syncState()` reset
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
- Reinstalling the app stops the VPN; `BootReceiver` restarts it (`MY_PACKAGE_REPLACED`) if it was on.
- `adb shell wm size` changes recreate the activity (useful to test responsive layout); reset with `wm size reset`.
- App blocking can be tested from adb: with blocking on, `adb shell am start -a android.intent.action.VIEW -d
  https://www.youtube.com/watch?v=jNQXAC9IVRw com.google.android.youtube` (run it twice for the PiP case),
  then `adb shell dumpsys activity activities | Select-String mode=pinned` to see if a PiP window is left.
- Removing accounts for `set-device-owner` on the real phone: some come from preinstalled apps and are not
  listed in Settings > Accounts (Instagram, Meet). `adb shell pm uninstall --user 0 <pkg>` plus a **reboot**
  removed them (without the reboot the account stayed). Secure Folder counts as an extra user and must be
  uninstalled. Check with `adb shell dumpsys account` (`Accounts: 0`) and `adb shell pm list users`.

## Status / not yet done

- Owner's real phone: Samsung SM-A566B, Android 16, One UI. Done there: device owner set (after removing all
  accounts and Secure Folder), fail-safe verified, app blocking incl. PiP drag verified, `WRITE_SECURE_SETTINGS`
  granted. **Not done there yet:** Enable protection, the zone (needs walking out of it), checking whether
  Samsung lets the battery setting be changed while the app is device owner.
- Things to watch on Samsung: Auto Blocker can block USB commands and installs (turn it off); Samsung battery
  management may kill the service (Unrestricted, not in sleeping apps).
- Ideas not implemented: `setUserControlDisabledPackages` to prevent force-stop/hibernation, foreground
  notification, IPv6/TCP DNS, unit tests for the DNS packet code and the zone rules,
  `allowBackup`/`data_extraction_rules` review (the password hash and blocklist live in SharedPreferences and
  are currently backup-eligible).
- Only the template's example tests exist.

## Working with the owner

- Prefer small, verified steps; test on the emulator before touching the real phone.
- Device Owner is only for protecting BlockingApp itself (uninstall, VPN); don't use it to block other apps.
- Do not enable device-owner protection on the real phone before checking the fail-safe works there.
- Use the IDE tools for file edits (do not edit files through shell redirection).
