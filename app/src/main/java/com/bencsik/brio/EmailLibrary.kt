package com.bencsik.brio

import android.content.Context
import com.bencsik.brio.core.CloudRecord
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CloudAccountState(
    val configured: Boolean = false,
    val email: String? = null,
    val pendingEmail: String? = null,
    val busy: Boolean = false,
    val status: String = "Email sync needs project setup."
)

/** One process-wide email account and library sync connection. No administrator key is bundled. */
class EmailLibrary private constructor(context: Context) {
    private val context = context.applicationContext
    private val store = BrioStore(this.context)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val config = runCatching {
        context.assets.open("cloud_config.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    }.getOrNull()
    private val base = config?.text("url")?.trimEnd('/').orEmpty()
    private val key = config?.text("publishable_key").orEmpty()
    private val configured = base.startsWith("https://") && key.startsWith("sb_publishable_")
    private var session: Session? = runCatching { gson.fromJson(store.emailCloudSession, Session::class.java) }.getOrNull()
        ?.takeIf { it.uid.isNotBlank() && it.refreshToken.isNotBlank() }
    private val refreshLock = Mutex()
    private var syncJob: Job? = null
    private var scheduled: Job? = null
    private var generation = 0
    private var lastUploadAt = 0L
    private val _state = MutableStateFlow(CloudAccountState(configured, session?.email,
        status = if (!configured) "Email sync needs a Supabase project configured by the app owner."
        else if (session != null) "Checking your library…" else "Sign in with an email code to sync across devices."))
    val state = _state.asStateFlow()
    // SharedPreferences does not retain observers unless we keep this callback alive.
    private val stopObserving = store.observeLibrary { if (session != null) scheduleUpload() }

    init {
        store.switchAccount(session?.uid)
        if (configured && session != null) syncNow()
    }

    suspend fun sendCode(address: String) {
        if (!configured) { _state.value = _state.value.copy(status = "Email sync has not been configured yet."); return }
        val email = address.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _state.value = _state.value.copy(status = "Enter a valid email address."); return
        }
        _state.value = _state.value.copy(busy = true, status = "Sending a six-digit code…")
        try {
            request("auth/v1/otp", "POST", gson.toJson(mapOf("email" to email, "create_user" to true)))
            _state.value = _state.value.copy(pendingEmail = email,
                status = "Check $email for your code. It may take a minute.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.value = _state.value.copy(status = "Couldn't send the code: ${message(e)}") }
        finally { _state.value = _state.value.copy(busy = false) }
    }

    suspend fun verifyCode(code: String) {
        val email = _state.value.pendingEmail ?: return
        val token = code.trim()
        if (!token.matches(Regex("[0-9]{6}"))) {
            _state.value = _state.value.copy(status = "Enter the six digits from your email."); return
        }
        _state.value = _state.value.copy(busy = true, status = "Checking your code…")
        try {
            val reply = request("auth/v1/verify", "POST",
                gson.toJson(mapOf("email" to email, "token" to token, "type" to "email"))).asJsonObject
            val newSession = sessionFrom(reply, email)
            generation++
            session = newSession
            store.emailCloudSession = gson.toJson(newSession)
            store.switchAccount(newSession.uid)
            _state.value = _state.value.copy(email = email, pendingEmail = null, status = "Signed in. Syncing library…")
            syncNow()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.value = _state.value.copy(status = "Code could not be verified: ${message(e)}") }
        finally { _state.value = _state.value.copy(busy = false) }
    }

    suspend fun signOut() {
        val old = session
        generation++
        scheduled?.cancel(); syncJob?.cancel()
        session = null
        store.emailCloudSession = ""
        store.switchAccount(null)
        _state.value = _state.value.copy(email = null, pendingEmail = null, busy = false,
            status = "Signed out. Your guest library stays on this device.")
        if (old != null && configured) try {
            request("auth/v1/logout", "POST", "{}", old.accessToken)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Local sign-out still succeeds offline. */ }
    }

    fun syncNow() {
        if (!configured || session == null || syncJob?.isActive == true) return
        val version = generation
        syncJob = scope.launch {
            try {
                _state.value = _state.value.copy(status = "Syncing library…")
                val remote = pull(version)
                if (version != generation) return@launch
                store.applyCloud(remote)
                val pending = store.pendingCloud()
                for (record in pending) {
                    if (version != generation) return@launch
                    val payload = JsonObject().apply {
                        addProperty("p_record_id", "${record.category}:${record.key}")
                        add("p_record", gson.toJsonTree(record))
                        addProperty("p_updated_at", record.updatedAt)
                    }
                    val winner = gson.fromJson(requestAuthenticated("rest/v1/rpc/merge_library_record", "POST", payload.toString()), CloudRecord::class.java)
                    if (version != generation) return@launch
                    store.applyCloud(listOf(winner))
                    store.acknowledgeCloud(listOf(winner))
                }
                lastUploadAt = System.currentTimeMillis()
                _state.value = _state.value.copy(status = "Library synced.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.value = _state.value.copy(status = "Sync paused: ${message(e)}. Progress stays on this device.")
            } finally {
                syncJob = null
                if (version == generation && store.pendingCloud().isNotEmpty()) scheduleUpload()
            }
        }
    }

    private suspend fun pull(version: Int): List<CloudRecord> {
        val records = mutableListOf<CloudRecord>()
        var offset = 0
        while (version == generation) {
            val rows = requestAuthenticated(
                "rest/v1/library_entries?select=record&order=updated_at.asc&limit=500&offset=$offset", "GET").asJsonArray
            rows.forEach { row ->
                runCatching { gson.fromJson(row.asJsonObject.get("record"), CloudRecord::class.java) }
                    .getOrNull()?.let(records::add)
            }
            if (rows.size() < 500) break
            offset += rows.size()
        }
        return records
    }

    private suspend fun requestAuthenticated(path: String, method: String, body: String? = null): JsonElement {
        val current = freshSession()
        return request(path, method, body, current.accessToken)
    }

    private suspend fun freshSession(): Session {
        refreshLock.lock()
        try {
            val current = session ?: error("Sign in again to sync.")
            if (System.currentTimeMillis() < current.expiresAt - 60_000) return current
            val reply = request("auth/v1/token?grant_type=refresh_token", "POST",
                gson.toJson(mapOf("refresh_token" to current.refreshToken))).asJsonObject
            val refreshed = sessionFrom(reply, current.email)
            require(refreshed.uid == current.uid) { "Account changed during token refresh." }
            session = refreshed
            store.emailCloudSession = gson.toJson(refreshed)
            return refreshed
        } finally { refreshLock.unlock() }
    }

    private suspend fun request(path: String, method: String, body: String? = null, accessToken: String? = null): JsonElement = withContext(Dispatchers.IO) {
        require(configured) { "Email sync is not configured." }
        val url = "$base/$path"
        val builder = Request.Builder().url(url).header("apikey", key)
            .header("Accept", "application/json")
        if (accessToken != null) builder.header("Authorization", "Bearer $accessToken")
        if (body == null) builder.get()
        else builder.method(method, body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val source = response.body?.source() ?: throw IOException("Empty server response")
            source.request(4_000_001)
            if (source.buffer.size > 4_000_000) throw IOException("Server response is too large")
            val content = source.readUtf8()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val error = JsonParser.parseString(content).asJsonObject
                    listOf("error_description", "msg", "message", "error").firstNotNullOfOrNull { error.text(it).takeIf(String::isNotBlank) }
                }.getOrNull() ?: "HTTP ${response.code}"
                throw IOException(detail.take(160))
            }
            if (content.isBlank()) JsonObject() else JsonParser.parseString(content)
        }
    }

    private fun sessionFrom(reply: JsonObject, fallbackEmail: String): Session {
        val user = reply.getAsJsonObject("user") ?: error("No account was returned.")
        val uid = user.text("id")
        val access = reply.text("access_token")
        val refresh = reply.text("refresh_token")
        require(uid.isNotBlank() && access.isNotBlank() && refresh.isNotBlank()) { "Incomplete sign-in response." }
        val seconds = reply.get("expires_in")?.asLong ?: 3600L
        return Session(uid, user.text("email").ifBlank { fallbackEmail }, access, refresh,
            System.currentTimeMillis() + seconds * 1000)
    }

    private fun scheduleUpload() {
        if (session == null || store.pendingCloud().isEmpty() || scheduled?.isActive == true) return
        scheduled = scope.launch {
            delay((60_000 - (System.currentTimeMillis() - lastUploadAt)).coerceAtLeast(500))
            syncNow()
        }
    }

    private fun message(error: Exception) = error.message?.take(160) ?: "Check your connection and try again"
    private fun JsonObject.text(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private data class Session(val uid: String, val email: String, val accessToken: String,
        val refreshToken: String, val expiresAt: Long)

    companion object {
        @Volatile private var instance: EmailLibrary? = null
        fun get(context: Context): EmailLibrary = instance ?: synchronized(this) {
            instance ?: EmailLibrary(context).also { instance = it }
        }
    }
}
