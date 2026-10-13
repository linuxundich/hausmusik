// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.sonos.DidlObject
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import de.linuxundich.hausmusik.data.AlbumDetails
import de.linuxundich.hausmusik.data.ArtistDetails
import de.linuxundich.hausmusik.sonos.NowPlaying
import de.linuxundich.hausmusik.ui.common.isWide
import de.linuxundich.hausmusik.ui.common.sheetDragHandle
import de.linuxundich.hausmusik.ui.common.sheetMaxWidth
import de.linuxundich.hausmusik.ui.theme.CoverColors
import java.util.Locale

/** What the sheet shows for the chosen tab. */
sealed interface DetailsUi {
    data object Loading : DetailsUi
    data object None : DetailsUi
    data object Failed : DetailsUi
    data class Artist(val details: ArtistDetails) : DetailsUi
    data class Album(val details: AlbumDetails) : DetailsUi
}

/** Whose details to show: the artist, the album or both, and which one first. */
@kotlinx.serialization.Serializable
data class DetailsTarget(val artist: String?, val album: String?, val albumFirst: Boolean = false) {
    companion object {
        fun of(now: NowPlaying) = DetailsTarget(now.artist?.ifBlank { null }, now.album?.ifBlank { null })

        /**
         * An entry of the library or queue: an artist, an album or a track. Null for
         * what has no artist or album, such as a radio station or a playlist.
         */
        fun of(item: DidlObject): DetailsTarget? = when {
            item.isRadio -> null
            item.upnpClass.contains("person") -> DetailsTarget(item.title, null)
            item.upnpClass.contains("musicAlbum") -> DetailsTarget((item.albumArtist ?: item.creator)?.ifBlank { null }, item.title, albumFirst = true)
            item.upnpClass.contains("musicTrack") && (item.creator != null || item.album != null) ->
                DetailsTarget(item.creator?.ifBlank { null }, item.album?.ifBlank { null })
            else -> null
        }
    }
}

/** Long press in the library opens details where an entry has any; provided by the library screens. */
val LocalItemDetails = androidx.compose.runtime.staticCompositionLocalOf<((DetailsTarget) -> Unit)?> { null }

data class DetailsActions(
    val loadArtist: suspend (String) -> Result<ArtistDetails?>,
    val loadAlbum: suspend (String, String?) -> Result<AlbumDetails?>,
    /** The user agreed to send the names to the services. */
    val onEnable: () -> Unit,
)

