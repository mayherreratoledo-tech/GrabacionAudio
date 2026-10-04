package com.clases.grabador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estado compartido entre el servicio y la pantalla. */
object RecState {
    val recording = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val elapsedMs = MutableStateFlow(0L)
    val marks = MutableStateFlow<List<Long>>(emptyList())
    val warning = MutableStateFlow<String?>(null)
    val lastSaved = MutableStateFlow<String?>(null)
}

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.clases.grabador.START"
        const val ACTION_PAUSE = "com.clases.grabador.PAUSE"
        const val ACTION_RESUME = "com.clases.grabador.RESUME"
        const val ACTION_STOP = "com.clases.grabador.STOP"
        const val ACTION_MARK = "com.clases.grabador.MARK"
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_SOURCE = "source"
        private const val CHANNEL = "grabacion"
        private const val NOTIF_ID = 1
        private const val MB = 1024L * 1024L
    }

    private var recorder: MediaRecorder? = null
    private var folder = ""
    private var fileName = ""
    private var accumulated = 0L
    private var segStart = 0L
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (RecState.recording.value) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopAndSave()
            ACTION_MARK -> mark()
        }
        return START_NOT_STICKY
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun elapsed() = accumulated + if (RecState.paused.value) 0L else now() - segStart

    private fun notification(): Notification {
        val paused = RecState.paused.value
        fun pi(code: Int, action: String) = PendingIntent.getService(
            this, code, Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "Grabación en pausa" else "Grabando clase…")
            .setContentText("Marcas: ${RecState.marks.value.size} · toca para abrir")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, if (paused) "Reanudar" else "Pausar", pi(1, if (paused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(0, "Marcar", pi(2, ACTION_MARK))
            .addAction(0, "Detener", pi(3, ACTION_STOP))
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    @Suppress("DEPRECATION")
    private fun start(intent: Intent) {
        if (RecState.recording.value) return
        folder = intent.getStringExtra(EXTRA_FOLDER) ?: ""
        val source = intent.getIntExtra(EXTRA_SOURCE, MediaRecorder.AudioSource.MIC)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Grabación de clases", NotificationManager.IMPORTANCE_LOW)
        )
        RecState.marks.value = emptyList()
        RecState.paused.value = false
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        try {
            val dir = File(Repo.root(this), folder).also { it.mkdirs() }
            fileName = "Clase_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date()) + ".aac"
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(source)
            // ADTS: el archivo se escribe de forma continua y se puede recuperar si la app o el teléfono se cierran.
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(File(dir, fileName).absolutePath)
            r.prepare()
            r.start()
            recorder = r
        } catch (e: Exception) {
            RecState.warning.value = "No se pudo iniciar la grabación: ${e.message}"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        accumulated = 0L
        segStart = now()
        RecState.elapsedMs.value = 0L
        RecState.warning.value = null
        RecState.lastSaved.value = null
        RecState.recording.value = true

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grabador:rec").also { it.acquire(6 * 60 * 60 * 1000L) }
        handler.postDelayed(ticker, 1000)
    }

    private fun pause() {
        if (!RecState.recording.value || RecState.paused.value) return
        try { recorder?.pause() } catch (e: Exception) { return }
        accumulated += now() - segStart
        RecState.paused.value = true
        refreshNotification()
    }

    private fun resume() {
        if (!RecState.recording.value || !RecState.paused.value) return
        try { recorder?.resume() } catch (e: Exception) { return }
        segStart = now()
        RecState.paused.value = false
        refreshNotification()
    }

    private fun mark() {
        if (!RecState.recording.value) return
        RecState.marks.value = RecState.marks.value + elapsed()
        refreshNotification()
    }

    private fun tick() {
        RecState.elapsedMs.value = elapsed()
        val free = File(Repo.root(this), folder).usableSpace
        if (free < 30 * MB) {
            stopAndSave("Se acabó el espacio: la grabación se guardó y se detuvo.")
            return
        }
        val warnings = mutableListOf<String>()
        if (free < 300 * MB) warnings += "Poco espacio libre (${free / MB} MB)"
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null) {
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            if (level in 0..14 && !charging) warnings += "Batería baja ($level%)"
        }
        RecState.warning.value = if (warnings.isEmpty()) null else warnings.joinToString(" · ")
    }

    private fun stopAndSave(warn: String? = null) {
        if (!RecState.recording.value) { stopSelf(); return }
        handler.removeCallbacks(ticker)
        try { recorder?.stop() } catch (e: RuntimeException) { /* grabación vacía */ }
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null
        val f = File(File(Repo.root(this), folder), fileName)
        if (f.exists() && f.length() < 1024) {
            f.delete()
        } else {
            Repo.setBookmarks(this, Repo.relPath(folder, fileName), RecState.marks.value)
            RecState.lastSaved.value = fileName
        }
        RecState.recording.value = false
        RecState.paused.value = false
        RecState.warning.value = warn
        try { wake?.release() } catch (e: Exception) { }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (RecState.recording.value) stopAndSave()
        super.onDestroy()
    }
}
