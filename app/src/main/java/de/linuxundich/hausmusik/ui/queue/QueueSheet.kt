package de.linuxundich.hausmusik.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.SourceKind
import de.linuxundich.hausmusik.ui.common.Cover
import de.linuxundich.hausmusik.ui.common.formatTime
import de.linuxundich.hausmusik.ui.theme.CoverColors

/** The queue of the selected room. Reordering and removing follow in phase 2. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueSheet(
    group: GroupState,
    colors: CoverColors,
    load: suspend () -> Result<List<DidlObject>>,
    artUrl: (String?) -> String?,
    onPlayTrack: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf<List<DidlObject>?>(null) }
    LaunchedEffect(group.id, group.queueLength) { items = load().getOrDefault(emptyList()) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = group.nowPlaying?.queuePosition
    val list = rememberLazyListState()
    LaunchedEffect(items != null) { if (current != null && current > 2) list.scrollToItem(current - 2) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f), contentColor = colors.content,
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.queue_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(pluralStringResource(R.plurals.queue_count, items?.size ?: group.queueLength, items?.size ?: group.queueLength), fontSize = 12.sp, color = colors.content.copy(alpha = 0.6f))
            if (group.nowPlaying?.kind != SourceKind.Queue && group.nowPlaying != null) {
                Text(stringResource(R.string.queue_not_active), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        val loaded = items
        if (loaded == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { LoadingIndicator(color = colors.accent) }
            return@ModalBottomSheet
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(loaded) { i, t ->
                val nr = i + 1
                val playing = nr == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (playing) colors.accent.copy(alpha = 0.2f) else colors.backgroundBottom.copy(alpha = 0f))
                        .clickable { onPlayTrack(nr) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (playing) "▶" else "$nr", modifier = Modifier.width(28.dp), fontSize = 12.sp,
                        color = if (playing) colors.accent else colors.content.copy(alpha = 0.5f),
                    )
                    Cover(artUrl(t.albumArtUri), Modifier.size(40.dp), corner = 8.dp, placeholderColor = colors.subtle, iconColor = colors.content.copy(alpha = 0.4f))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.creator.orEmpty(), fontSize = 12.sp, color = colors.content.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatTime(t.durationMs), fontSize = 12.sp, color = colors.content.copy(alpha = 0.5f))
                }
            }
        }
    }
}
