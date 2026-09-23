package io.github.kunkai2002.splashclean

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kunkai2002.splashclean.capture.SnapNode
import io.github.kunkai2002.splashclean.capture.Snapshot
import io.github.kunkai2002.splashclean.capture.SnapshotStore
import io.github.kunkai2002.splashclean.capture.UserRule
import io.github.kunkai2002.splashclean.capture.UserRules
import io.github.kunkai2002.splashclean.ui.SplashCleanTheme
import io.github.kunkai2002.splashclean.ui.SubPageBar
import io.github.kunkai2002.splashclean.ui.formatTime
import java.io.File

/** "Teach it": pick a captured screen, tap the skip button, save a rule. */
class TeachActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = intent.getStringExtra(EXTRA_PATH)
        setContent {
            SplashCleanTheme {
                var folder by remember { mutableStateOf(initial?.let(::File)) }
                Scaffold { padding ->
                    Box(Modifier.padding(padding)) {
                        val f = folder
                        if (f == null) {
                            SnapshotList(onBack = { finish() }) { folder = it }
                        } else {
                            val snap = remember(f) { SnapshotStore.load(f) }
                            if (snap == null) {
                                Text(stringResource(R.string.teach_load_failed), Modifier.padding(16.dp))
                            } else {
                                TeachEditor(f, snap, onBack = { if (initial != null) finish() else folder = null }) { finish() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotList(onBack: () -> Unit, onPick: (File) -> Unit) {
    val context = LocalContext.current
    val list = remember { SnapshotStore.list(context).mapNotNull { f -> SnapshotStore.load(f)?.let { f to it } } }
    Column {
        SubPageBar(stringResource(R.string.teach_title), onBack)
        Text(stringResource(R.string.set_teach_desc), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        if (list.isEmpty()) Text(stringResource(R.string.teach_empty), modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(list, key = { it.first.name }) { (f, s) ->
                Column(Modifier.fillMaxWidth().clickable { onPick(f) }.padding(16.dp)) {
                    Text(s.appName, style = MaterialTheme.typography.bodyLarge)
                    Text(formatTime(s.time) + " · " + (s.activityId ?: s.appId), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TeachEditor(folder: File, snap: Snapshot, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(folder) {
        SnapshotStore.screenshot(folder).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var tap by remember { mutableStateOf<Offset?>(null) }
    var picked by remember { mutableStateOf<SnapNode?>(null) }
    var usePosition by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("splash") }
    val candidates = remember(tap) {
        val t = tap ?: return@remember emptyList()
        snap.nodes.filter { it.visible && it.width > 0 && it.height > 0 && it.contains(t.x.toInt(), t.y.toInt()) }
            .sortedBy { it.width.toLong() * it.height }
            .take(6)
    }
    val sx = if (viewSize.width > 0) viewSize.width.toFloat() / snap.screenWidth else 1f
    val sy = if (viewSize.height > 0) viewSize.height.toFloat() / snap.screenHeight else 1f

    Column(Modifier.fillMaxSize()) {
        SubPageBar(snap.appName, onBack)
        Text(stringResource(R.string.teach_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.weight(1f).fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .aspectRatio(snap.screenWidth.toFloat() / snap.screenHeight)
                    .background(Color(0xFF30343A))
                    .onSizeChanged { viewSize = it }
                    .pointerInput(snap) {
                        detectTapGestures { o ->
                            tap = Offset(o.x / sx, o.y / sy)
                            picked = null
                            usePosition = false
                        }
                    },
            ) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                Canvas(Modifier.fillMaxSize()) {
                    if (bitmap == null) {
                        snap.nodes.filter { it.visible && it.clickable }.forEach { n ->
                            drawRect(Color(0x55FFFFFF), Offset(n.left * sx, n.top * sy), Size(n.width * sx, n.height * sy), style = Stroke(1f))
                        }
                    }
                    val sel = picked ?: candidates.firstOrNull()
                    if (sel != null && !usePosition) {
                        drawRect(Color(0xFFFF5252), Offset(sel.left * sx, sel.top * sy), Size(sel.width * sx, sel.height * sy), style = Stroke(4f))
                    }
                    tap?.let { drawCircle(Color(0xFFFFEB3B), 10f, Offset(it.x * sx, it.y * sy)) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = kind == "splash", onClick = { kind = "splash" }, label = { Text(stringResource(R.string.kind_splash)) })
                FilterChip(selected = kind == "popup", onClick = { kind = "popup" }, label = { Text(stringResource(R.string.kind_popup)) })
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(candidates, key = { it.id }) { n ->
                    val sel = UserRules.selectorsFor(n).firstOrNull()
                    Row(Modifier.fillMaxWidth().clickable { picked = n; usePosition = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !usePosition && (picked ?: candidates.first()) == n, onClick = { picked = n; usePosition = false })
                        Column {
                            Text(sel?.first ?: stringResource(R.string.teach_no_selector), style = MaterialTheme.typography.bodySmall)
                            Text("${n.name?.substringAfterLast('.')} ${n.width}×${n.height}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (tap != null) {
                    item {
                        Row(Modifier.fillMaxWidth().clickable { usePosition = true }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = usePosition, onClick = { usePosition = true })
                            Text(stringResource(R.string.teach_use_position), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Button(
                enabled = tap != null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                onClick = {
                    val t = tap ?: return@Button
                    val node = picked ?: candidates.firstOrNull()
                    val sel = node?.let { UserRules.selectorsFor(it).firstOrNull() }
                    val rule = if (!usePosition && sel != null) {
                        UserRule(
                            id = System.currentTimeMillis(), appId = snap.appId, appName = snap.appName,
                            activityId = snap.activityId, kind = kind, selector = sel.first, fastQuery = sel.second,
                        )
                    } else {
                        UserRule(
                            id = System.currentTimeMillis(), appId = snap.appId, appName = snap.appName,
                            activityId = snap.activityId, kind = kind,
                            relX = t.x / snap.screenWidth, relY = t.y / snap.screenHeight,
                        )
                    }
                    val r = UserRules.add(context, rule)
                    Toast.makeText(
                        context,
                        if (r.isSuccess) context.getString(R.string.teach_saved) else context.getString(R.string.teach_save_failed, r.exceptionOrNull()?.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                    if (r.isSuccess) onSaved()
                },
            ) { Text(stringResource(R.string.teach_save)) }
        }
    }
}
