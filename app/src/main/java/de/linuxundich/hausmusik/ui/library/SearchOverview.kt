// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.ArtistImages
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.ui.common.Cover
import de.linuxundich.hausmusik.ui.theme.AppIcons

/** What a hit is, across the names services give their categories. */
enum class HitKind { Artist, Album, Track, Playlist, Other }

internal fun hitKind(groupKind: String, item: DidlObject): HitKind {
    val k = groupKind.lowercase().substringAfter(':')
    // What the item says it is wins: bonob answers a track search with albums too.
    return when {
        item.isContainer && item.upnpClass.contains("person") -> HitKind.Artist
        item.isContainer && item.upnpClass.contains("musicAlbum") -> HitKind.Album
        k.startsWith("artist") -> HitKind.Artist
        k.startsWith("album") -> HitKind.Album
        k.startsWith("track") || k.startsWith("song") -> HitKind.Track
        k.startsWith("playlist") -> HitKind.Playlist
        item.upnpClass.contains("person") -> HitKind.Artist
        item.upnpClass.contains("musicAlbum") -> HitKind.Album
        item.upnpClass.contains("playlist") -> HitKind.Playlist
        item.upnpClass.contains("musicTrack") -> HitKind.Track
        else -> HitKind.Other
    }
}

/** One hit and where it comes from: null for the music share, else the service's name. */
data class Hit(val item: DidlObject, val source: String?, val kind: HitKind, val alsoIn: List<String?> = emptyList())

/**
 * The results of all sources, rearranged by kind: artists, albums, tracks,
 * playlists, then whatever else a service offers. The sources' order (the
 * user's library order) stays within each kind; an artist several sources
 * know shows once, with all of them. [top] is the best hit: the artist,
 * album or track whose name is the search, else the first artist.
 */
data class SearchOverviewData(val top: Hit?, val byKind: List<Pair<HitKind, List<Hit>>>)

internal fun arrangeSearch(sections: List<SearchSection>, query: String): SearchOverviewData {
    val hits = sections.flatMap { s -> s.groups.flatMap { g -> g.items.map { Hit(it, s.service, hitKind(g.kind, it)) } } }
    val artists = hits.filter { it.kind == HitKind.Artist }
        .groupBy { ArtistImages.normalize(it.item.title) }
        .map { (_, same) -> same.first().copy(alsoIn = same.drop(1).map { it.source }.distinct() - same.first().source) }
    val byKind = buildList {
        if (artists.isNotEmpty()) add(HitKind.Artist to artists)
        for (k in listOf(HitKind.Album, HitKind.Track, HitKind.Playlist, HitKind.Other)) {
            val list = hits.filter { it.kind == k }.distinctBy { listOf(it.source, it.item.id) }
            if (list.isNotEmpty()) add(k to list)
        }
    }
    val q = ArtistImages.normalize(query)
    val exact = listOf(HitKind.Artist, HitKind.Album, HitKind.Track).firstNotNullOfOrNull { k ->
        byKind.firstOrNull { it.first == k }?.second?.firstOrNull { ArtistImages.normalize(it.item.title) == q }
    }
    val top = exact ?: artists.firstOrNull { ArtistImages.normalize(it.item.title).startsWith(q) }
    return SearchOverviewData(top, byKind.map { (k, l) -> k to l.filter { it !== top && it.item.id != top?.item?.id } }.filter { it.second.isNotEmpty() })
}

@Composable
private fun kindLabel(kind: HitKind): String = stringResource(
    when (kind) {
        HitKind.Artist -> R.string.library_artists
        HitKind.Album -> R.string.library_albums
        HitKind.Track -> R.string.search_tracks
        HitKind.Playlist -> R.string.library_playlists
        HitKind.Other -> R.string.search_more_kinds
    },
)

@Composable
private fun sourceName(source: String?): String = source ?: stringResource(R.string.library_my_music)

