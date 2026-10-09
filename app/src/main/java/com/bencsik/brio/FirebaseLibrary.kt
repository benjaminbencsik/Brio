package com.bencsik.brio

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.bencsik.brio.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class CloudAccountState(val configured: Boolean = false, val email: String? = null,
    val busy: Boolean = false, val status: String = "Cloud sync needs Firebase setup.")

/** One process-wide connection shared by browsing and the player. */
class FirebaseLibrary private constructor(context: Context) {
    private val context = context.applicationContext
    private val store = BrioStore(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()
    private val app = FirebaseApp.getApps(this.context).firstOrNull() ?: FirebaseApp.initializeApp(this.context)
    private val auth = app?.let { FirebaseAuth.getInstance(it) }
    private val database = app?.let { FirebaseFirestore.getInstance(it) }
    private val _state = MutableStateFlow(CloudAccountState(configured = app != null && webClientId().isNotBlank(), status = if (app == null) "Cloud sync needs Firebase setup." else "Sign in to sync your library."))
    val state = _state.asStateFlow()
    private var listener: ListenerRegistration? = null
    private var upload: Job? = null
    private var generation = 0
    private var lastUploadAt = 0L
    private var ready = false
    // Hold a strong reference for the lifetime of this process.
    private val stopObserving = store.observeLibrary { if (ready) scheduleUpload() }

    init {
        if (auth == null) store.switchAccount(null)
        auth?.addAuthStateListener { account ->
            val uid = account.currentUser?.uid
            if (uid == store.libraryAccount() && (uid == null || listener != null)) return@addAuthStateListener
            generation++; ready = false
            upload?.cancel(); listener?.remove(); listener = null
            store.switchAccount(uid)
            _state.value = _state.value.copy(email = account.currentUser?.email, busy = false,
                status = if (uid == null) "Signed out. Your guest library stays on this device." else "Loading your cloud library…")
            if (uid != null) listen(uid, generation)
        }
    }
    private fun webClientId(): String {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) "" else context.getString(id)
    }
    suspend fun signIn(activity: Activity) {
        if (!_state.value.configured) { _state.value = _state.value.copy(status = "Firebase project configuration is not installed yet."); return }
        _state.value = _state.value.copy(busy = true, status = "Choose your Google account…")
        try {
            val option = GetGoogleIdOption.Builder().setServerClientId(webClientId())
                .setFilterByAuthorizedAccounts(false).setAutoSelectEnabled(false).build()
            val result = CredentialManager.create(activity).getCredential(activity,
                GetCredentialRequest.Builder().addCredentialOption(option).build())
            val credential = result.credential
            check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Google did not return a sign-in credential." }
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            auth!!.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        } catch (_: GetCredentialCancellationException) {
            _state.value = _state.value.copy(status = "Sign-in cancelled.")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.value = _state.value.copy(status = "Google sign-in failed. Check the Firebase setup and try again.") }
        finally { _state.value = _state.value.copy(busy = false) }
    }
    suspend fun signOut() {
        // Preserve pending edits in this account's encrypted local library for next sign-in.
        generation++; ready = false; upload?.cancel(); listener?.remove(); listener = null
        auth?.signOut()
        store.switchAccount(null)
        _state.value = _state.value.copy(email = null, busy = false, status = "Signed out. Cloud data remains in your account.")
        try { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    private fun listen(uid: String, version: Int) {
        listener = database!!.collection("users").document(uid).collection("library")
            .addSnapshotListener { snapshot, error ->
                if (version != generation || auth?.currentUser?.uid != uid) return@addSnapshotListener
                if (error != null) {
                    _state.value = _state.value.copy(status = "Cloud sync unavailable. Progress is saved on this device; tap Sync now to retry.")
                    return@addSnapshotListener
                }
                val records = snapshot?.documents.orEmpty().mapNotNull { document ->
                    runCatching {
                        gson.fromJson(document.getString("record"), CloudRecord::class.java)?.let {
                            mergeCloudRecords(emptyList(), listOf(it)).singleOrNull()
                        }
                    }.getOrNull()
                }
                store.applyCloud(records)
                ready = true
                _state.value = _state.value.copy(status = if (snapshot?.metadata?.isFromCache == true) "Offline. Changes will sync when connected." else "Library synced.")
                scheduleUpload()
            }
    }
    fun syncNow() {
        lastUploadAt = 0
        if (!ready && auth?.currentUser != null) {
            listener?.remove(); listen(auth.currentUser!!.uid, generation)
        } else scheduleUpload()
    }
    private fun scheduleUpload() {
        if (!ready || auth?.currentUser == null || upload?.isActive == true || store.pendingCloud().isEmpty()) return
        val uid = auth.currentUser!!.uid
        val version = generation
        upload = scope.launch {
            // Coalesce player progress updates to at most one upload cycle per minute.
            while (System.currentTimeMillis() - lastUploadAt < 60_000) delay(1000)
            delay(500)
            if (version != generation) return@launch
            val records = store.pendingCloud()
            try {
                _state.value = _state.value.copy(status = "Syncing library…")
                records.forEach { record ->
                    if (version != generation) return@launch
                    val document = database!!.collection("users").document(uid).collection("library")
                        .document("${record.category}:${record.key}")
                    val winner = withTimeout(30_000) { database.runTransaction { transaction ->
                        val previous = transaction.get(document).getString("record")?.let {
                            runCatching { gson.fromJson(it, CloudRecord::class.java) }.getOrNull()
                        }
                        val merged = mergeCloudRecords(listOfNotNull(previous), listOf(record)).single()
                        if (merged == record) transaction.set(document, mapOf("record" to gson.toJson(merged), "updatedAt" to merged.updatedAt))
                        merged
                    }.await() }
                    if (version != generation) return@launch
                    store.applyCloud(listOf(winner))
                    store.acknowledgeCloud(listOf(winner))
                }
                lastUploadAt = System.currentTimeMillis()
                _state.value = _state.value.copy(status = "Library synced.")
            } catch (_: TimeoutCancellationException) {
                lastUploadAt = System.currentTimeMillis()
                _state.value = _state.value.copy(status = "Sync timed out. Progress is saved locally and will retry shortly.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                lastUploadAt = System.currentTimeMillis()
                _state.value = _state.value.copy(status = "Sync paused. Progress is saved locally and will retry shortly.")
            } finally {
                upload = null
                if (version == generation && store.pendingCloud().isNotEmpty()) scope.launch { delay(60_000); scheduleUpload() }
            }
        }
    }
    companion object {
        @Volatile private var instance: FirebaseLibrary? = null
        fun get(context: Context): FirebaseLibrary = instance ?: synchronized(this) {
            instance ?: FirebaseLibrary(context).also { instance = it }
        }
    }
}
