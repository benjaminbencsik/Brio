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
    private fun storageName(name: String): String = if (name in listOf("watchlist", "history", "cloud", "pending")) {
        val uid = prefs.getString("library_account", null)
        if (uid == null) name else "$name:$uid"
    } else name
    private fun putSecret(field: String, value: String) {
        val name = storageName(field)
        if (value.isEmpty()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(name, encoded).apply()
    }
    private fun secret(field: String): String {
        val name = storageName(field)
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
        if (item.id > 0 && item.kind in listOf("movie", "tv")) recordCloud(CloudRecord(item.key, "watchlist", cloudTitle(item), System.currentTimeMillis(), deleted = list.none { it.key == item.key }))
    }
    fun history(): List<History> = runCatching {
        gson.fromJson<List<History>>(secret("history").ifEmpty { "[]" }, object : TypeToken<List<History>>() {}.type)
    }.getOrDefault(emptyList())
    fun saveProgress(playback: Playback, position: Long, duration: Long) {
        val history = history().filterNot { it.playback.key == playback.key }.toMutableList()
        history.add(0, History(playback, position.coerceAtLeast(0), duration.coerceAtLeast(0), System.currentTimeMillis()))
        putSecret("history", gson.toJson(history.take(50)))
        if (playback.title.id > 0 && playback.title.kind in listOf("movie", "tv") && duration > 0) recordCloud(cloudProgress(history.first()))
    }
    fun clearSeriesProgress(title: Title) {
        putSecret("history", gson.toJson(history().filterNot { it.playback.title.key == title.key }))
        recordCloud(CloudRecord(title.key, "reset", cloudTitle(title), System.currentTimeMillis()))
    }
    fun clearHistory() {
        val now = System.currentTimeMillis()
        cloudRecords().filter { it.category == "progress" && !it.deleted }.forEach {
            recordCloud(it.copy(updatedAt = now, deleted = true))
        }
        prefs.edit().remove(storageName("history")).apply()
    }
    fun libraryAccount(): String? = prefs.getString("library_account", null)
    fun switchAccount(uid: String?) {
        if (uid == libraryAccount()) return
        // Import only the guest library, never the previously connected account.
        val guestList = if (libraryAccount() == null) watchlist() else emptyList()
        val guestHistory = if (libraryAccount() == null) history() else emptyList()
        val guestCloud = if (libraryAccount() == null) cloudRecords() else emptyList()
        prefs.edit().apply { if (uid == null) remove("library_account") else putString("library_account", uid) }.apply()
        if (uid != null && prefs.getString(storageName("cloud"), null) == null) {
            guestCloud.forEach(::recordCloud)
            guestList.filter { it.id > 0 && it.kind in listOf("movie", "tv") }.forEach {
                if (guestCloud.none { record -> record.category == "watchlist" && record.key == it.key })
                    recordCloud(CloudRecord(it.key, "watchlist", cloudTitle(it), 0))
            }
            guestHistory.filter { it.playback.title.id > 0 && it.playback.title.kind in listOf("movie", "tv") }.forEach {
                recordCloud(cloudProgress(it))
            }
            applyCloud(emptyList())
        }
    }
    fun cloudRecords(): List<CloudRecord> = runCatching {
        gson.fromJson<List<CloudRecord>>(secret("cloud").ifEmpty { "[]" }, object : TypeToken<List<CloudRecord>>() {}.type)
    }.getOrDefault(emptyList())
    private fun pendingIds(): Set<String> = runCatching {
        gson.fromJson<List<String>>(secret("pending").ifEmpty { "[]" }, object : TypeToken<List<String>>() {}.type).toSet()
    }.getOrDefault(emptySet())
    private fun recordId(record: CloudRecord) = "${record.category}:${record.key}"
    private fun recordCloud(record: CloudRecord) {
        putSecret("cloud", gson.toJson(mergeCloudRecords(cloudRecords(), listOf(record))))
        putSecret("pending", gson.toJson(pendingIds() + recordId(record)))
    }
    fun pendingCloud(): List<CloudRecord> = cloudRecords().filter { recordId(it) in pendingIds() }
    fun acknowledgeCloud(sent: List<CloudRecord>) {
        val current = cloudRecords().associateBy { recordId(it) }
        val acknowledged = sent.filter { current[recordId(it)] == it }.map { recordId(it) }.toSet()
        putSecret("pending", gson.toJson(pendingIds() - acknowledged))
    }
    fun applyCloud(remote: List<CloudRecord>) {
        val merged = mergeCloudRecords(cloudRecords(), remote)
        putSecret("cloud", gson.toJson(merged))
        val oldList = watchlist().associateBy { it.key }
        putSecret("watchlist", gson.toJson(merged.filter { it.category == "watchlist" && !it.deleted }
            .sortedByDescending { it.updatedAt }.map { oldList[it.title.key] ?: it.title }))
        val oldHistory = history().associateBy { it.playback.key }
        val accountHistory = visibleCloudProgress(merged).take(50).map { record ->
            val old = oldHistory[record.key]
            val playback = old?.playback ?: Playback(record.title, Stream("", "Resolve on device"), record.season, record.episode)
            History(playback, record.positionMs, record.durationMs, record.updatedAt)
        }
        // Local files and user-entered links are device-only.
        val deviceHistory = oldHistory.values.filter { it.playback.title.kind !in listOf("movie", "tv") || it.playback.title.id <= 0 }
        putSecret("history", gson.toJson((accountHistory + deviceHistory).sortedByDescending { it.updatedAt }.take(50)))
    }
    fun observeLibrary(listener: () -> Unit): () -> Unit {
        val observer = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == storageName("pending") || key == storageName("history") || key == storageName("watchlist")) listener()
        }
        prefs.registerOnSharedPreferenceChangeListener(observer)
        return { prefs.unregisterOnSharedPreferenceChangeListener(observer) }
    }
}
