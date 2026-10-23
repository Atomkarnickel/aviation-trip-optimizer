package com.atomkarnickel.overhead.data

import com.atomkarnickel.overhead.domain.RouteAirport
import com.atomkarnickel.overhead.domain.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Letzter Abruf – gemeinsam für App, Hintergrundaktualisierung und Widget. */
interface SnapshotStore {
    val latest: StateFlow<Snapshot?>
    suspend fun load(): Snapshot?
    suspend fun save(snapshot: Snapshot)
}

private val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Atomar geschriebene JSON-Datei im App-Speicher. */
class FileSnapshotStore(private val file: File) : SnapshotStore {
    private val state = MutableStateFlow<Snapshot?>(null)
    private val mutex = Mutex()
    private var loaded = false
    override val latest: StateFlow<Snapshot?> = state

    override suspend fun load(): Snapshot? = mutex.withLock {
        if (!loaded) {
            state.value = withContext(Dispatchers.IO) {
                runCatching { if (file.exists()) storeJson.decodeFromString<Snapshot>(file.readText()) else null }.getOrNull()
            }
            loaded = true
        }
        state.value
    }

    override suspend fun save(snapshot: Snapshot) = mutex.withLock {
        withContext(Dispatchers.IO) { writeAtomically(file, storeJson.encodeToString(Snapshot.serializer(), snapshot)) }
        state.value = snapshot
        loaded = true
    }
}

class MemorySnapshotStore(initial: Snapshot? = null) : SnapshotStore {
    private val state = MutableStateFlow(initial)
    override val latest: StateFlow<Snapshot?> = state
    override suspend fun load() = state.value
    override suspend fun save(snapshot: Snapshot) { state.value = snapshot }
}

@Serializable
private data class RouteCacheFile(
    val routes: Map<String, CachedRoute> = emptyMap(),
    val airports: Map<String, RouteAirport> = emptyMap(),
)

/** Routen-/Flughafen-Cache als Datei, damit auch nach Prozessende nicht neu abgefragt werden muss. */
class FileRouteCache(private val file: File, private val maxRoutes: Int = 2000) : RouteCache {
    private var dirty = false
    private val lock = Any()

    // Erst beim ersten Zugriff lesen (der Abruf läuft auf einem IO-Thread).
    private val memory by lazy {
        MemoryRouteCache().also { m ->
            runCatching { if (file.exists()) storeJson.decodeFromString<RouteCacheFile>(file.readText()) else null }.getOrNull()?.let {
                m.routes.putAll(it.routes)
                m.airports.putAll(it.airports)
            }
        }
    }

    override fun route(callsign: String) = synchronized(lock) { memory.route(callsign) }
    override fun airport(icao: String) = synchronized(lock) { memory.airport(icao) }

    override fun putRoute(route: CachedRoute) = synchronized(lock) {
        memory.putRoute(route)
        if (memory.routes.size > maxRoutes) {
            memory.routes.values.sortedBy { it.savedAtMillis }.take(memory.routes.size - maxRoutes)
                .forEach { memory.routes.remove(it.record.callsign) }
        }
        dirty = true
    }

    override fun putAirport(airport: RouteAirport) = synchronized(lock) {
        memory.putAirport(airport)
        dirty = true
    }

    override suspend fun flush() {
        val text = synchronized(lock) {
            if (!dirty) return
            dirty = false
            storeJson.encodeToString(RouteCacheFile.serializer(), RouteCacheFile(HashMap(memory.routes), HashMap(memory.airports)))
        }
        withContext(Dispatchers.IO) { runCatching { writeAtomically(file, text) } }
    }
}

internal fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}