/**
 * Search results as a best hit and rows by kind, with chips to show one kind
 * in full. Albums and playlists as cover rows, artists and tracks as lists;
 * each kind shows a few and "All n" opens it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SearchOverview(
    state: SearchState,
    query: String,
    artUrl: (String?) -> String?,
    onOpenContainer: (DidlObject) -> Unit,
    onPlay: (DidlObject) -> Unit,
) {
    when (state) {
        SearchState.Idle -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
            Text(stringResource(R.string.search_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is SearchState.Results -> if (state.empty) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) { Text(stringResource(R.string.search_nothing)) }
        } else {
            val data = remember(state, query) { arrangeSearch(state.sections, query) }
            var filter by rememberSaveable(query) { mutableStateOf<String?>(null) }
            val kind = data.byKind.firstOrNull { it.first.name == filter }
            val list = rememberLazyListState()
            LaunchedEffect(filter) { list.scrollToItem(0) }
            val open = { h: Hit -> if (h.item.isContainer) onOpenContainer(h.item) else onPlay(h.item) }
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "chips") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.search_all)) }) }
                        items(data.byKind, key = { it.first.name }) { (k, hits) ->
                            FilterChip(selected = filter == k.name, onClick = { filter = k.name }, label = { Text("${kindLabel(k)} ${hits.size + if (data.top?.kind == k) 1 else 0}") })
                        }
                    }
                }
                // Sources still searching, or failed: one quiet line each.
                state.sections.filter { it.loading || it.failed }.forEach { s ->
                    item(key = "state/${s.key}") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (s.loading) LoadingIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(if (s.loading) R.string.search_source_busy else R.string.search_source_failed_named, sourceName(s.service)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (kind == null) {
                    data.top?.let { top -> item(key = "top") { TopHit(top, artUrl, { open(top) }, { onPlay(top.item) }) } }
                    data.byKind.forEach { (k, hits) ->
                        item(key = "head/${k.name}") {
                            // The count is the kind's, the best hit included, as on the chip.
                            KindHeader(kindLabel(k), hits.size + if (data.top?.kind == k) 1 else 0, onAll = if (hits.size > PREVIEW[k]!!) ({ filter = k.name }) else null)
                        }
                        val few = hits.take(PREVIEW[k]!!)
                        if (k == HitKind.Album || k == HitKind.Playlist) {
                            item(key = "row/${k.name}") {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(few, key = { i, h -> "$i/${h.item.id}" }) { _, h -> CoverTile(h, artUrl, Modifier.width(132.dp)) { open(h) } }
                                }
                            }
                        } else {
                            itemsIndexed(few, key = { i, h -> "${k.name}/$i/${h.item.id}" }) { _, h -> HitRow(h, artUrl) { open(h) } }
                        }
                    }
                } else {
                    val (k, hits) = kind
                    val all = listOfNotNull(data.top?.takeIf { it.kind == k }) + hits
                    if (k == HitKind.Album || k == HitKind.Playlist) {
                        // A grid of three, built from rows: one list keeps a single scroll.
                        itemsIndexed(all.chunked(3), key = { i, _ -> "grid/$i" }) { _, row ->
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { h -> CoverTile(h, artUrl, Modifier.weight(1f)) { open(h) } }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else {
                        itemsIndexed(all, key = { i, h -> "all/$i/${h.item.id}" }) { _, h -> HitRow(h, artUrl) { open(h) } }
                    }
                }
            }
        }
    }
}

private val PREVIEW = mapOf(HitKind.Artist to 3, HitKind.Album to 10, HitKind.Track to 4, HitKind.Playlist to 10, HitKind.Other to 3)

@Composable
private fun KindHeader(label: String, count: Int, onAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onAll != null) TextButton(onClick = onAll) { Text(stringResource(R.string.search_show_all, count)) }
    }
}

/** The best hit: large picture, kind and source, and play right there. */
@Composable
private fun TopHit(hit: Hit, artUrl: (String?) -> String?, onOpen: () -> Unit, onPlay: () -> Unit) {
    val person = hit.kind == HitKind.Artist
    val image = LocalArtistImage.current
    val picture by androidx.compose.runtime.produceState<String?>(null, hit.item.title, person, image) { if (person) value = image(hit.item.title) }
    Surface(
        onClick = onOpen, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(
                picture ?: artUrl(hit.item.albumArtUri), Modifier.size(96.dp), corner = if (person) 48.dp else 16.dp,
                placeholderColor = MaterialTheme.colorScheme.surfaceVariant, iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                icon = if (person) Icons.Filled.Person else AppIcons.Album,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.search_top_hit, kindLabelSingular(hit.kind)).uppercase(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                )
                Text(hit.item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                hit.item.creator?.takeIf { !person }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onPlay, contentPadding = PaddingValues(horizontal = 16.dp)) {
                        Icon(AppIcons.Play, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.play), Modifier.padding(start = 6.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Sources(listOf(hit.source) + hit.alsoIn)
                }
            }
        }
    }
}

@Composable
private fun kindLabelSingular(kind: HitKind): String = stringResource(
    when (kind) {
        HitKind.Artist -> R.string.search_kind_artist
        HitKind.Album -> R.string.search_kind_album
        HitKind.Track -> R.string.search_kind_track
        HitKind.Playlist -> R.string.search_kind_playlist
        HitKind.Other -> R.string.search_kind_other
    },
)

/** The sources' marks side by side; one source also by name. */
@Composable
private fun Sources(sources: List<String?>) {
    val names = sources.map { sourceName(it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEach { ServiceBadge(it, 18.dp, 5.dp) }
        if (names.size == 1) Text(names.first(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
    }
}

/** An album or playlist: cover with the source's mark, title, artist. */
@Composable
private fun CoverTile(hit: Hit, artUrl: (String?) -> String?, modifier: Modifier, onClick: () -> Unit) {
    // The modifier (a weight in the grid) on the outer box, so the tile takes its share.
    Box(modifier) { ItemMenuBox(hit.item, null, onClick) { m ->
        Column(Modifier.fillMaxWidth().then(m)) {
            Box {
                Cover(
                    artUrl(hit.item.albumArtUri), Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp,
                    placeholderColor = MaterialTheme.colorScheme.surfaceVariant, iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = if (hit.kind == HitKind.Playlist) AppIcons.Playlist else AppIcons.Album,
                )
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) { ServiceBadge(sourceName(hit.source), 20.dp, 6.dp) }
            }
            Text(hit.item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            hit.item.creator?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    }
}

/** An artist, track or other hit: the list row of the library, the source beside it. */
@Composable
private fun HitRow(hit: Hit, artUrl: (String?) -> String?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { ItemRow(hit.item, artUrl) { onClick() } }
        Box(Modifier.padding(end = 16.dp)) { Sources(listOf(hit.source) + hit.alsoIn) }
    }
}
