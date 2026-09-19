package net.bitplane.android.microphone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri

class MicrophoneService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    // Native Sample-Rate des Geräts (per Messung ermittelt: 16000 Hz auf dem
    // Test-Gerät). Trifft man die native Rate, entfällt der Resampler ->
    // deutlich geringere Latenz. Für Sprach-Monitoring ist 16 kHz ausreichend.
    // Wird in onCreate ggf. auf den tatsächlichen Gerätewert gesetzt.
    private var mSampleRate = 16000
    private val mFormat = AudioFormat.ENCODING_PCM_16BIT

    // Mono für Ein- und Ausgabe: halbiert die Datenmenge pro Puffer.
    // (Der frühere Lautstärke-Einbruch kam von der Eingangsquelle
    // VOICE_PERFORMANCE, nicht von Mono – wir nutzen jetzt MIC.)
    private val mInChannelConfig = AudioFormat.CHANNEL_IN_MONO
    private val mOutChannelConfig = AudioFormat.CHANNEL_OUT_MONO

    private var mActive = false

    private lateinit var mSharedPreferences: SharedPreferences
    private var mAudioOutput: AudioTrack? = null
    private var mAudioInput: AudioRecord? = null
    private var mInBufferSize = 0
    // Native Hardware-Puffergröße in Frames (aus AudioManager); bestimmt die
    // ideale Chunk-Größe für minimale Latenz ohne Aussetzer.
    private var mNativeFramesPerBuffer = 0
    private lateinit var mNotificationManager: NotificationManagerCompat
    private lateinit var mBroadcastReceiver: MicrophoneReceiver

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        Log.d(AppPreferences.APP_TAG, "Creating mic service")

        // notification service
        mNotificationManager = NotificationManagerCompat.from(applicationContext)
        mBroadcastReceiver = MicrophoneReceiver()

        // --- Selbstoptimierung: Audio-Parameter des jeweiligen Geräts ermitteln ---
        // Ziel: auf jedem Handy automatisch die native Sample-Rate und Hardware-
        // Puffergröße treffen (kein Resampler, minimale Latenz), mit robusten
        // Fallbacks, falls ein Gerät die Werte nicht meldet.
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mSampleRate = pickSampleRate(am)
        mNativeFramesPerBuffer =
            am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        if (BuildConfig.DEBUG) {
            Log.d(
                AppPreferences.APP_TAG,
                "Audio-Setup: SampleRate=$mSampleRate, FramesPerBuffer=$mNativeFramesPerBuffer"
            )
        }

        // create input and output streams. getMinBufferSize kann bei ungültiger
        // Konfiguration einen Fehler (negativ) liefern -> auf einen sicheren Wert
        // aus der nativen Puffergröße ausweichen.
        mInBufferSize = safeMinBufferSize(
            AudioRecord.getMinBufferSize(mSampleRate, mInChannelConfig, mFormat)
        )
        val mOutBufferSize = safeMinBufferSize(
            AudioTrack.getMinBufferSize(mSampleRate, mOutChannelConfig, mFormat)
        )
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Test: VOICE_RECOGNITION umgeht die Signalverarbeitung (wie
            // VOICE_PERFORMANCE), ist aber normalerweise nicht leise -> evtl.
            // etwas geringere Eingangslatenz bei gleichem Pegel wie MIC.
            val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

            val audioFormat = AudioFormat.Builder()
                .setEncoding(mFormat)
                .setChannelMask(mInChannelConfig)
                .setSampleRate(mSampleRate)
                .build()

            mAudioInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Ab Android 11: Aufnahme über den Low-Latency-Pfad anfordern.
                AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(mInBufferSize)
                    .build()
            } else {
                AudioRecord(
                    audioSource,
                    mSampleRate,
                    mInChannelConfig,
                    mFormat,
                    mInBufferSize
                )
            }
        }
        val audioTrackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                // CONTENT_TYPE_MUSIC bleibt (laut). Nur SONIFICATION war leise;
                // der Low-Latency-Pfad selbst (unten per PerformanceMode) ist es nicht.
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(mFormat)
                    .setChannelMask(mOutChannelConfig)
                    .setSampleRate(mSampleRate)
                    .build()
            )
            // Ausgabepuffer an der nativen Hardware-Blockgröße ausrichten. Das
            // System deckelt ohnehin auf seine Untergrenze (auf dem Test-Gerät
            // 640 Frames / ~40 ms) – kleinere Anfragen bringen nichts, größere
            // erhöhen nur die Latenz. Faktor 2 als kleine Reserve gegen Aussetzer.
            .setBufferSizeInBytes(
                if (mNativeFramesPerBuffer > 0)
                    (mNativeFramesPerBuffer * BYTES_PER_FRAME * 2).coerceAtLeast(512)
                else
                    (mOutBufferSize / 8).coerceAtLeast(512)
            )
            .setTransferMode(AudioTrack.MODE_STREAM)

        // Low-Latency-Wiedergabepfad (ab Android 8) anfordern – mit dem lauten
        // CONTENT_TYPE_MUSIC, nicht mit dem leisen SONIFICATION von vorhin.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioTrackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        mAudioOutput = audioTrackBuilder.build()

        // listen for preference changes
        mSharedPreferences = AppPreferences.prefs(this)
        mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        mActive = AppPreferences.isActive(mSharedPreferences)

        if (mActive) record()
    }

    /**
     * Wählt die Sample-Rate für minimale Latenz: bevorzugt die native Rate des
     * Geräts (dann entfällt der Resampler). Prüft, ob Ein- und Ausgabe die Rate
     * tatsächlich unterstützen; sonst wird eine gängige Rate aus einer Fallback-
     * Kette genommen. So funktioniert die App auf beliebigen Geräten.
     */
    private fun pickSampleRate(am: AudioManager): Int {
        val native = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val candidates = buildList {
            if (native != null) add(native)
            // Fallback-Kette gängiger Raten, falls die native Rate fehlt/ungültig ist.
            addAll(listOf(48000, 44100, 16000, 22050, 8000))
        }
        for (rate in candidates) {
            if (isSampleRateSupported(rate)) return rate
        }
        return 44100 // letzter, praktisch immer unterstützter Notnagel
    }

    /** Prüft, ob eine Sample-Rate für Aufnahme und Wiedergabe brauchbar ist. */
    private fun isSampleRateSupported(rate: Int): Boolean {
        if (rate <= 0) return false
        val inMin = AudioRecord.getMinBufferSize(rate, mInChannelConfig, mFormat)
        val outMin = AudioTrack.getMinBufferSize(rate, mOutChannelConfig, mFormat)
        return inMin > 0 && outMin > 0
    }

    /**
     * Liefert eine gültige Puffergröße. getMinBufferSize kann ERROR (-1) oder
     * ERROR_BAD_VALUE (-2) zurückgeben; dann aus der nativen Puffergröße einen
     * sicheren Ersatz bilden statt abzustürzen.
     */
    private fun safeMinBufferSize(reported: Int): Int {
        if (reported > 0) return reported
        val frames = if (mNativeFramesPerBuffer > 0) mNativeFramesPerBuffer else 1024
        return frames * BYTES_PER_FRAME * 4
    }

    override fun onDestroy() {
        Log.d(AppPreferences.APP_TAG, "Stopping mic service")

        AppPreferences.setActive(mSharedPreferences, false)

        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        mAudioInput?.release()
        mAudioOutput?.release()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(AppPreferences.APP_TAG, "Service sent intent")

        // if this is a stop request, cancel the recording
        if (intent?.action != null) {
            if (intent.action == ACTION_STOP) {
                Log.d(AppPreferences.APP_TAG, "Cancelling recording via notification click")
                AppPreferences.setActive(mSharedPreferences, false)
            }
        }

        return START_STICKY
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        // intercept the preference change.
        if (key != null && key != AppPreferences.KEY_ACTIVE) return
        val bActive = sharedPreferences.getBoolean(AppPreferences.KEY_ACTIVE, false)
        Log.d(AppPreferences.APP_TAG, "Mic state changing (from $mActive to $bActive)")

        if (bActive != mActive) {
            mActive = bActive
            if (mActive) record()
            if (!mActive) mNotificationManager.cancel(0)
        }
    }

    private fun record() {
        val t: Thread = object : Thread() {
            override fun run() {
                val cancelIntent = Intent(applicationContext, MicrophoneService::class.java).apply {
                    action = ACTION_STOP
                    data = "null://null".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingCancelIntent = PendingIntent.getService(
                    applicationContext,
                    0,
                    cancelIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )

                val builder =
                    NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_mic_notification)
                        .setContentTitle(getString(R.string.mic_active))
                        .setContentText(getString(R.string.cancel_mic))
                        .setWhen(System.currentTimeMillis())
                        .setContentIntent(pendingCancelIntent)
                        .setAutoCancel(true)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel =
                        NotificationChannel(
                            CHANNEL_ID,
                            AppPreferences.APP_TAG,
                            NotificationManager.IMPORTANCE_DEFAULT
                        )
                    mNotificationManager.createNotificationChannel(channel)
                }

                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    mNotificationManager.notify(0, builder.build())
                }

                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    registerReceiver(
                        mBroadcastReceiver,
                        IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                    )
                    Log.d(AppPreferences.APP_TAG, "Entered record loop")
                    recordLoop()
                    Log.d(AppPreferences.APP_TAG, "Record loop finished")
                }
            }

            private fun recordLoop() {
                val audioOutput = mAudioOutput
                val audioInput = mAudioInput

                if (audioOutput == null || audioInput == null) {
                    Log.e(AppPreferences.APP_TAG, "Audio components are not ready")
                    return
                }

                if (audioOutput.state != AudioTrack.STATE_INITIALIZED || audioInput.state != AudioRecord.STATE_INITIALIZED) {
                    Log.d(AppPreferences.APP_TAG, "Can't start. Race condition?")
                    return
                }

                // Chunk-Größe an der nativen Hardware-Puffergröße ausrichten:
                // FramesPerBuffer * Bytes/Frame. Das ist die Blockgröße, in der die
                // Hardware ohnehin arbeitet -> minimale Latenz ohne Aussetzer.
                // Fallback auf 1/8 des Min-Puffers, wenn die native Größe unbekannt.
                val chunkSize = if (mNativeFramesPerBuffer > 0) {
                    (mNativeFramesPerBuffer * BYTES_PER_FRAME).coerceAtLeast(256)
                } else {
                    (mInBufferSize / 8).coerceAtLeast(256)
                }
                val byteArray = ByteArray(chunkSize)
                if (BuildConfig.DEBUG) {
                    Log.d(
                        AppPreferences.APP_TAG,
                        "LATENZ-MESSUNG: chunkSize=$chunkSize bytes " +
                            "(nativeFramesPerBuffer=$mNativeFramesPerBuffer), mInBufferSize=$mInBufferSize"
                    )
                }

                try {
                    // Wiedergabe auf volle Lautstärke setzen. Der Low-Latency-Pfad
                    // verwendet sonst nicht zwingend den vollen Pegel.
                    audioOutput.setVolume(AudioTrack.getMaxVolume())
                    audioOutput.play()
                    audioInput.startRecording()

                    // Diagnose: tatsächlich vom System vergebene Ausgabepuffergröße.
                    if (BuildConfig.DEBUG) {
                        val realOutFrames = audioOutput.bufferSizeInFrames
                        val realOutMs = realOutFrames * 1000 / mSampleRate
                        Log.d(
                            AppPreferences.APP_TAG,
                            "LATENZ-MESSUNG: AudioTrack-Puffer=$realOutFrames frames (~${realOutMs} ms), " +
                                "chunkSize=$chunkSize bytes"
                        )
                    }

                    while (mActive) {
                        val read = audioInput.read(byteArray, 0, chunkSize)
                        if (read > 0) {
                            audioOutput.write(byteArray, 0, read)
                        }
                    }

                    Log.d(AppPreferences.APP_TAG, "Finished recording")
                } catch (e: IllegalStateException) {
                    Log.e(AppPreferences.APP_TAG, "Failed during audio start/stop", e)
                } catch (e: Exception) {
                    Log.e(AppPreferences.APP_TAG, "Error while recording, aborting.", e)
                } finally {
                    try {
                        if (audioOutput.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            audioOutput.stop()
                        }
                    } catch (e: IllegalStateException) {
                        Log.e(AppPreferences.APP_TAG, "Can't stop playback", e)
                    }
                    try {
                        if (audioInput.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            audioInput.stop()
                        }
                    } catch (e: IllegalStateException) {
                        Log.e(AppPreferences.APP_TAG, "Can't stop recording", e)
                    }
                }

                // cancel notification and receiver
                mNotificationManager.cancel(0)
                try {
                    unregisterReceiver(mBroadcastReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.e(AppPreferences.APP_TAG, "Receiver wasn't registered: $e")
                }
            }
        }

        t.start()
    }

    private class MicrophoneReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action != null && action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                AppPreferences.setActive(context, false)
            }
        }
    }

    companion object {
        private const val ACTION_STOP = "net.bitplane.android.microphone.STOP"
        private const val CHANNEL_ID = "microphone_channel_id"

        // Mono, 16 bit PCM = 2 Bytes pro Frame. Zentral, damit Puffer-/Chunk-
        // Berechnungen konsistent bleiben, falls sich das Format ändert.
        private const val BYTES_PER_FRAME = 2
    }
}
