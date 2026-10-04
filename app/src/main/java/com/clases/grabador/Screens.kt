package com.clases.grabador

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date

// ───────────── utilidades ─────────────

fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

@Composable
fun TextDialog(title: String, initial: String, onOk: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } }
    )
}

@Composable
fun ConfirmDialog(title: String, onOk: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onOk) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } }
    )
}

@Composable
fun MenuButton(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮", fontSize = 20.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label)
    }
}

sealed class LibDialog {
    object NewFolder : LibDialog()
    data class RenameFolder(val name: String) : LibDialog()
    data class DeleteFolder(val name: String) : LibDialog()
    data class RenameFile(val rel: String) : LibDialog()
    data class MoveFile(val rel: String) : LibDialog()
    data class DeleteFile(val rel: String) : LibDialog()
}

// ───────────── biblioteca ─────────────

@Composable
fun LibraryScreen(folder: String, onFolder: (String) -> Unit, onRecord: () -> Unit, onPlay: (String) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<LibDialog?>(null) }
    val recording by RecState.recording.collectAsState()
    val folders = remember(tick, folder) { if (folder.isEmpty()) Repo.folders(ctx) else emptyList() }
    val files = remember(tick, folder) { Repo.files(ctx, folder) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (folder.isNotEmpty()) TextButton(onClick = { onFolder("") }) { Text("← Atrás") }
            Text(
                if (folder.isEmpty()) "Mis clases" else "📁 $folder",
                style = MaterialTheme.typography.titleLarge
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRecord) { Text(if (recording) "🔴 Grabando… (abrir)" else "🎙 Grabar") }
            if (folder.isEmpty()) OutlinedButton(onClick = { dialog = LibDialog.NewFolder }) { Text("+ Carpeta") }
        }
        Spacer(Modifier.height(8.dp))

        if (folders.isEmpty() && files.isEmpty()) {
            Text("Aún no hay nada aquí. Toca «Grabar» para empezar.")
        }

        LazyColumn(Modifier.weight(1f)) {
            items(folders) { name ->
                Row(
                    Modifier.fillMaxWidth().clickable { onFolder(name) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("📁 $name", fontWeight = FontWeight.Bold)
                        Text("${Repo.files(ctx, name).size} audios", style = MaterialTheme.typography.bodySmall)
                    }
                    MenuButton(
                        listOf(
                            "Renombrar" to { dialog = LibDialog.RenameFolder(name) },
                            "Eliminar" to { dialog = LibDialog.DeleteFolder(name) }
                        )
                    )
                }
            }
            items(files) { f ->
                val rel = Repo.relPath(folder, f.name)
                val saved = Repo.position(ctx, rel)
                val marks = Repo.bookmarks(ctx, rel).size
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified()))
                val info = buildString {
                    append(date).append(" · ").append(f.length() / (1024 * 1024)).append(" MB")
                    if (saved > 1000) append(" · retomar en ").append(fmt(saved))
                    if (marks > 0) append(" · 🔖").append(marks)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onPlay(rel) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("🎧 " + f.nameWithoutExtension, fontWeight = FontWeight.Bold)
                        Text(info, style = MaterialTheme.typography.bodySmall)
                    }
                    MenuButton(
                        listOf(
                            "Mover a carpeta" to { dialog = LibDialog.MoveFile(rel) },
                            "Renombrar" to { dialog = LibDialog.RenameFile(rel) },
                            "Compartir / exportar" to {
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/aac"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(i, "Compartir audio"))
                            },
                            "Eliminar" to { dialog = LibDialog.DeleteFile(rel) }
                        )
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> {}
        is LibDialog.NewFolder -> TextDialog("Nueva carpeta", "", { name ->
            if (!Repo.createFolder(ctx, name)) toast(ctx, "No se pudo crear (¿ya existe?)")
            dialog = null; tick++
        }, { dialog = null })
        is LibDialog.RenameFolder -> TextDialog("Renombrar carpeta", d.name, { name ->
            if (!Repo.renameFolder(ctx, d.name, name)) toast(ctx, "No se pudo renombrar")
            dialog = null; tick++
        }, { dialog = null })
        is LibDialog.DeleteFolder -> ConfirmDialog("¿Eliminar la carpeta «${d.name}»? (solo si está vacía)", {
            if (!Repo.deleteFolder(ctx, d.name)) toast(ctx, "La carpeta tiene audios; muévelos o elimínalos primero")
            dialog = null; tick++
        }, { dialog = null })
        is LibDialog.RenameFile -> TextDialog("Renombrar audio", File(d.rel).nameWithoutExtension, { name ->
            if (!Repo.renameFile(ctx, d.rel, name)) toast(ctx, "No se pudo renombrar")
            dialog = null; tick++
        }, { dialog = null })
        is LibDialog.DeleteFile -> ConfirmDialog("¿Eliminar este audio? No se puede deshacer.", {
            Repo.deleteFile(ctx, d.rel)
            dialog = null; tick++
        }, { dialog = null })
        is LibDialog.MoveFile -> {
            val current = d.rel.substringBeforeLast('/', "")
            val options = (listOf("") + Repo.folders(ctx)).filter { it != current }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Mover a…") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        options.forEach { dest ->
                            TextButton(onClick = {
                                if (!Repo.moveFile(ctx, d.rel, dest)) toast(ctx, "No se pudo mover")
                                dialog = null; tick++
                            }) { Text(if (dest.isEmpty()) "(Sin carpeta)" else "📁 $dest") }
                        }
                        if (options.isEmpty()) Text("Crea primero una carpeta.")
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
            )
        }
    }
}

