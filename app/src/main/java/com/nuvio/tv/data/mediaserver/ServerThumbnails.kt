package com.nuvio.tv.data.mediaserver

/*
 * Arc's media-server thumbnail types, kept so its engine compiles unchanged. Lite has no Jellyfin / Emby / Silo
 * sign-in, so no ServerThumbnailSource is ever made and these paths never run.
 */

enum class ServerThumbnailFormat { TRICKPLAY, BIF }

data class ServerTrickplay(val width: Int, val height: Int, val tileWidth: Int, val tileHeight: Int, val thumbnailCount: Int, val intervalMs: Long)

data class ServerTrickplaySet(val mediaSourceId: String, val levels: List<ServerTrickplay>)

class ServerBytes(val bytes: ByteArray, val partial: Boolean, val totalLength: Long?, val truncated: Boolean)

enum class ServerFailure { AUTH_REQUIRED, FORBIDDEN, UNREACHABLE, UNSUPPORTED, STREAM_LIMIT, NOT_FOUND, INCOMPLETE, CERTIFICATE, FAILED }

class ServerException(val failure: ServerFailure, message: String? = null, val network: Boolean = false,
                      val detail: String? = null, val httpStatus: Int? = null) : Exception(message ?: failure.name)

class ServerThumbnailSource internal constructor(
    val serverName: String, val format: ServerThumbnailFormat, val connectionId: String, val itemId: String, val mediaSourceId: String
) {
    val key: String get() = "$connectionId:$itemId:$mediaSourceId"
    suspend fun trickplay(): ServerTrickplaySet? = null
    suspend fun sheet(listedAs: String, width: Int, index: Int, maxBytes: Long): ByteArray = throw ServerException(ServerFailure.UNSUPPORTED)
    suspend fun bif(width: Int, range: LongRange?, maxBytes: Long): ServerBytes = throw ServerException(ServerFailure.UNSUPPORTED)
    override fun toString(): String = "ServerThumbnailSource($serverName, $format)"
}
