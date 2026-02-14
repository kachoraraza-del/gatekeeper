package com.example.opensesame

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.*
import java.util.Locale

class GarageAutomationService : LifecycleService(), RecognitionListener {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var mediaSession: MediaSession
    private val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 100)

    private var ttsReady = false
    private var hasAskedOnce = false
    private var isInsideZone = false
    private var isWaitingForWakeup = false
    private var continuousErrorCount = 0
    private var langIndex = 0

    private val garageLat = 60.228799
    private val garageLng = 24.851481
    private val garagePhone = "049449311094"
    private val triggerDistance = 100.0
    private val resetDistance = 400.0

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("GaragePrefs", Context.MODE_PRIVATE)
        langIndex = prefs.getInt("selected_lang", 0)

        setupNotificationChannel()
        setupMediaSession()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val loc = when(langIndex) {
                    1 -> Locale("fi", "FI")
                    2 -> Locale("sv", "SE")
                    else -> Locale.UK
                }
                tts.language = loc
                tts.setSpeechRate(0.82f) // Human-like slow speed
                tts.setPitch(0.75f)      // Bassy Lexus tone
                setupTTSListener()
                requestAppAudioFocus()

                val greeting = when(langIndex) {
                    1 -> "Gatekeeper... on valmiina."
                    2 -> "Gatekeeper... är redo."
                    else -> "Gatekeeper... is active."
                }
                tts.speak(greeting, TextToSpeech.QUEUE_FLUSH, null, "INIT_DONE")
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        startForeground(1, createNotification("Gatekeeper Standby"), types)
    }

    private fun updateStatusBroadcast(isMonitoring: Boolean) {
        val intent = Intent("GATEKEEPER_STATUS_UPDATE")
        intent.putExtra("IS_MONITORING", isMonitoring)
        sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.getStringExtra("ACTION")) {
            "START_GPS" -> {
                hasAskedOnce = false
                startLocationUpdates()
                updateStatusBroadcast(true)
                updateNotification("Monitoring for Lexus...")
            }
            "STOP_GPS" -> {
                if (::fusedLocationClient.isInitialized) fusedLocationClient.removeLocationUpdates(locationCallback)
                isInsideZone = false
                updateStatusBroadcast(false)
                updateNotification("Lexus Disconnected - Standby")
            }
            "STOP_SERVICE" -> cleanupAndStop()
        }
        return START_STICKY
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(content))
    }

    private fun setupTTSListener() {
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(p0: String?) {}
            override fun onDone(utteranceId: String?) {
                when (utteranceId) {
                    "INIT_DONE" -> {
                        ttsReady = true
                        releaseAudioFocus()
                        if (isInsideZone) handleWakeUp()
                    }
                    "QUESTION_ID", "ERROR_RETRY" -> Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 300)
                    "CONFIRM" -> Handler(Looper.getMainLooper()).postDelayed({ dialGarage() }, 500)
                    "SLEEP_MSG" -> {
                        isWaitingForWakeup = true
                        releaseAudioFocus()
                    }
                }
            }
            override fun onError(p0: String?) { releaseAudioFocus() }
        })
    }

    private fun handleWakeUp() {
        if (!isInsideZone) return
        isWaitingForWakeup = false
        continuousErrorCount = 0
        requestAppAudioFocus()
        ensureHighVolume()

        val prompt = when(langIndex) {
            1 -> "Portti havaittu. Avataanko se?"
            2 -> "Grinden är nära. Ska jag öppna den?"
            else -> "Gate detected. Should I open it?"
        }
        Handler(Looper.getMainLooper()).postDelayed({ tts.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "QUESTION_ID") }, 500)
    }

    private fun startListening() {
        if (!isInsideZone || isWaitingForWakeup) return
        playChime()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val langTag = when(langIndex) { 1 -> "fi-FI"; 2 -> "sv-SE"; else -> "en-US" }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
        }
        Handler(Looper.getMainLooper()).postDelayed({ if (isInsideZone) speechRecognizer.startListening(intent) }, 300)
    }

    override fun onResults(results: Bundle?) {
        if (!isInsideZone) return
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.joinToString(" ")?.lowercase() ?: ""

        val positive = when(langIndex) {
            1 -> text.contains("joo") || text.contains("kyllä") || text.contains("avaa")
            2 -> text.contains("ja") || text.contains("öppna") || text.contains("uppfattat")
            else -> text.contains("yes") || text.contains("open") || text.contains("confirmed")
        }

        val negative = when(langIndex) {
            1 -> text.contains("ei") || text.contains("älä") || text.contains("peruuta")
            2 -> text.contains("nej") || text.contains("stopp") || text.contains("avbryt")
            else -> text.contains("no") || text.contains("cancel") || text.contains("stop")
        }

        when {
            positive -> {
                val msg = when(langIndex) {
                    1 -> "Selvä. Avataan."
                    2 -> "Uppfattat. Jag öppnar."
                    else -> "Confirmed. Opening now."
                }
                tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "CONFIRM")
            }
            negative -> {
                val msg = when(langIndex) {
                    1 -> "Selvä. Ei avata."
                    2 -> "Okej. Vi hoppar över det."
                    else -> "Okay. Standing down."
                }
                tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "SLEEP_MSG")
            }
            else -> {
                val retry = when(langIndex) {
                    1 -> "Anteeksi, avataanko portti?"
                    2 -> "Ursäkta, ska jag öppna grinden?"
                    else -> "Excuse me, should I open the gate?"
                }
                tts.speak(retry, TextToSpeech.QUEUE_FLUSH, null, "ERROR_RETRY")
            }
        }
    }

    override fun onError(error: Int) {
        if (!isInsideZone || isWaitingForWakeup) return
        if (++continuousErrorCount < 3) {
            val listenMsg = when(langIndex) { 1 -> "Kuuntelen."; 2 -> "Jag lyssnar."; else -> "Listening." }
            tts.speak(listenMsg, TextToSpeech.QUEUE_FLUSH, null, "ERROR_RETRY")
        } else {
            isWaitingForWakeup = true
            releaseAudioFocus()
        }
    }

    private fun dialGarage() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$garagePhone")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                Handler(Looper.getMainLooper()).postDelayed({
                    val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        tm.showInCallScreen(false)
                        releaseAudioFocus()
                    }
                }, 1000)
            } catch (e: Exception) { releaseAudioFocus() }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            val dist = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, garageLat, garageLng, dist)
            if (dist[0] < triggerDistance) {
                if (!isInsideZone && !hasAskedOnce && ttsReady) {
                    isInsideZone = true; hasAskedOnce = true; handleWakeUp()
                }
            } else {
                if (isInsideZone) {
                    isInsideZone = false; isWaitingForWakeup = false
                    speechRecognizer.cancel(); releaseAudioFocus()
                }
                if (dist[0] > resetDistance) hasAskedOnce = false
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).setMinUpdateDistanceMeters(5f).build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun setupNotificationChannel() {
        val channel = NotificationChannel("CH_ID", "Gatekeeper Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification = NotificationCompat.Builder(this, "CH_ID")
        .setContentTitle("Gatekeeper").setContentText(content).setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true).setSilent(true).build()

    private fun cleanupAndStop() {
        if (::fusedLocationClient.isInitialized) fusedLocationClient.removeLocationUpdates(locationCallback)
        updateStatusBroadcast(false)
        try { speechRecognizer.destroy() } catch (e: Exception) {}
        releaseAudioFocus()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        toneGen.release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun requestAppAudioFocus() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        am.requestAudioFocus(focusRequest)
    }

    private fun releaseAudioFocus() { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocus(null) }
    private fun ensureHighVolume() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), AudioManager.FLAG_SHOW_UI)
    }
    private fun playChime() { toneGen.startTone(android.media.ToneGenerator.TONE_PROP_PROMPT, 150) }
    private fun setupMediaSession() { mediaSession = MediaSession(this, "OpenSesameMedia").apply { isActive = true } }

    override fun onReadyForSpeech(p0: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(p0: Float) {}
    override fun onBufferReceived(p0: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(p0: Bundle?) {}
    override fun onEvent(p0: Int, p1: Bundle?) {}
}