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
    private val appContext = context.applicationContext
    private val folder = File(context.applicationContext.filesDir, "timetable").apply { mkdirs() }
    private val client: OkHttpClient = Http.client(context)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<TimetableState>(TimetableState.Loading)
    val state: StateFlow<TimetableState> = _state.asStateFlow()

    private val metaFile get() = File(folder, "meta.json")
    private val mergedFile get() = File(folder, "merged.bin")
    private val removedFile get() = File(folder, REMOVED)

    /** When the app opens: reload what is there, then check without blocking. */
    fun start() {
        scope.launch {
            val ready = mutex.withLock { loadSaved() }
            if (!ready || isDue(readMeta())) refresh()
        }
    }

    /** When coming back to the app: the timetables may have changed since it was opened. */
    fun refreshIfDue() {
        scope.launch { refreshWhenDue() }
    }

    /** Reads the manifest again if the data mode says it is time. */
    suspend fun refreshWhenDue() {
        if (isDue(readMeta())) refresh()
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
                    _state.value = TimetableState.Failed("Impossible de télécharger les horaires. Vérifiez votre connexion.")
                }
                false
            }
        }
    }

    /** For background work, where the app may not have started. */
    suspend fun plannerOrNull(): JourneyPlanner? {
        (_state.value as? TimetableState.Ready)?.let { return it.planner }
        mutex.withLock { withContext(Dispatchers.IO) { loadSaved() } }
        return (_state.value as? TimetableState.Ready)?.planner
    }

    fun retry() {
        scope.launch { refresh() }
    }

    private fun isDue(meta: Meta?): Boolean =
        meta == null ||
            System.currentTimeMillis() - meta.checkedAt > DataPolicy.current(appContext).timetableCheckEvery(meta.pending != null).toMillis()

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
        combine(local(SNCF), local(IDFM), File(folder, CORRECTIONS), File(folder, RemovedRuns.PUBLISHED), null, mergedFile, removedFile).also {
            writeMeta(meta)
        }
    } catch (e: Exception) {
        if (e is IOException || e is IllegalArgumentException || e is SerializationException) null else throw e
    }

    private fun update() {
        val manifest = fetchManifest()
        val previous = readMeta()
        val hashes = manifest.files.mapValues { it.value.sha256 }
        // Xz since 0.1.9, about a quarter smaller; gzip while a manifest has no xz files.
        val xz = SNCF.xzKey in manifest.files && IDFM.xzKey in manifest.files
        val names = listOf(SNCF.pick(xz), IDFM.pick(xz), CORRECTIONS_KEY to CORRECTIONS)
        if (names.any { (key, _) -> key !in manifest.files }) throw TimetableUnavailable("Horaires publiés incomplets.")
        // Published since 0.1.9 only: a help, not required.
        val files = names + listOf(REMOVED_KEY to RemovedRuns.PUBLISHED).filter { (key, _) -> key in manifest.files }

        val changed = files.filter { (key, name) ->
            previous?.hashes?.get(key) != manifest.files.getValue(key).sha256 || !File(folder, name).exists()
        }
        // Timetables the phone already has may wait for Wi-Fi, as the data mode allows: the manifest is read
        // again soon, and the files are downloaded once on Wi-Fi or once waiting is no longer acceptable.
        // In either format: the switch to xz may wait for Wi-Fi too.
        val usable = previous != null && _state.value is TimetableState.Ready &&
            local(SNCF).exists() && local(IDFM).exists() && File(folder, CORRECTIONS).exists()
        val published = runCatching { OffsetDateTime.parse(manifest.generatedAt) }.getOrNull()
        if (changed.isNotEmpty() && usable && !DataPolicy.current(appContext).mayDownloadTimetables(published, OffsetDateTime.now())) {
            writeMeta(previous!!.copy(checkedAt = System.currentTimeMillis(), pending = manifest.generatedAt))
            WifiCatchUpWorker.schedule(appContext)
            return
        }

        val replaced = ArrayList<Pair<File, File>>()
        for ((key, name) in files) {
            val entry = manifest.files.getValue(key)
            val target = File(folder, name)
            if ((key to name) in changed) {
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
        fun candidate(source: Source) = replaced.firstOrNull { it.second.name in source.names }?.first ?: local(source)
        // The SNCF timetables being replaced: what they had and the new ones no longer have was cancelled.
        val oldSncf = local(SNCF).takeIf { it.exists() && replaced.any { (_, target) -> target.name in SNCF.names } }
        val candidateMerged = File(folder, "merged.new")
        val candidateRemoved = File(folder, "$REMOVED.new")
        val table = try {
            combine(
                candidate(SNCF), candidate(IDFM), candidate(CORRECTIONS), candidate(RemovedRuns.PUBLISHED), oldSncf,
                candidateMerged, candidateRemoved,
            )
        } catch (e: IllegalArgumentException) {
            throw TimetableUnavailable("Mets à jour l'application pour lire les nouveaux horaires.")
        } catch (e: ZipException) {
            throw TimetableUnavailable("Horaires publiés illisibles.")
        } catch (e: SerializationException) {
            throw TimetableUnavailable("Horaires publiés illisibles.")
        }

        replaced.forEach { (from, to) ->
            from.renameTo(to)
            // One format kept: the other would be read instead later.
            listOf(SNCF, IDFM).firstOrNull { to.name in it.names }?.names?.filter { it != to.name }?.forEach { File(folder, it).delete() }
        }
        candidateRemoved.renameTo(removedFile)
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

    /**
     * Merges both sources, then adds the runs cancelled in advance: those saved before, those published with
     * the timetables ([publishedRemovedFile], for a new install), and those [oldSncfFile] had and the new SNCF
     * timetables no longer have. Writes [target] and [removedTarget].
     */
    private fun combine(
        sncfFile: File,
        idfmFile: File,
        correctionsFile: File,
        publishedRemovedFile: File,
        oldSncfFile: File?,
        target: File,
        removedTarget: File,
    ): RailTimetable {
        val start = System.nanoTime()
        val sncf = sncfFile.inputStream().use(RailTimetable::readCompressed)
        val idfm = idfmFile.inputStream().use(RailTimetable::readCompressed)
        val corrections = json.decodeFromString<CorrectionsDto>(correctionsFile.readText())
        val merged = TimetableMerger.merge(sncf, idfm, TimetableCorrections(corrections.sameStation))
        // Only a help: unreadable old files or saved runs mean none are known.
        val oldSncf = oldSncfFile?.let { file -> runCatching { file.inputStream().use(RailTimetable::readCompressed) }.getOrNull() }
        val saved = runCatching { json.decodeFromString<List<RemovedRun>>(removedFile.readText()) }.getOrDefault(emptyList())
        val published = runCatching { RemovedRuns.readPublished(publishedRemovedFile.readBytes()) }.getOrDefault(emptyList())
        val previous = saved + published
        val removed = RemovedRuns.update(previous, oldSncf, merged, LocalDate.now(PARIS))
        val table = RemovedRuns.append(merged, removed)
        removedTarget.writeText(json.encodeToString(removed))
        TimetableCache.write(table, target)
        Log.i(TAG, "Timetables merged in ${(System.nanoTime() - start) / 1_000_000} ms, ${removed.size} runs cancelled in advance")
        suburbanUntil = idfm.lastDate
        File(folder, SUBURBAN_UNTIL).writeText(idfm.lastDate.toString())
        return table
    }

    @Volatile private var suburbanUntil: LocalDate? = null

    /** A timetable source, published as gzip under [key] and as xz under "[key]-xz". */
    private class Source(val key: String) {
        val xzKey = "$key-xz"
        val gz = "$key.bin.gz"
        val xz = "$key.bin.xz"
        val names = setOf(gz, xz)

        fun pick(useXz: Boolean) = if (useXz) xzKey to xz else key to gz
    }

    /** The file of [source] the phone has, in whichever format it was downloaded; only one is kept. */
    private fun local(source: Source): File = File(folder, source.xz).takeIf { it.exists() } ?: File(folder, source.gz)

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
    private data class Meta(
        val generatedAt: String,
        val hashes: Map<String, String>,
        val checkedAt: Long,
        /** Newer timetables published at that time, not downloaded yet: waiting for Wi-Fi. */
        val pending: String? = null,
    )

    @Serializable
    private data class CorrectionsDto(val sameStation: Map<String, String> = emptyMap())

    companion object {
        private const val TAG = "Datrain"
        private const val MANIFEST_FORMAT = 1
        private const val REMOVED_KEY = "removed"
        private const val CORRECTIONS_KEY = "corrections"
        private val SNCF = Source("sncf")
        private val IDFM = Source("idfm")
        private const val CORRECTIONS = "corrections.json"
        private const val SUBURBAN_UNTIL = "idfm-until.txt"
        private const val REMOVED = "removed.json"

        @Volatile private var instance: TimetableRepository? = null

        fun get(context: Context): TimetableRepository = instance ?: synchronized(this) {
            instance ?: TimetableRepository(context).also { instance = it }
        }
    }
}
