package app.ferry

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.UUID
import kotlin.concurrent.thread

private const val PICK_FILES = 1
private const val PICK_FOLDER = 2

/** Docking (first run), then Arrivals / Departures. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private var departures = false
    private var deepLink: Uri? = null
    private var list: LinearLayout? = null
    @Volatile private var listening = false
    @Volatile private var stream: HttpURLConnection? = null
    private val refreshOnSync = Observer<List<WorkInfo>> { infos ->
        if (infos.any { it.state == WorkInfo.State.SUCCEEDED }) load()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = Prefs(this)
        api = Api(prefs)
        deepLink = intent?.data
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink = intent.data
        render()
    }

    override fun onStart() {
        super.onStart()
        startLive()
    }

    private fun startLive() {
        if (!prefs.docked) return
        render()
        Sync.now(this)
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("arrivals-now").observeForever(refreshOnSync)
        listen()
    }

    override fun onStop() {
        super.onStop()
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("arrivals-now").removeObserver(refreshOnSync)
        listening = false
        stream?.disconnect()
    }

    private fun render() = if (prefs.docked && deepLink == null) home() else dock()

    // ---- docking ----

    private fun dock() {
        val link = deepLink?.takeIf { it.scheme == "ferry" }
        val body = column()
        body.addView(wordmark())
        body.gap(24)
        body.addView(title("Dock with a computer"))
        body.addView(text("Open ferry on the web › Docks › Show boarding code. Scan it with your camera app, or type it here.", 15f, R.color.muted))
        if (prefs.lostDock) {
            body.gap(12)
            body.addView(text("This device isn't docked anymore: it was undocked, or the server was reset. Dock it again with a new boarding code.", 14f, R.color.danger))
        } else if (prefs.docked) {
            body.gap(12)
            body.addView(text("Docking here moves this device to the fleet of that code.", 14f, R.color.muted))
        }
        body.gap(20)
        val server = field("Server, e.g. https://ferry.example.com", link?.getQueryParameter("server") ?: prefs.server)
        val code = field("Boarding code", link?.getQueryParameter("code").orEmpty(), numeric = true)
        val name = field("Name shown to other devices", Build.MODEL)
        for ((l, f) in listOf("Server" to server, "Boarding code" to code, "This device's name" to name)) {
            body.addView(text(l, 13f, R.color.muted).apply { setPadding(0, dp(12), 0, dp(6)) })
            body.addView(f)
        }
        val error = text("", 13f, R.color.danger).apply { setPadding(0, dp(12), 0, 0) }
        body.addView(error)
        body.gap(12)
        body.addView(button("Dock") { b ->
            b.isEnabled = false
            error.text = ""
            bg({
                // A boarding code always wins: leave the current fleet first (best effort, it may already be gone).
                if (prefs.docked) runCatching { api.undock(prefs.deviceId) }
                api.claim(server.text.toString().trim(), code.text.toString(), name.text.toString())
            }) { r ->
                b.isEnabled = true
                r.onFailure { error.text = it.message ?: "Could not reach the server" }
                r.onSuccess {
                    deepLink = null
                    prefs.lostDock = false
                    Sync.schedule(this)
                    if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
                    startLive()
                }
            }
        })
        screen(body)
    }

    // ---- arrivals / departures ----

    private fun home() {
        val body = column()
        body.addView(row().apply {
            addView(wordmark(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Settings", primary = false) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) })
        })
        body.gap(16)
        body.addView(row().apply {
            for ((label, out) in listOf("Arrivals" to false, "Departures" to true)) {
                addView(button(label, primary = departures == out) { departures = out; home() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        body.gap(12)
        val card = card()
        list = card
        body.addView(card)
        body.addView(text("Arrivals land in ${Harbor.label(prefs)} · the server copy is deleted once every destination has it", 12f, R.color.muted)
            .apply { setPadding(0, dp(10), 0, 0) })
        body.addView(LinearLayout(this), LinearLayout.LayoutParams(1, 0, 1f)) // pushes the button down
        body.gap(16)
        body.addView(button("Ferry files") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_FILES)
        })
        screen(body)
        load()
    }

    private fun load() {
        val card = list ?: return
        val out = departures
        bg({ api.crossings(out) }) { r ->
            if (!prefs.docked) return@bg render()
            card.removeAllViews()
            val items = r.getOrElse {
                card.addView(item("Can't reach the server", it.message ?: "", R.color.danger))
                return@bg
            }
            if (items.isEmpty()) card.addView(item(if (out) "No departures yet" else "Nothing has arrived yet", "Files show up here"))
            for ((i, c) in items.withIndex()) {
                if (i > 0) card.addView(divider())
                card.addView(if (out) {
                    item(c.name, c.targets.entries.joinToString(" · ") { (n, s) -> "$n: ${if (s == "arrived") "arrived" else "crossing"}" })
                } else {
                    val landed = prefs.landed(c.id)
                    item(c.name, "From ${c.from} · ${ago(c.createdAt)} · ${size(c.size)}",
                        trailing = if (landed != null) button("Open", primary = false) { open(landed) }
                        else text(if (c.status == "pending") "Crossing…" else "Landed elsewhere", 12f, R.color.muted))
                })
            }
        }
    }

    private fun open(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity result API needs AndroidX activity; this keeps the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        if (uris.isEmpty()) return
        val share = Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        share.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        startActivity(share)
    }

    /** Live events while the app is open: arrivals download right away and docks show "In port". */
    private fun listen() {
        if (listening) return
        listening = true
        thread(isDaemon = true) {
            while (listening && prefs.docked) {
                try {
                    val c = api.events()
                    stream = c
                    c.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!listening) break
                            if (!line.startsWith("data: ")) continue
                            val type = JSONObject(line.removePrefix("data: ")).optString("type")
                            runOnUiThread {
                                when (type) {
                                    "arrival" -> { Sync.now(this); load() }
                                    "departure" -> load()
                                    "undocked" -> { prefs.clear(); Sync.stop(this); render() }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (listening) Thread.sleep(3000) // server restarting or network blip: reconnect
                }
            }
        }
    }
}

