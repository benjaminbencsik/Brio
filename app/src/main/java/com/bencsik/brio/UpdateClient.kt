package com.bencsik.brio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.core.content.FileProvider

class BrioFileProvider : FileProvider()

/** Reads a GitHub release anonymously or with a device-only token for private repos. */
internal class UpdateClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("brio_updates", Context.MODE_PRIVATE)
    private val alias = "brio_release_token"
    private val releaseUrl = "https://api.github.com/repos/benjaminbencsik/Brio/releases/latest"

    data class Release(val version: String, val assetId: Long, val size: Long, val digest: String)

    fun hasToken(): Boolean = prefs.contains("token")

    fun saveToken(value: String) {
        val token = value.trim()
        require(token.isNotBlank()) { "Enter a GitHub token." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit().putString("token", Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)).apply()
    }

    fun clearToken() { prefs.edit().remove("token").apply() }

    private fun token(): String? {
        val saved = prefs.getString("token", null) ?: return null
        return try {
            val bytes = Base64.decode(saved, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        } catch (_: Exception) { error("Saved token couldn't be read. Remove it and save a new one.") }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun check(): Release? {
        val connection = connect(releaseUrl, token(), "application/vnd.github+json")
        val body = try {
            if (connection.responseCode !in 200..299) {
                if (connection.responseCode == 404 && !hasToken())
                    error("No public Brio release found. If the repository is still private, add a read-only GitHub token below.")
                error("GitHub couldn't read the release (${connection.responseCode}). Check repository access and try again.")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
        val json = JSONObject(body)
        val version = json.getString("tag_name").removePrefix("v")
        val parts = version.split('.').map { it.toIntOrNull() ?: error("Invalid release version.") }
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            .split('.').map { it.toIntOrNull() ?: 0 }
        val newer = (0 until maxOf(parts.size, installed.size)).firstOrNull { i ->
            parts.getOrElse(i) { 0 } != installed.getOrElse(i) { 0 }
        }?.let { i -> parts.getOrElse(i) { 0 } > installed.getOrElse(i) { 0 } } ?: false
        if (!newer) return null
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") != "app-debug.apk") continue
            val digest = asset.optString("digest").removePrefix("sha256:")
            require(digest.matches(Regex("[0-9a-fA-F]{64}"))) { "Release APK has no SHA-256 checksum." }
            val size = asset.getLong("size")
            require(size in 1L..100_000_000L) { "Unexpected update size." }
            return Release(version, asset.getLong("id"), size, digest)
        }
        error("The latest release has no installable APK.")
    }

    fun download(release: Release, progress: (Int) -> Unit): File {
        val uri = "https://api.github.com/repos/benjaminbencsik/Brio/releases/assets/${release.assetId}"
        val auth = token()
        var url = uri
        var connection: HttpURLConnection? = null
        try {
            repeat(4) {
                connection?.disconnect()
                connection = connect(url, if (URL(url).host == "api.github.com") auth else null, "application/octet-stream")
                val code = connection!!.responseCode
                if (code !in 300..399) {
                    if (code !in 200..299) error("APK download failed ($code). Check repository access and try again.")
                    val folder = File(context.cacheDir, "updates").apply { mkdirs() }
                    val partial = File(folder, "brio-update.part")
                    val apk = File(folder, "brio-update.apk")
                    val hash = MessageDigest.getInstance("SHA-256")
                    try {
                        var count = 0L
                        connection!!.inputStream.use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= release.size && count <= 100_000_000) { "APK exceeded expected size." }
                                output.write(buffer, 0, n)
                                hash.update(buffer, 0, n)
                                progress((count * 100 / release.size).toInt())
                            }
                        } }
                        require(count == release.size) { "APK download was incomplete." }
                        val actual = hash.digest().joinToString("") { "%02x".format(it) }
                        require(actual.equals(release.digest, ignoreCase = true)) { "APK checksum didn't match. Try again." }
                        require(partial.inputStream().use { it.read() == 80 && it.read() == 75 }) { "The download is not an APK." }
                        if (apk.exists()) apk.delete()
                        require(partial.renameTo(apk)) { "Couldn't save the update." }
                        return apk
                    } finally { partial.delete() }
                }
                val next = URL(connection!!.getHeaderField("Location") ?: error("Missing download address."))
                require(next.protocol == "https" && (next.host == "github.com" || next.host.endsWith(".githubusercontent.com"))) {
                    "Unexpected update download address."
                }
                url = next.toString()
            }
            error("Too many download redirects.")
        } finally { connection?.disconnect() }
    }

    private fun connect(address: String, authorization: String?, accept: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Brio-Android-Updater")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (authorization != null) setRequestProperty("Authorization", "Bearer $authorization")
        }
}
