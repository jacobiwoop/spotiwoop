package com.spotywoop.kt.data

data class TrackResult(
    val id: String,
    val name: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val cover: String?,
    val isExplicit: Boolean,
) {
    val durationLabel: String
        get() {
            val total = durationMs / 1000
            return "%d:%02d".format(total / 60, total % 60)
        }
}

data class AlbumResult(
    val id: String,
    val name: String,
    val artists: String,
    val cover: String?,
    val year: Int?,
)

data class ArtistResult(
    val id: String,
    val name: String,
    val cover: String?,
)

data class PlaylistResult(
    val id: String,
    val name: String,
    val cover: String?,
    val owner: String?,
)

data class SearchResults(
    val tracks: List<TrackResult> = emptyList(),
    val albums: List<AlbumResult> = emptyList(),
    val artists: List<ArtistResult> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList(),
)

data class UserPlaylist(
    val id: String,
    val name: String,
    val cover: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val tracks: List<TrackResult> = emptyList(),
)

data class DownloadedTrack(
    val track: TrackResult,
    val localAudioPath: String,
    val localCoverPath: String? = null,
    val downloadedAt: Long = System.currentTimeMillis(),
    val fileSize: Long = 0L,
)