/** Share sheet target: pick destinations, then the upload continues in the background. */
class ShareActivity : Activity() {
    private class Cargo(val uri: Uri?, val name: String, val size: Long, val text: String? = null)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val prefs = Prefs(this)
        if (!prefs.docked) {
            Toast.makeText(this, "Dock ferry with a computer first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            return finish()
        }
        val cargo = cargo()
        if (cargo.isEmpty()) return finish()

        val body = column()
        body.addView(title("Ferry ${cargo.size} ${if (cargo.size == 1) "file" else "files"}"))
        body.addView(text(if (cargo.size == 1) cargo[0].name else size(cargo.sumOf { it.size }), 13f, R.color.muted))
        body.addView(label("Destinations"))
        val card = card()
        body.addView(card)
        body.gap(20)
        val picked = mutableSetOf<String>()
        lateinit var go: Button
        fun refreshButton() {
            go.isEnabled = picked.isNotEmpty()
            go.alpha = if (go.isEnabled) 1f else 0.45f
            go.text = if (picked.isEmpty()) "Pick a destination" else "Ferry to ${picked.size} ${if (picked.size == 1) "device" else "devices"}"
        }
        go = button("Pick a destination") { b ->
            b.isEnabled = false
            val to = picked.toList()
            bg({ stash(cargo) }) { r ->
                r.onSuccess { files ->
                    Sync.ferry(this, files, to)
                    Toast.makeText(this, "Ferrying ${files.size} ${if (files.size == 1) "file" else "files"}", Toast.LENGTH_SHORT).show()
                    finish()
                }
                r.onFailure { Toast.makeText(this, "Couldn't read the files: ${it.message}", Toast.LENGTH_LONG).show(); b.isEnabled = true }
            }
        }
        body.addView(go)
        refreshButton()
        screen(body)

        bg({ Api(prefs).docks().filter { !it.me } }) { r ->
            val docks = r.getOrElse {
                card.addView(item("Can't reach the server", it.message ?: "", R.color.danger))
                return@bg
            }
            if (docks.isEmpty()) card.addView(item("No other docks yet", "Dock a computer from ferry on the web"))
            for ((i, d) in docks.withIndex()) {
                if (i > 0) card.addView(divider())
                val box = check(false)
                val row = item(d.name, statusText(d), if (d.online) R.color.ok else R.color.muted, trailing = box)
                box.setOnCheckedChangeListener { _, on -> if (on) picked.add(d.id) else picked.remove(d.id); refreshButton() }
                row.setOnClickListener { box.toggle() }
                card.addView(row)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun cargo(): List<Cargo> {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
        }
        if (uris.isEmpty()) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return emptyList()
            return listOf(Cargo(null, "shared-text.txt", text.toByteArray().size.toLong(), text))
        }
        return uris.map { uri ->
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) Cargo(uri, c.getString(0) ?: "file", if (c.isNull(1)) 0 else c.getLong(1)) else null
            } ?: Cargo(uri, uri.lastPathSegment ?: "file", 0)
        }
    }

    /** Copies shared files into the cache: the share grant ends with this screen, the upload may not. */
    private fun stash(cargo: List<Cargo>): List<File> = cargo.map { c ->
        val dir = File(cacheDir, "outbox/" + UUID.randomUUID()).apply { mkdirs() }
        val f = File(dir, File(c.name).name.ifEmpty { "file" })
        if (c.text != null) f.writeText(c.text)
        else contentResolver.openInputStream(c.uri!!)!!.use { input -> f.outputStream().use { copy(input, it) } }
        f
    }
}

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var api: Api

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = Prefs(this)
        api = Api(prefs)
        render()
    }

    private fun render() {
        val body = column()
        body.addView(title("Settings"))

        body.addView(label("Downloads"))
        val harbor = card()
        harbor.addView(item("Arrivals land in", Harbor.label(prefs), trailing = button("Change", primary = false) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)
        }))
        if (prefs.saveTree.isNotEmpty()) {
            harbor.addView(divider())
            harbor.addView(item("Use Downloads/ferry", "Go back to the default folder", trailing = button("Reset", primary = false) {
                prefs.saveTree = ""
                saveLabel()
            }))
        }
        body.addView(harbor)

        if (Build.VERSION.SDK_INT >= 31) {
            body.addView(label("Appearance"))
            body.addView(row().apply {
                for ((name, mode) in listOf("Auto" to UiModeManager.MODE_NIGHT_AUTO, "Light" to UiModeManager.MODE_NIGHT_NO, "Dark" to UiModeManager.MODE_NIGHT_YES)) {
                    addView(button(name, primary = prefs.theme == mode) {
                        prefs.theme = mode
                        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode) // the system restarts screens in the new theme
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            })
        }

        body.addView(label("This device"))
        val name = field("Name shown to other devices")
        body.addView(name)
        body.gap(8)
        body.addView(button("Save name", primary = false) {
            bg({ api.updateMe(name = name.text.toString()) }) { r -> toast(r.fold({ "Saved" }, { it.message ?: "Failed" })) }
        })
        bg({ api.docks().first { it.me }.name }) { r -> r.onSuccess { name.setText(it) } }

        body.addView(label("Docking"))
        body.addView(button("Undock this device", primary = false) {
            AlertDialog.Builder(this)
                .setMessage("Undock this device? You'll need a boarding code to dock it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Undock") { _, _ ->
                    bg({ api.undock(prefs.deviceId) }) {
                        prefs.clear()
                        Sync.stop(this)
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }
                }.show()
        }.apply { setTextColor(color(R.color.danger)) })
        screen(body)
    }

    private fun saveLabel() {
        bg({ api.updateMe(saveLabel = Harbor.label(prefs)) }) {}
        render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Deprecated("Activity result API needs AndroidX activity; this keeps the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || uri == null) return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.saveTree = uri.toString()
        saveLabel()
    }
}
