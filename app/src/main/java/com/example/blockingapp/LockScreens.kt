package com.example.blockingapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
private fun CenteredCard(content: @Composable ColumnScope.() -> Unit) {
    Scaffold { inner ->
        Box(
            Modifier.fillMaxSize().padding(inner).imePadding().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun SetPasswordScreen() {
    val ctx = LocalContext.current
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        when {
            pw.length < 6 -> error = "Use at least 6 characters"
            pw != confirm -> error = "Passwords don't match"
            else -> {
                PasswordStore.set(ctx, pw)
                Session.passwordSet = true
                Session.unlocked = true
            }
        }
    }

    CenteredCard {
        Text("Create a password", style = MaterialTheme.typography.headlineSmall)
        Text(
            "It is required to open the app. If you forget it, the only way out is the PC fail-safe or a factory reset.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = pw, onValueChange = { pw = it; error = null }, label = { Text("Password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it; error = null }, label = { Text("Confirm password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save password") }
    }
}

@Composable
fun LoginScreen() {
    val ctx = LocalContext.current
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun unlock() {
        if (PasswordStore.verify(ctx, pw)) Session.unlocked = true else error = true
        pw = ""
    }

    CenteredCard {
        Text("BlockingApp", style = MaterialTheme.typography.headlineSmall)
        Text("Enter your password to continue.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = pw, onValueChange = { pw = it; error = false }, label = { Text("Password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { unlock() }),
            isError = error,
            supportingText = if (error) ({ Text("Wrong password") }) else null,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = ::unlock, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
    }
}
