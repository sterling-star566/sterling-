package com.sterling.app

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.*
import java.util.Locale

class SterlingService : Service(), RecognitionListener {
    private lateinit var wm: WindowManager
    private lateinit var orb: OrbView
    private lateinit var tools: Tools
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null
    private val h = Handler(Looper.getMainLooper())
    private var active = true      // always-listening on/off (tap the orb to toggle)
    private var fails = 0
    private var needleDone = false
    private var greeted = false

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("sterling", "Sterling", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, "sterling").setContentTitle("Sterling is listening").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, note, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, note)
        tools = Tools(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        orb = OrbView(this)
        val size = (96 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 400 }
        var dx = 0f; var dy = 0f; var moved = false
        orb.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - lp.x; dy = e.rawY - lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - dx - lp.x) > 12 || Math.abs(e.rawY - dy - lp.y) > 12) moved = true
                    if (moved) { lp.x = (e.rawX - dx).toInt(); lp.y = (e.rawY - dy).toInt(); wm.updateViewLayout(orb, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }
        wm.addView(orb, lp)
        Thread { NeedleEngine.start(applicationContext); h.post { needleDone = true; maybeGreet() } }.start()
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.getDefault())
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { h.post { resume() } }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { h.post { resume() } }
                })
                ttsReady = true
                maybeGreet()
            } else h.post { resume() }
        }
    }

    private fun maybeGreet() {
        if (greeted || !ttsReady || !needleDone) return
        greeted = true
        say("Sterling online. " + (if (NeedleEngine.ready) "Needle is ready." else "Needle isn't available, using basic commands.") + " I'm listening.")
    }

    private fun toggle() {
        active = !active
        if (active) say("Listening") else { sr?.cancel(); if (ttsReady) tts.stop(); orb.state = OrbView.S.IDLE }
    }

    private fun resume() { if (active) listen() }

    private fun listen() {
        if (!active) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0) { active = false; say("I need microphone permission"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { active = false; say("Speech recognition isn't available on this phone"); return }
        orb.state = OrbView.S.LISTENING
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            it.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L))
        }
    }

    private fun say(s: String) {
        orb.state = OrbView.S.IDLE
        sr?.cancel()
        if (!ttsReady || tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "s") != TextToSpeech.SUCCESS) h.post { resume() }
    }

    override fun onResults(r: Bundle?) {
        val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (list.isNullOrEmpty()) { h.postDelayed({ resume() }, 250); return }
        orb.state = OrbView.S.THINKING
        Thread {
            var calls: List<Call> = emptyList()
            for ((i, x) in list.withIndex()) { calls = Brain.route(x, useNeedle = i < 2); if (calls.isNotEmpty()) break }
            val addressed = list.any { it.lowercase().contains("sterling") }
            h.post {
                fails = 0
                when {
                    calls.isEmpty() -> if (addressed) say("I heard ${list[0]}, but I don't know how to do that") else resume()
                    calls.any { it.name == "sleep" } -> { active = false; say("Okay. Tap the orb when you need me") }
                    else -> Thread {
                        val reply = calls.map { try { tools.run(it) } catch (e: Exception) { "That didn't work: ${e.message}" } }
                            .filter { it.isNotEmpty() }.joinToString(". ")
                        h.post { if (reply.isEmpty()) resume() else say(reply) }
                    }.start()
                }
            }
        }.start()
    }

    override fun onError(e: Int) {
        if (!active) return
        when (e) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { active = false; say("I need microphone permission") }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> h.postDelayed({ resume() }, 250)
            else -> { fails++; h.postDelayed({ resume() }, minOf(1000L * fails, 5000L)) }
        }
    }

    override fun onReadyForSpeech(p: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(v: Float) {}
    override fun onBufferReceived(b: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(p: Bundle?) {}
    override fun onEvent(t: Int, p: Bundle?) {}

    override fun onDestroy() {
        active = false; h.removeCallbacksAndMessages(null); sr?.destroy()
        if (::tts.isInitialized) tts.shutdown()
        if (::orb.isInitialized) wm.removeView(orb)
        super.onDestroy()
    }
}
