package app.ferry

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What this phone knows about its dock: server, token and where arrivals land. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ferry", Context.MODE_PRIVATE)
    var server: String
        get() = sp.getString("server", "")!!
        set(v) = sp.edit().putString("server", v.trimEnd('/')).apply()
    var token: String
        get() = sp.getString("token", "")!!
        set(v) = sp.edit().putString("token", v).apply()
    var deviceId: String
        get() = sp.getString("device_id", "")!!
        set(v) = sp.edit().putString("device_id", v).apply()
    /** SAF tree picked in Settings; empty means Downloads/ferry. */
    var saveTree: String
        get() = sp.getString("save_tree", "")!!
        set(v) = sp.edit().putString("save_tree", v).apply()
    /** UiModeManager.MODE_NIGHT_AUTO / _NO / _YES, picked in Settings (Android 12+). */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()
    val docked get() = token.isNotEmpty()

    fun landed(id: String): Uri? = sp.getString("landed_$id", null)?.let(Uri::parse)
    fun markLanded(id: String, uri: Uri) = sp.edit().putString("landed_$id", uri.toString()).apply()
    /** Forget the dock; settings like the save folder stay. */
    fun clear() = sp.edit().remove("token").remove("device_id").apply()

    /** Set when the server rejected our token, so the dock screen can say why it is back. */
    var lostDock: Boolean
        get() = sp.getBoolean("lost_dock", false)
        set(v) = sp.edit().putBoolean("lost_dock", v).apply()
}

class ApiError(val code: Int, message: String) : Exception(message)

data class Dock(val id: String, val name: String, val platform: String, val online: Boolean, val lastSeen: Long, val saveLabel: String, val me: Boolean)
data class Crossing(val id: String, val name: String, val size: Long, val createdAt: Long, val from: String, val status: String, val targets: Map<String, String>)

/** Blocking HTTP client for the ferry server. Call it off the main thread. */
class Api(private val prefs: Prefs) {

    fun claim(server: String, code: String, name: String) {
        prefs.server = server
        val res = JSONObject(call("POST", "/api/dock/claim", JSONObject().put("code", code).put("name", name).put("platform", "android"), auth = false))
        prefs.token = res.getString("token")
        prefs.deviceId = res.getString("device_id")
    }

    fun docks(): List<Dock> = JSONArray(call("GET", "/api/docks")).objects().map {
        Dock(it.getString("id"), it.getString("name"), it.getString("platform"), it.getBoolean("online"),
            it.getLong("last_seen"), it.optString("save_label"), it.getBoolean("me"))
    }

    fun crossings(out: Boolean): List<Crossing> = JSONArray(call("GET", if (out) "/api/crossings?box=out" else "/api/crossings")).objects().map {
        val t = it.optJSONObject("targets")
        Crossing(it.getString("id"), it.getString("name"), it.getLong("size"), it.getLong("created_at"),
            it.optString("from"), it.optString("status"), t?.keys()?.asSequence()?.associateWith(t::getString) ?: emptyMap())
    }

    fun updateMe(name: String? = null, saveLabel: String? = null) {
        val body = JSONObject()
        name?.let { body.put("name", it) }
        saveLabel?.let { body.put("save_label", it) }
        call("PATCH", "/api/docks/me", body)
    }

    fun undock(id: String) = call("DELETE", "/api/docks/$id")
    fun ack(id: String) = call("POST", "/api/crossings/$id/ack")

    fun upload(name: String, size: Long, to: List<String>, input: InputStream, progress: (Long) -> Unit) {
        val path = "/api/crossings?name=${URLEncoder.encode(name, "UTF-8")}&to=${to.joinToString(",")}"
        val c = open("POST", path)
        c.doOutput = true
        c.setFixedLengthStreamingMode(size)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.outputStream.use { out -> copy(input, out, progress) }
        read(c)
    }

    fun download(id: String): HttpURLConnection {
        val c = open("GET", "/api/crossings/$id/file")
        if (c.responseCode != 200) read(c)
        return c
    }

    /** Opens the live event stream; the caller reads lines until it closes. */
    fun events(): HttpURLConnection = open("GET", "/api/events").apply { readTimeout = 60_000 }

    private fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): String {
        val c = open(method, path, auth)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        return read(c)
    }

    private fun open(method: String, path: String, auth: Boolean = true): HttpURLConnection =
        (URL(prefs.server + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            if (auth) setRequestProperty("Authorization", "Bearer ${prefs.token}")
        }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401 && prefs.docked) {
            prefs.clear()
            prefs.lostDock = true
        }
        if (code >= 400) throw ApiError(code, text.trim().ifEmpty { "HTTP $code" })
        return text
    }
}

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

fun copy(input: InputStream, out: OutputStream, progress: (Long) -> Unit = {}) {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        progress(total)
    }
}

/** Writes arrivals into the chosen folder, or Downloads/ferry by default. */
object Harbor {
    fun label(prefs: Prefs): String {
        if (prefs.saveTree.isEmpty()) return "Downloads/ferry"
        val doc = DocumentsContract.getTreeDocumentId(Uri.parse(prefs.saveTree)) // e.g. "primary:Documents/Inbox"
        return doc.substringAfter(':').ifEmpty { doc }
    }

    /** Downloads an arrival, saves it and acks it. */
    fun land(ctx: Context, prefs: Prefs, api: Api, c: Crossing): Uri {
        val conn = api.download(c.id)
        val uri = try { conn.inputStream.use { save(ctx, prefs, c.name, it) } } finally { conn.disconnect() }
        prefs.markLanded(c.id, uri)
        api.ack(c.id)
        return uri
    }

    fun save(ctx: Context, prefs: Prefs, name: String, input: InputStream): Uri {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val resolver = ctx.contentResolver
        if (prefs.saveTree.isNotEmpty()) {
            try {
                val tree = Uri.parse(prefs.saveTree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val uri = DocumentsContract.createDocument(resolver, parent, mime, name)!! // the provider renames duplicates
                resolver.openOutputStream(uri)!!.use { copy(input, it) }
                return uri
            } catch (e: SecurityException) {
                prefs.saveTree = "" // folder access was revoked: fall back to Downloads/ferry
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ferry")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        try {
            resolver.openOutputStream(uri)!!.use { copy(input, it) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }
}

fun statusText(d: Dock): String = when {
    d.online -> "In port"
    d.platform == "android" -> "Anchored · seen ${ago(d.lastSeen)}"
    else -> "Away · seen ${ago(d.lastSeen)} · waits up to 24h"
}

fun ago(sec: Long): String {
    val s = System.currentTimeMillis() / 1000 - sec
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

fun size(n: Long): String = when {
    n < 1_000 -> "$n B"
    n < 1_000_000 -> "%.1f KB".format(n / 1e3)
    n < 1_000_000_000 -> "%.1f MB".format(n / 1e6)
    else -> "%.2f GB".format(n / 1e9)
}
