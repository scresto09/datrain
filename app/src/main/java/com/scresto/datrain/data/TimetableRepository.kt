package com.scresto.datrain.data

import android.content.Context
import android.util.Log
import com.scresto.datrain.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.zip.ZipException

sealed interface TimetableState {
    data object Loading : TimetableState
    data object Downloading : TimetableState
    data class Failed(val message: String) : TimetableState
    data class Ready(val planner: JourneyPlanner, val info: TimetableInfo) : TimetableState
}

/** [suburbanUntil]: last day of the IDFM timetables, published for about 30 days only. */
data class TimetableInfo(val generatedAt: OffsetDateTime?, val suburbanUntil: LocalDate?)

/**
 * Keeps timetables up to date: downloads only what changed, combines both sources,
 * and never replaces valid timetables with a damaged file.
 */
class TimetableRepository private constructor(context: Context) {
    private val folder = File(context.applicationContext.filesDir, "timetable").apply { mkdirs() }
    private val client: OkHttpClient = Http.client(context)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<TimetableState>(TimetableState.Loading)
    val state: StateFlow<TimetableState> = _state.asStateFlow()

    private val metaFile get() = File(folder, "meta.json")
    private val mergedFile get() = File(folder, "merged.bin")

    /** When the app opens: reload what is there, then check without blocking. */
    fun start() {
        scope.launch {
            val ready = mutex.withLock { loadSaved() }
            if (!ready || isDue(readMeta())) refresh()
        }
    }