// ───────────── grabadora ─────────────

@Composable
fun RecorderScreen(initialFolder: String, onBack: (String) -> Unit) {
    val ctx = LocalContext.current
    val rec by RecState.recording.collectAsState()
    val paused by RecState.paused.collectAsState()
    val elapsed by RecState.elapsedMs.collectAsState()
    val marks by RecState.marks.collectAsState()
    val warning by RecState.warning.collectAsState()
    val saved by RecState.lastSaved.collectAsState()

    var target by remember { mutableStateOf(initialFolder) }
    var source by remember { mutableIntStateOf(MediaRecorder.AudioSource.MIC) }
    val folders = remember { Repo.folders(ctx) }
    val freeMb = remember(rec) { Repo.root(ctx).usableSpace / (1024 * 1024) }

    fun startRec() {
        val i = Intent(ctx, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_FOLDER, target)
            .putExtra(RecorderService.EXTRA_SOURCE, source)
        ContextCompat.startForegroundService(ctx, i)
    }

    fun send(action: String) {
        ctx.startService(Intent(ctx, RecorderService::class.java).setAction(action))
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) startRec()
        else toast(ctx, "Sin permiso de micrófono no se puede grabar")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onBack(target) }) { Text("← Biblioteca") }
            Text("Grabar clase", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            fmt(elapsed),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        )
        Text(
            when {
                rec && paused -> "⏸ En pausa"
                rec -> "🔴 Grabando (puedes apagar la pantalla)"
                else -> "Listo · espacio libre: $freeMb MB"
            }
        )
        warning?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
        saved?.let { Text("✅ Guardado: $it") }
        Spacer(Modifier.height(12.dp))

        if (!rec) {
            Button(onClick = {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(perms.toTypedArray())
            }, modifier = Modifier.fillMaxWidth()) { Text("🎙 Iniciar grabación") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { send(if (paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE) },
                    modifier = Modifier.weight(1f)
                ) { Text(if (paused) "▶ Reanudar" else "⏸ Pausar") }
                OutlinedButton(
                    onClick = { send(RecorderService.ACTION_MARK) },
                    modifier = Modifier.weight(1f)
                ) { Text("🔖 Marcar") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { send(RecorderService.ACTION_STOP) }, modifier = Modifier.fillMaxWidth()) {
                Text("⏹ Detener y guardar")
            }
        }

        if (marks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Marcas: " + marks.joinToString("  ") { "🔖" + fmt(it) })
        }

        Spacer(Modifier.height(16.dp))
        Text("Guardar en:", fontWeight = FontWeight.Bold)
        RadioRow("(Sin carpeta)", target.isEmpty(), enabled = !rec) { target = "" }
        folders.forEach { RadioRow("📁 $it", target == it, enabled = !rec) { target = it } }

        Spacer(Modifier.height(12.dp))
        Text("Micrófono:", fontWeight = FontWeight.Bold)
        listOf(
            "Normal" to MediaRecorder.AudioSource.MIC,
            "Voz (reduce ruido de fondo)" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "Cámara (suele captar mejor a distancia)" to MediaRecorder.AudioSource.CAMCORDER
        ).forEach { (label, src) -> RadioRow(label, source == src, enabled = !rec) { source = src } }
        Text("Si la voz del profesor sale baja, prueba «Cámara».", style = MaterialTheme.typography.bodySmall)

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("Evitar que el sistema cierre la grabación (recomendado)") }
        }
    }
}

// ───────────── reproductor ─────────────

