package com.bencsik.brio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bencsik.brio.core.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials and signed playback URLs never go into plaintext preferences or backups. */
class BrioStore(context: Context) {
    private val prefs = context.getSharedPreferences("brio", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val alias = "brio.local.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun putSecret(name: String, value: String) {
        if (value.isEmpty()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(name, encoded).apply()
    }
    private fun secret(name: String): String {
        val encoded = prefs.getString(name, null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
    var tmdbToken: String
        get() = secret("tmdb")
        set(value) = putSecret("tmdb", value.trim())
    var debridToken: String
        get() = secret("debrid")
        set(value) = putSecret("debrid", value.trim())
    var provider: String
        get() = secret("provider")
        set(value) = putSecret("provider", value.trim())
    fun watchlist(): List<Title> = runCatching {
        gson.fromJson<List<Title>>(secret("watchlist").ifEmpty { "[]" }, object : TypeToken<List<Title>>() {}.type)
    }.getOrDefault(emptyList())
    fun toggle(item: Title) {
        val list = watchlist().toMutableList()
        if (!list.removeAll { it.key == item.key }) list.add(0, item)
        putSecret("watchlist", gson.toJson(list))
    }
    fun history(): List<History> = runCatching {
        gson.fromJson<List<History>>(secret("history").ifEmpty { "[]" }, object : TypeToken<List<History>>() {}.type)
    }.getOrDefault(emptyList())
    fun saveProgress(playback: Playback, position: Long, duration: Long) {
        val history = history().filterNot { it.playback.key == playback.key }.toMutableList()
        history.add(0, History(playback, position.coerceAtLeast(0), duration.coerceAtLeast(0), System.currentTimeMillis()))
        putSecret("history", gson.toJson(history.take(50)))
    }
    fun clearHistory() = prefs.edit().remove("history").apply()
}
