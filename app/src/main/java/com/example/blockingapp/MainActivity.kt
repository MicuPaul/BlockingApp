package com.example.blockingapp

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.blockingapp.ui.theme.BlockingAppTheme

object Session {
    var passwordSet by mutableStateOf(false)

    /** Password entered; protected actions don't ask again until the app leaves the screen. */
    var unlocked by mutableStateOf(false)
    var suppressLock = false
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so keep the system bar icons light regardless of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        Session.passwordSet = PasswordStore.isSet(this)
        setContent {
            BlockingAppTheme {
                Root()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Rotation, resizing and folding recreate the activity; that must not lock the app.
        if (!Session.suppressLock && !isChangingConfigurations) Session.unlocked = false
    }
}

@Composable
private fun Root() {
    if (Session.passwordSet) BlockerApp() else SetPasswordScreen()
}

internal fun startVpn(ctx: Context) {
    ctx.startService(Intent(ctx, BlockingVpnService::class.java))
}

internal fun stopVpn(ctx: Context) {
    ctx.startService(Intent(ctx, BlockingVpnService::class.java).setAction(BlockingVpnService.ACTION_STOP))
}
