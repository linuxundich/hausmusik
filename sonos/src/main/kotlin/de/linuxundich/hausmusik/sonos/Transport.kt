package de.linuxundich.hausmusik.sonos

enum class TransportState {
    Playing, Paused, Stopped, Transitioning, NoMedia;

    companion object {
        fun parse(value: String?): TransportState = when (value) {
            "PLAYING" -> Playing
            "PAUSED_PLAYBACK" -> Paused
            "TRANSITIONING" -> Transitioning
            "NO_MEDIA_PRESENT" -> NoMedia
            else -> Stopped
        }
    }
}

enum class RepeatMode { Off, All, One }

/** Sonos packs shuffle and repeat into one PlayMode string. */
data class PlayMode(val shuffle: Boolean, val repeat: RepeatMode) {
    val sonosValue: String
        get() = when {
            shuffle && repeat == RepeatMode.Off -> "SHUFFLE_NOREPEAT"
            shuffle && repeat == RepeatMode.All -> "SHUFFLE"
            shuffle -> "SHUFFLE_REPEAT_ONE"
            repeat == RepeatMode.All -> "REPEAT_ALL"
            repeat == RepeatMode.One -> "REPEAT_ONE"
            else -> "NORMAL"
        }

    companion object {
        val Normal = PlayMode(false, RepeatMode.Off)

        fun parse(value: String?): PlayMode = when (value) {
            "SHUFFLE_NOREPEAT" -> PlayMode(true, RepeatMode.Off)
            "SHUFFLE" -> PlayMode(true, RepeatMode.All)
            "SHUFFLE_REPEAT_ONE" -> PlayMode(true, RepeatMode.One)
            "REPEAT_ALL" -> PlayMode(false, RepeatMode.All)
            "REPEAT_ONE" -> PlayMode(false, RepeatMode.One)
            else -> Normal
        }
    }
}

data class PositionInfo(
    val trackNumber: Int,
    val durationMs: Long?,
    val positionMs: Long?,
    val trackUri: String?,
    val track: DidlObject?,
)

data class MediaInfo(
    val numberOfTracks: Int,
    val currentUri: String?,
    val currentUriMetadata: DidlObject?,
)

/** The transport actions a source allows right now (radio cannot skip, for example). */
data class TransportActions(val raw: Set<String>) {
    val canSeek: Boolean get() = "Seek" in raw || "X_DLNA_SeekTime" in raw
    val canNext: Boolean get() = "Next" in raw
    val canPrevious: Boolean get() = "Previous" in raw
    val canPause: Boolean get() = "Pause" in raw

    companion object {
        fun parse(value: String?): TransportActions =
            TransportActions(value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }
}

/** What a room plays, reduced to what the UI shows. */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val album: String?,
    /** Station name for radio, otherwise null. */
    val station: String?,
    val albumArtUri: String?,
    val durationMs: Long?,
    val positionMs: Long?,
    val kind: SourceKind,
    val queuePosition: Int?,
) {
    companion object {
        /**
         * Combines GetPositionInfo and GetMediaInfo. GetPositionInfo is the
         * source of truth for the track: after a queue change, events still
         * carry the old title for a while (learned in Gnomos 0.28.4).
         */
        fun from(position: PositionInfo, media: MediaInfo?): NowPlaying? {
            val trackUri = position.trackUri.orEmpty()
            val mediaUri = media?.currentUri.orEmpty()
            val kind = SourceKind.of(trackUri, mediaUri)
            val track = position.track
            if (kind == SourceKind.Empty && track == null) return null
            if (kind == SourceKind.Radio) {
                val station = media?.currentUriMetadata?.title?.ifBlank { null } ?: track?.title
                val stream = track?.streamContent
                val (artist, title) = splitStreamContent(stream)
                return NowPlaying(
                    title = title ?: station.orEmpty(),
                    artist = artist,
                    album = null,
                    station = station,
                    albumArtUri = track?.albumArtUri ?: media?.currentUriMetadata?.albumArtUri,
                    durationMs = null,
                    positionMs = null,
                    kind = kind,
                    queuePosition = null,
                )
            }
            return NowPlaying(
                title = track?.title?.ifBlank { null } ?: trackUri.substringAfterLast('/'),
                artist = track?.creator ?: track?.albumArtist,
                album = track?.album,
                station = null,
                albumArtUri = track?.albumArtUri,
                durationMs = position.durationMs,
                positionMs = position.positionMs,
                kind = kind,
                queuePosition = position.trackNumber.takeIf { kind == SourceKind.Queue && it > 0 },
            )
        }

        /** Radio streams send "Artist - Title"; anything else stays the title. */
        internal fun splitStreamContent(text: String?): Pair<String?, String?> {
            if (text.isNullOrBlank() || text.startsWith("ZPSTR_")) return null to null
            val i = text.indexOf(" - ")
            return if (i > 0) text.substring(0, i).trim() to text.substring(i + 3).trim() else null to text.trim()
        }
    }
}

enum class SourceKind {
    Queue, Radio, LineIn, Tv, GroupMember, Stream, Empty;

    companion object {
        fun of(trackUri: String, mediaUri: String): SourceKind = when {
            trackUri.startsWith("x-rincon:") || mediaUri.startsWith("x-rincon:") -> GroupMember
            mediaUri.startsWith("x-rincon-queue:") -> Queue
            mediaUri.startsWith("x-rincon-stream:") || trackUri.startsWith("x-rincon-stream:") -> LineIn
            mediaUri.startsWith("x-sonos-htastream:") -> Tv
            mediaUri.startsWith("x-sonosapi-stream:") || mediaUri.startsWith("x-rincon-mp3radio:") ||
                mediaUri.startsWith("x-sonosapi-radio:") || mediaUri.startsWith("aac:") ||
                trackUri.startsWith("x-rincon-mp3radio:") || trackUri.startsWith("x-sonosapi-stream:") -> Radio
            mediaUri.isEmpty() && trackUri.isEmpty() -> Empty
            else -> Stream
        }
    }
}

data class BrowseResult(
    val items: List<DidlObject>,
    val totalMatches: Int,
    val updateId: Int?,
)
