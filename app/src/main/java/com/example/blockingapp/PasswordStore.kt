package com.example.blockingapp

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordStore {
    private const val FILE = "auth"
    private const val KEY_HASH = "hash"
    private const val KEY_SALT = "salt"
    private const val ITERATIONS = 100_000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isSet(ctx: Context): Boolean = prefs(ctx).contains(KEY_HASH)

    fun set(ctx: Context, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash(password, salt), Base64.NO_WRAP))
            .apply()
    }

    fun verify(ctx: Context, password: String): Boolean {
        val p = prefs(ctx)
        val salt = Base64.decode(p.getString(KEY_SALT, null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(p.getString(KEY_HASH, null) ?: return false, Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, hash(password, salt))
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256))
            .encoded
}