    /** True if timetables are usable and up to date. Also called by the background job. */
    suspend fun refresh(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val hadData = _state.value is TimetableState.Ready
            if (!hadData) _state.value = TimetableState.Downloading
            try {
                update()
                true
            } catch (e: TimetableUnavailable) {
                if (!hadData) _state.value = TimetableState.Failed(e.message.orEmpty())
                false
            } catch (e: IOException) {
                if (!hadData) {
                    _state.value = TimetableState.Failed("Impossible de télécharger les horaires. Vérifie ta connexion.")
                }
                false
            }
        }
    }

    fun retry() {
        scope.launch { refresh() }
    }

    private fun isDue(meta: Meta?): Boolean =
        meta == null || System.currentTimeMillis() - meta.checkedAt > CHECK_EVERY_MILLIS

    private fun loadSaved(): Boolean {
        val meta = readMeta() ?: return false
        val start = System.nanoTime()
        val table = TimetableCache.read(mergedFile) ?: rebuild(meta) ?: return false
        Log.i(TAG, "Timetables read in ${(System.nanoTime() - start) / 1_000_000} ms")
        publish(table, meta)
        return true
    }

    /** Combined file missing or in an old format: rebuild it from the files already downloaded. */
    private fun rebuild(meta: Meta): RailTimetable? = try {
        combine(File(folder, SNCF), File(folder, IDFM), File(folder, CORRECTIONS), mergedFile).also {
            writeMeta(meta)
        }
    } catch (e: Exception) {
        if (e is IOException || e is IllegalArgumentException || e is SerializationException) null else throw e
    }

    private fun update() {
        val manifest = fetchManifest()
        val previous = readMeta()
        val hashes = manifest.files.mapValues { it.value.sha256 }
        val names = listOf(SNCF_KEY to SNCF, IDFM_KEY to IDFM, CORRECTIONS_KEY to CORRECTIONS)

        val replaced = ArrayList<Pair<File, File>>()
        for ((key, name) in names) {
            val entry = manifest.files[key] ?: throw TimetableUnavailable("Horaires publiés incomplets.")
            val target = File(folder, name)
            if (previous?.hashes?.get(key) != entry.sha256 || !target.exists()) {
                val temporary = File(folder, "$name.new")
                download(entry, temporary)
                replaced += temporary to target
            }
        }
        val meta = Meta(manifest.generatedAt, hashes, System.currentTimeMillis())
        if (replaced.isEmpty() && _state.value is TimetableState.Ready) {
            writeMeta(meta)
            return
        }

        fun candidate(name: String) = replaced.firstOrNull { it.second.name == name }?.first ?: File(folder, name)
        val candidateMerged = File(folder, "merged.new")
        val table = try {
            combine(candidate(SNCF), candidate(IDFM), candidate(CORRECTIONS), candidateMerged)
        } catch (e: IllegalArgumentException) {
            throw TimetableUnavailable("Mets à jour l'application pour lire les nouveaux horaires.")
        } catch (e: ZipException) {
            throw TimetableUnavailable("Horaires publiés illisibles.")
        } catch (e: SerializationException) {
            throw TimetableUnavailable("Horaires publiés illisibles.")
        }

        replaced.forEach { (from, to) -> from.renameTo(to) }
        candidateMerged.renameTo(mergedFile)
        writeMeta(meta)
        publish(table, meta)
    }

    private fun fetchManifest(): Manifest {
        val request = Request.Builder()
            .url(BuildConfig.MANIFEST_URL)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", "Datrain-Android/1.0")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TimetableUnavailable("Horaires indisponibles pour le moment (HTTP ${response.code}).")
            response.body?.string().orEmpty()
        }
        val manifest = try {
            json.decodeFromString<Manifest>(body)
        } catch (e: SerializationException) {
            throw TimetableUnavailable("Horaires publiés illisibles.")
        }
        if (manifest.format != MANIFEST_FORMAT) {
            throw TimetableUnavailable("Mets à jour l'application pour lire les nouveaux horaires.")
        }
        return manifest
    }

    private fun download(entry: ManifestFile, target: File) {
        // The hash already decides whether to download: a cached copy could only be stale.
        val request = Request.Builder()
            .url(entry.url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", "Datrain-Android/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val digest = MessageDigest.getInstance("SHA-256")
            target.outputStream().use { output ->
                response.body!!.byteStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != entry.sha256) {
                target.delete()
                throw IOException("Fichier incomplet")
            }
        }
    }

    private fun combine(sncfFile: File, idfmFile: File, correctionsFile: File, target: File): RailTimetable {
        val start = System.nanoTime()
        val sncf = sncfFile.inputStream().use(RailTimetable::readGzip)
        val idfm = idfmFile.inputStream().use(RailTimetable::readGzip)
        val corrections = json.decodeFromString<CorrectionsDto>(correctionsFile.readText())
        val merged = TimetableMerger.merge(sncf, idfm, TimetableCorrections(corrections.sameStation))
        TimetableCache.write(merged, target)
        Log.i(TAG, "Timetables merged in ${(System.nanoTime() - start) / 1_000_000} ms")
        suburbanUntil = idfm.lastDate
        File(folder, SUBURBAN_UNTIL).writeText(idfm.lastDate.toString())
        return merged
    }

    @Volatile private var suburbanUntil: LocalDate? = null

    private fun publish(table: RailTimetable, meta: Meta) {
        val until = suburbanUntil
            ?: runCatching { LocalDate.parse(File(folder, SUBURBAN_UNTIL).readText()) }.getOrNull()
        _state.value = TimetableState.Ready(
            JourneyPlanner(table),
            TimetableInfo(runCatching { OffsetDateTime.parse(meta.generatedAt) }.getOrNull(), until),
        )
    }

    private fun readMeta(): Meta? = runCatching { json.decodeFromString<Meta>(metaFile.readText()) }.getOrNull()

    private fun writeMeta(meta: Meta) {
        val temporary = File(folder, "meta.new")
        temporary.writeText(json.encodeToString(meta))
        temporary.renameTo(metaFile)
    }

    private class TimetableUnavailable(message: String) : Exception(message)

    @Serializable
    private data class Manifest(val format: Int, val generatedAt: String, val files: Map<String, ManifestFile>)

    @Serializable
    private data class ManifestFile(val url: String, val sha256: String, val size: Long = 0)

    @Serializable
    private data class Meta(val generatedAt: String, val hashes: Map<String, String>, val checkedAt: Long)

    @Serializable
    private data class CorrectionsDto(val sameStation: Map<String, String> = emptyMap())

    companion object {
        private const val TAG = "Datrain"
        private const val MANIFEST_FORMAT = 1
        private const val CHECK_EVERY_MILLIS = 24L * 3600 * 1000
        private const val SNCF_KEY = "sncf"
        private const val IDFM_KEY = "idfm"
        private const val CORRECTIONS_KEY = "corrections"
        private const val SNCF = "sncf.bin.gz"
        private const val IDFM = "idfm.bin.gz"
        private const val CORRECTIONS = "corrections.json"
        private const val SUBURBAN_UNTIL = "idfm-until.txt"

        @Volatile private var instance: TimetableRepository? = null

        fun get(context: Context): TimetableRepository = instance ?: synchronized(this) {
            instance ?: TimetableRepository(context).also { instance = it }
        }
    }
}
