package com.lekathedon.visionlite.addons

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Stremio add-on protocol, only the fields Nuvio Vision reads. Unknown fields are ignored. */
@Serializable
data class Manifest(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String? = null,
    val logo: String? = null,
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<CatalogDef> = emptyList(),
    val idPrefixes: List<String>? = null
)

@Serializable
data class CatalogDef(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<ExtraDef> = emptyList(),
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList()
) {
    val supportsSearch get() = extra.any { it.name == "search" } || "search" in extraSupported
    val needsSearch get() = extra.any { it.name == "search" && it.isRequired } || "search" in extraRequired
    val supportsSkip get() = extra.any { it.name == "skip" } || "skip" in extraSupported
    /** A Home row: no required extra (search/genre-only catalogs are not rows). */
    val isBoardRow get() = extra.none { it.isRequired } && extraRequired.isEmpty()
}

@Serializable
data class ExtraDef(val name: String, val isRequired: Boolean = false, val options: List<String> = emptyList())

@Serializable
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val runtime: String? = null
)

@Serializable
data class Meta(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val runtime: String? = null,
    val cast: List<String> = emptyList(),
    val videos: List<Video> = emptyList()
)

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null
) {
    val label: String get() = title ?: name ?: "Episode ${episode ?: ""}"
}

@Serializable
data class Stream(
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val behaviorHints: StreamHints? = null
) {
    /** Text the add-on puts in the list: name = source/quality, title/description = file details. */
    val details: String get() = (description ?: title).orEmpty()
}

@Serializable
data class StreamHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val proxyHeaders: ProxyHeaders? = null
)

@Serializable
data class ProxyHeaders(val request: Map<String, String> = emptyMap())

@Serializable
internal data class CatalogResponse(val metas: List<MetaPreview> = emptyList())

@Serializable
internal data class MetaResponse(val meta: Meta? = null)

@Serializable
internal data class StreamResponse(val streams: List<Stream> = emptyList())

/** An installed add-on: where it lives + what it said it can do. */
@Serializable
data class Addon(
    /** Base URL without "/manifest.json". */
    val base: String,
    val manifest: Manifest
) {
    /** Whether this add-on answers [resource] ("meta", "stream", ...) for [type] and [id]. */
    fun serves(resource: String, type: String, id: String): Boolean {
        val entry = manifest.resources.firstOrNull { nameOf(it) == resource } ?: return false
        val obj = entry as? JsonObject
        val types = obj?.strings("types") ?: manifest.types
        val prefixes = obj?.strings("idPrefixes") ?: manifest.idPrefixes
        return type in types && (prefixes == null || prefixes.any { id.startsWith(it) })
    }

    private fun nameOf(e: JsonElement): String? = when (e) {
        is JsonPrimitive -> e.content
        is JsonObject -> (e["name"] as? JsonPrimitive)?.content
        else -> null
    }

    private fun JsonObject.strings(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
}