@Composable
fun PlayerScreen(rel: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val file = remember(rel) { Repo.file(ctx, rel) }
    val mp = remember(rel) { MediaPlayer() }
    var ready by remember(rel) { mutableStateOf(false) }
    var error by remember(rel) { mutableStateOf(false) }
    var playing by remember(rel) { mutableStateOf(false) }
    var pos by remember(rel) { mutableLongStateOf(0L) }
    var dur by remember(rel) { mutableLongStateOf(0L) }
    var speed by remember(rel) { mutableFloatStateOf(1f) }
    var resumedAt by remember(rel) { mutableLongStateOf(0L) }
    var marks by remember(rel) { mutableStateOf(Repo.bookmarks(ctx, rel)) }
    var dragging by remember(rel) { mutableStateOf(false) }
    var sliderPos by remember(rel) { mutableFloatStateOf(0f) }

    fun savePos() {
        if (!ready) return
        try { Repo.setPosition(ctx, rel, mp.currentPosition.toLong()) } catch (e: Exception) { }
    }

    DisposableEffect(rel) {
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { p ->
                dur = p.duration.toLong()
                val saved = Repo.position(ctx, rel)
                if (saved > 1000 && saved < dur - 2000) {
                    p.seekTo(saved.toInt()); pos = saved; resumedAt = saved
                }
                ready = true
            }
            mp.setOnCompletionListener {
                playing = false
                Repo.setPosition(ctx, rel, 0L)
                pos = 0L
                try { mp.seekTo(0) } catch (e: Exception) { }
            }
            mp.setOnErrorListener { _, _, _ -> error = true; true }
            mp.prepareAsync()
        } catch (e: Exception) {
            error = true
        }
        onDispose {
            savePos()
            try { mp.release() } catch (e: Exception) { }
        }
    }

    LaunchedEffect(rel, ready) {
        var n = 0
        while (ready) {
            delay(500)
            try {
                if (!dragging) pos = mp.currentPosition.toLong()
                n++
                if (playing && n % 10 == 0) savePos()
            } catch (e: Exception) { }
        }
    }

    fun applySpeed() {
        try { mp.playbackParams = PlaybackParams().setSpeed(speed) } catch (e: Exception) { }
    }

    fun togglePlay() {
        if (!ready) return
        if (playing) {
            mp.pause(); playing = false; savePos()
        } else {
            mp.start(); applySpeed(); playing = true
        }
    }

    fun seekTo(ms: Long) {
        val t = ms.coerceIn(0L, dur)
        mp.seekTo(t.toInt()); pos = t
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Biblioteca") }
        }
        Text(file.nameWithoutExtension, style = MaterialTheme.typography.titleLarge)
        if (error) Text("No se pudo abrir este audio.", color = MaterialTheme.colorScheme.error)
        if (resumedAt > 0) Text("Retomado donde lo dejaste (${fmt(resumedAt)})", style = MaterialTheme.typography.bodySmall)
        Text("La posición se guarda sola al pausar o salir.", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        Slider(
            value = if (dragging) sliderPos else pos.toFloat(),
            onValueChange = { dragging = true; sliderPos = it },
            onValueChangeFinished = { seekTo(sliderPos.toLong()); dragging = false; savePos() },
            valueRange = 0f..maxOf(dur, 1L).toFloat(),
            enabled = ready
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(if (dragging) sliderPos.toLong() else pos))
            Text(fmt(dur))
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { seekTo(pos - 15000) }, modifier = Modifier.weight(1f)) { Text("⏪ 15s") }
            Button(onClick = { togglePlay() }, modifier = Modifier.weight(1.3f)) { Text(if (playing) "⏸ Pausa" else "▶ Play") }
            OutlinedButton(onClick = { seekTo(pos + 15000) }, modifier = Modifier.weight(1f)) { Text("15s ⏩") }
        }

        Spacer(Modifier.height(12.dp))
        Text("Velocidad", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                val label = "${s}x"
                val onClick = { speed = s; if (playing) applySpeed() }
                if (speed == s) Button(onClick = onClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f)) { Text(label, fontSize = 13.sp) }
                else OutlinedButton(onClick = onClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f)) { Text(label, fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = {
            marks = (marks + pos).distinct().sorted()
            Repo.setBookmarks(ctx, rel, marks)
        }, modifier = Modifier.fillMaxWidth(), enabled = ready) { Text("🔖 Marcar este momento") }

        if (marks.isNotEmpty()) {
            Text("Marcas", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            marks.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { seekTo(m) }, modifier = Modifier.weight(1f)) {
                        Text("🔖 ${fmt(m)}", modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = {
                        marks = marks - m
                        Repo.setBookmarks(ctx, rel, marks)
                    }) { Text("✕") }
                }
            }
        }
    }
}
