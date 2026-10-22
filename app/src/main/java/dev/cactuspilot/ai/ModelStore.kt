package dev.cactuspilot.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val fraction: Float, val phase: String) : ModelState
    data object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

/** Downloads, unpacks and tracks model bundles under `filesDir/models/<id>/`. */
class ModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "models").apply { mkdirs() }

    init {
        pruneUnknown()
    }

    private val _states = MutableStateFlow(
        ModelCatalog.all.associate { it.id to initialState(it) },
    )
    val states: StateFlow<Map<String, ModelState>> = _states

    private val _active = MutableStateFlow(
        ModelRole.entries.associateWith { prefs.getString(activeKey(it), null) },
    )
    val active: StateFlow<Map<ModelRole, String?>> = _active

    /** Deletes leftovers of models that are no longer in the catalog (frees space after catalog changes). */
    private fun pruneUnknown() {
        val known = ModelCatalog.all.map { it.id }.toSet()
        root.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".installed").removeSuffix(".zip.part")
            if (id !in known) f.deleteRecursively()
        }
    }

    private fun initialState(spec: ModelSpec) =
        if (marker(spec).exists()) ModelState.Installed else ModelState.NotInstalled

    private fun dir(spec: ModelSpec) = File(root, spec.id)
    private fun marker(spec: ModelSpec) = File(root, "${spec.id}.installed")
    private fun part(spec: ModelSpec) = File(root, "${spec.id}.zip.part")
    private fun activeKey(role: ModelRole) = "active_${role.name}"

    fun isInstalled(spec: ModelSpec) = _states.value[spec.id] == ModelState.Installed

    /** Folder to pass to `nativeInit`, or null when the active model is missing. */
    fun activeModelDir(role: ModelRole): String? {
        val spec = ModelCatalog.byId(_active.value[role]) ?: return null
        if (!isInstalled(spec)) return null
        return resolveBundleDir(dir(spec)).absolutePath
    }

    fun activeSpec(role: ModelRole): ModelSpec? = ModelCatalog.byId(_active.value[role])

    fun setActive(spec: ModelSpec) {
        prefs.edit().putString(activeKey(spec.role), spec.id).apply()
        _active.update { it + (spec.role to spec.id) }
    }

    fun delete(spec: ModelSpec) {
        dir(spec).deleteRecursively()
        marker(spec).delete()
        part(spec).delete()
        setState(spec, ModelState.NotInstalled)
        if (_active.value[spec.role] == spec.id) {
            prefs.edit().remove(activeKey(spec.role)).apply()
            _active.update { it + (spec.role to null) }
        }
    }

    suspend fun download(spec: ModelSpec) = withContext(Dispatchers.IO) {
        try {
            setState(spec, ModelState.Downloading(0f, "Downloading"))
            downloadZip(spec)
            setState(spec, ModelState.Downloading(1f, "Unpacking"))
            val target = dir(spec)
            target.deleteRecursively()
            target.mkdirs()
            unzipSafely(part(spec), target)
            part(spec).delete()
            validateBundle(resolveBundleDir(target))
            marker(spec).writeText(System.currentTimeMillis().toString())
            setState(spec, ModelState.Installed)
            // First installed model of a role becomes active automatically.
            if (_active.value[spec.role] == null) setActive(spec)
        } catch (e: Exception) {
            setState(spec, ModelState.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun downloadZip(spec: ModelSpec) {
        val out = part(spec)
        var have = if (out.exists()) out.length() else 0L
        var conn = open(spec.url, have)
        if (conn.responseCode == 416) { // already complete or stale: start over
            out.delete(); have = 0L; conn.disconnect(); conn = open(spec.url, 0)
        }
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IOException("HTTP $code from huggingface.co")
            if (code == 200 && have > 0) { out.delete(); have = 0 }
            val total = have + conn.contentLengthLong.coerceAtLeast(0)
            var done = have
            var lastPublished = 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(out, have > 0).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        fos.write(buf, 0, r)
                        done += r
                        if (done - lastPublished > 256 * 1024 && total > 0) {
                            lastPublished = done
                            setState(spec, ModelState.Downloading(done.toFloat() / total, "Downloading"))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, from: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    private fun setState(spec: ModelSpec, state: ModelState) =
        _states.update { it + (spec.id to state) }

    companion object {
        /** Extracts [zip] into [destDir], refusing entries that would escape it (zip-slip). */
        fun unzipSafely(zip: File, destDir: File) {
            val canonicalRoot = destDir.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val outFile = File(destDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                        throw IOException("Blocked unsafe zip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                }
            }
        }

        /** A runnable v2 bundle has config.txt and components/manifest.json at its root. */
        fun validateBundle(dir: File) {
            val missing = listOf("config.txt", "components/manifest.json").filter { !File(dir, it).exists() }
            if (missing.isNotEmpty()) {
                throw IOException("Not a runnable Cactus bundle (missing ${missing.joinToString()})")
            }
        }

        /** Bundles may extract into a single wrapper folder; descend into it. */
        fun resolveBundleDir(dir: File): File {
            var cur = dir
            repeat(3) {
                val children = cur.listFiles()?.filter { !it.name.startsWith(".") && !it.name.startsWith("__MACOSX") }
                    ?: return cur
                if (children.size == 1 && children[0].isDirectory) cur = children[0] else return cur
            }
            return cur
        }
    }
}
