package com.leo.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

class WakeWordService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "com.leo.assistant.action.START_WAKE"
        const val ACTION_STOP = "com.leo.assistant.action.STOP_WAKE"
        private const val CHANNEL_ID = "leo_wake_service"
        private const val NOTIFICATION_ID = 4401
        private const val TAG = "LEO-WAKE"
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listening = false
    @Volatile private var destroyed = false
    private var recorder: AudioRecord? = null
    private var stream: OnlineStream? = null
    private var audioThread: Thread? = null
    private var spotter: KeywordSpotter? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("leo", MODE_PRIVATE).edit().putBoolean("wake_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Preparing offline wake-word detector"))
        getSharedPreferences("leo", MODE_PRIVATE).edit().putBoolean("wake_enabled", true).apply()
        if (!listening && recognizer == null) {
            try {
                initializeSpotter()
                startWakeListening()
            } catch (e: Exception) {
                Log.e(TAG, "Wake-word initialization failed", e)
                updateNotification("Wake-word setup failed. Open LEO to retry.")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun initializeSpotter() {
        if (spotter != null) return
        val model = "$MODEL_DIR/"
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = model + "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = model + "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = model + "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                ),
                tokens = model + "tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2"
            ),
            keywordsFile = model + "keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2
        )
        spotter = KeywordSpotter(assets, config)
    }

    private fun startWakeListening() {
        if (destroyed || listening) return
        val minBytes = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBytes > 0) { "Microphone is unavailable" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBytes * 2, 6400)
        )
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "Could not open microphone" }
        val kwsStream = spotter!!.createStream("HEY LEO")
        recorder = audio
        stream = kwsStream
        audio.startRecording()
        listening = true
        updateNotification("Listening locally for “Hey LEO”")
        audioThread = thread(name = "leo-wake-audio") {
            val buffer = ShortArray(1600)
            try {
                while (listening && !destroyed) {
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    val samples = FloatArray(count) { buffer[it] / 32768.0f }
                    kwsStream.acceptWaveform(samples, 16000)
                    while (spotter?.isReady(kwsStream) == true) {
                        spotter?.decode(kwsStream)
                        val phrase = spotter?.getResult(kwsStream)?.keyword.orEmpty()
                        if (phrase.isNotBlank()) {
                            listening = false
                            main.postDelayed({ onWakeDetected() }, 300)
                            return@thread
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio loop stopped", e)
                main.post { if (!destroyed) restartWakeListening() }
            } finally {
                try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() } catch (_: Exception) {}
                audio.release()
                try { kwsStream.release() } catch (_: Exception) {}
                if (recorder === audio) recorder = null
                if (stream === kwsStream) stream = null
            }
        }
    }

    private fun onWakeDetected() {
        if (destroyed) return
        updateNotification("Wake word detected — listening for your request")
        beginCommandCapture()
    }

    private fun beginCommandCapture() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakAndReturn("Speech recognition is unavailable on this device.")
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { updateNotification("Listening for your command") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { updateNotification("Processing your request") }
                    override fun onError(error: Int) {
                        releaseRecognizer()
                        speakAndReturn("I didn't catch that. Say Hey LEO when you're ready.")
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().trim()
                        releaseRecognizer()
                        if (words.isBlank()) speakAndReturn("I didn't catch that.")
                        else handleCommand(words)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start command capture", e)
            releaseRecognizer()
            speakAndReturn("I couldn't start voice recognition.")
        }
    }

    private fun releaseRecognizer() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun handleCommand(command: String) {
        appendHistory("user", command)
        updateNotification("LEO is thinking")
        thread(name = "leo-ai-request") {
            val reply = askGemini(command)
            appendHistory("model", reply)
            main.post { speakAndReturn(reply) }
        }
    }

    private fun askGemini(prompt: String): String {
        val key = getSharedPreferences("leo", MODE_PRIVATE).getString("gemini_key", "").orEmpty()
        if (key.isBlank()) return "Please open LEO and add your Gemini API key in Settings."
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key")
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject()
                .put("contents", JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "parts", JSONArray().put(JSONObject().put("text", prompt))
                    )
                ))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                "Gemini returned an error ($code). Please check your API key and connection."
            } else {
                JSONObject(response).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            }
        } catch (e: Exception) {
            "I couldn't reach Gemini. Check your internet connection."
        } finally { connection?.disconnect() }
    }

    private fun appendHistory(role: String, text: String) {
        val prefs = getSharedPreferences("leo", MODE_PRIVATE)
        val array = try { JSONArray(prefs.getString("history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        array.put(JSONObject().put("role", role).put("text", text))
        val trimmed = JSONArray()
        for (i in max(0, array.length() - 40) until array.length()) trimmed.put(array.getJSONObject(i))
        prefs.edit().putString("history", trimmed.toString()).apply()
    }

    private fun speakAndReturn(text: String) {
        updateNotification("LEO is replying")
        if (ttsReady && tts != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { restartWakeListening() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { restartWakeListening() } }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "leo_reply")
        } else {
            main.postDelayed({ restartWakeListening() }, 1200)
        }
    }

    private fun restartWakeListening() {
        if (destroyed) return
        releaseRecognizer()
        try { startWakeListening() }
        catch (e: Exception) {
            Log.e(TAG, "Could not restart wake-word detector", e)
            updateNotification("Wake-word listener stopped. Open LEO to restart.")
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale.UK
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "LEO wake word", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val stopIntent = Intent(this, WakeWordService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 4402, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LEO is active")
            .setContentText(message)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        listening = false
        releaseRecognizer()
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { stream?.release() } catch (_: Exception) {}
        stream = null
        try { spotter?.release() } catch (_: Exception) {}
        spotter = null
        tts?.stop()
        tts?.shutdown()
        getSharedPreferences("leo", MODE_PRIVATE).edit().putBoolean("wake_enabled", false).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        super.onDestroy()
    }
}
