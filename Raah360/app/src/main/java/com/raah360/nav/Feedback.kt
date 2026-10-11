package com.raah360.nav

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Spoken guidance. Speaks only what VoicePrompter decided; never chatters. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    @Volatile var enabled = true
    private var locale: Locale = Locale.forLanguageTag("en-IN")
    /** Called with false when the phone has no voice for the chosen language (English is used instead). */
    var onVoiceAvailability: ((Boolean) -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            applyLocale()
            tts.setSpeechRate(1.0f)
        }
    }

    /** Speak in [tag] (BCP-47, e.g. "hi-IN"); falls back to English when that voice isn't installed. */
    fun setLanguage(tag: String) {
        locale = Locale.forLanguageTag(tag)
        if (ready) applyLocale()
    }

    private fun applyLocale() {
        val r = tts.setLanguage(locale)
        val ok = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ok) {
            val en = tts.setLanguage(Locale("en", "IN"))
            if (en == TextToSpeech.LANG_MISSING_DATA || en == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US)
        }
        onVoiceAvailability?.invoke(ok)
    }

    fun say(text: String, urgent: Boolean = false) {
        if (!enabled || !ready) return
        tts.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "r360-${text.hashCode()}")
    }

    fun stop() { if (ready) tts.stop() }

    fun shutdown() { tts.stop(); tts.shutdown() }
}

class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    var enabled = true

    private fun play(effect: VibrationEffect) {
        if (!enabled) return
        runCatching { vibrator?.vibrate(effect) }
    }

    fun tick() = play(VibrationEffect.createOneShot(25, 120))
    fun turn() = play(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
    fun success() = play(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80, 60, 220), -1))
    fun warning() = play(VibrationEffect.createWaveform(longArrayOf(0, 300), -1))
}