/**
 * Background on the artist and the album of the playing track, from free
 * sources (see [de.linuxundich.hausmusik.data.DetailsRepository]). Off until
 * the user agrees; the same switch is in the settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(target: DetailsTarget, enabled: Boolean, colors: CoverColors, actions: DetailsActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val artist = target.artist?.takeIf { it.isNotBlank() }
    val album = target.album?.takeIf { it.isNotBlank() }
    // 0 = artist, 1 = album; survives a rotation.
    var tab by rememberSaveable(target) { mutableIntStateOf(if (artist != null && !(target.albumFirst && album != null)) 0 else 1) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f), contentColor = colors.content,
        sheetMaxWidth = sheetMaxWidth(), dragHandle = sheetDragHandle(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(
                stringResource(R.string.details), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = if (isWide()) 12.dp else 0.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (!enabled) {
                Consent(colors, actions.onEnable)
            } else if (artist == null && album == null) {
                Text(stringResource(R.string.details_nothing_playing), color = colors.content.copy(alpha = 0.72f))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (artist != null) TabChip(stringResource(R.string.details_artist), tab == 0, colors) { tab = 0 }
                    if (album != null) TabChip(stringResource(R.string.details_album), tab == 1, colors) { tab = 1 }
                }
                Spacer(Modifier.height(16.dp))
                val shown = if (tab == 0 && artist != null) 0 else 1
                val state by produceState<DetailsUi>(DetailsUi.Loading, shown, artist, album, attempt) {
                    value = DetailsUi.Loading
                    value = if (shown == 0) {
                        actions.loadArtist(artist.orEmpty()).fold({ if (it == null) DetailsUi.None else DetailsUi.Artist(it) }, { DetailsUi.Failed })
                    } else {
                        actions.loadAlbum(album.orEmpty(), artist).fold({ if (it == null) DetailsUi.None else DetailsUi.Album(it) }, { DetailsUi.Failed })
                    }
                }
                when (val s = state) {
                    DetailsUi.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                    DetailsUi.None -> Message(stringResource(if (shown == 0) R.string.details_none_artist else R.string.details_none_album), colors)
                    DetailsUi.Failed -> {
                        Message(stringResource(R.string.details_failed), colors)
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.details_retry), color = colors.accent) }
                    }
                    is DetailsUi.Artist -> ArtistBody(s.details, colors)
                    is DetailsUi.Album -> AlbumBody(s.details, colors)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, colors: CoverColors, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = colors.content, selectedLabelColor = colors.onAccent, selectedContainerColor = colors.accent,
        ),
    )
}

@Composable
private fun Message(text: String, colors: CoverColors) {
    Text(text, color = colors.content.copy(alpha = 0.72f), modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun Consent(colors: CoverColors, onEnable: () -> Unit) {
    Text(stringResource(R.string.details_consent_body), color = colors.content.copy(alpha = 0.85f))
    Spacer(Modifier.height(16.dp))
    Button(onClick = onEnable) { Text(stringResource(R.string.details_consent_yes)) }
}

@Composable
private fun ArtistBody(d: ArtistDetails, colors: CoverColors) {
    Header(d.imageUrl, round = true, colors) { Text(d.name, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
    val type = when (d.type) {
        "Person" -> stringResource(R.string.details_type_person)
        "Group" -> stringResource(R.string.details_type_group)
        else -> d.type
    }
    val country = d.country?.takeIf { it.length == 2 }?.let { Locale("", it).displayCountry.ifBlank { null } }
    val years = d.begin?.take(4)?.let { b -> if (d.end != null) "$b – ${d.end.take(4)}" else "$b –" }
    Facts(listOfNotNull(type, country, years, d.disambiguation), d.genres, colors)
    Summary(d.summary, d.summaryUrl, d.musicBrainzUrl, colors)
}

@Composable
private fun AlbumBody(d: AlbumDetails, colors: CoverColors) {
    Header(d.imageUrl, round = false, colors) {
        Text(d.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        d.artist?.let { Text(it, color = colors.content.copy(alpha = 0.72f), fontSize = 15.sp) }
    }
    Facts(listOfNotNull(d.type, d.firstRelease?.take(4)), d.genres, colors)
    Summary(d.summary, d.summaryUrl, d.musicBrainzUrl, colors)
}

/** Name (and artist) beside a picture: a round one for an artist, a cover for an album. */
@Composable
private fun Header(imageUrl: String?, round: Boolean, colors: CoverColors, text: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (imageUrl != null) {
            de.linuxundich.hausmusik.ui.common.Cover(
                imageUrl, Modifier.size(72.dp), corner = if (round) 36.dp else 10.dp,
                placeholderColor = colors.subtle, iconColor = colors.content.copy(alpha = 0.5f),
                icon = if (round) de.linuxundich.hausmusik.ui.theme.AppIcons.Speaker else de.linuxundich.hausmusik.ui.theme.AppIcons.Album,
            )
            Spacer(Modifier.width(16.dp))
        }
        Column { text() }
    }
}

@Composable
private fun Facts(facts: List<String>, genres: List<String>, colors: CoverColors) {
    Spacer(Modifier.height(8.dp))
    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = colors.content.copy(alpha = 0.85f), fontSize = 15.sp)
    if (genres.isNotEmpty()) Text(genres.joinToString(" · "), color = colors.accent, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Summary(summary: String?, summaryUrl: String?, mbUrl: String, colors: CoverColors) {
    val uri = LocalUriHandler.current
    Spacer(Modifier.height(16.dp))
    if (summary != null) {
        Text(summary, color = colors.content.copy(alpha = 0.9f), lineHeight = 22.sp)
        Spacer(Modifier.height(4.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (summary != null && summaryUrl != null) {
            // CC BY-SA asks for the source and the license.
            TextButton(onClick = { uri.openUri(summaryUrl) }) { Text(stringResource(R.string.details_source_wikipedia), color = colors.accent) }
        }
        TextButton(onClick = { uri.openUri(mbUrl) }) { Text(stringResource(R.string.details_source_musicbrainz), color = colors.accent) }
    }
}
