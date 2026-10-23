package com.atomkarnickel.overhead.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Foto inkl. Pflichtangaben: Fotograf und Link zur Originalseite (Nutzungsbedingungen planespotters.net). */
data class AircraftPhoto(val imageUrl: String, val pageUrl: String, val photographer: String)

@Serializable
private data class PsSize(val width: Int = 0, val height: Int = 0)

@Serializable
private data class PsImage(val src: String, val size: PsSize? = null)

@Serializable
private data class PsPhoto(
    val thumbnail: PsImage? = null,
    @SerialName("thumbnail_large") val thumbnailLarge: PsImage? = null,
    val link: String? = null,
    val photographer: String? = null,
)

@Serializable
private data class PsResponse(val photos: List<PsPhoto> = emptyList())

class PhotoService(private val client: OkHttpClient, private val baseUrl: String = "https://api.planespotters.net") {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = object : LinkedHashMap<String, AircraftPhoto?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AircraftPhoto?>?) = size > 64
    }

    /** Null, wenn kein Foto existiert. Wirft bei Netzwerkfehlern. */
    suspend fun photo(hex: String): AircraftPhoto? {
        synchronized(cache) { if (cache.containsKey(hex)) return cache[hex] }
        val res = client.getText("${baseUrl.trimEnd('/')}/pub/photos/hex/$hex")
        if (!res.ok) throw HttpException(res.code, "planespotters.net: HTTP ${res.code}")
        val p = json.decodeFromString<PsResponse>(res.body).photos.firstOrNull()
        val photo = p?.let {
            val img = it.thumbnailLarge ?: it.thumbnail
            if (img == null || it.link == null || !img.src.startsWith("https://") || !it.link.startsWith("https://")) null
            else AircraftPhoto(img.src, it.link, it.photographer?.takeIf { n -> n.isNotBlank() } ?: "unbekannt")
        }
        synchronized(cache) { cache[hex] = photo }
        return photo
    }

    suspend fun image(url: String): ByteArray? = client.getBytes(url)
}
