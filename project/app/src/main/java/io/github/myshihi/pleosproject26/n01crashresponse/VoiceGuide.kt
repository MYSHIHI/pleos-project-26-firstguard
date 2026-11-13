package io.github.myshihi.pleosproject26.n01crashresponse

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Spoken guidance so occupants do not have to read the screen. Each key is spoken once per incident. */
class VoiceGuide(context: Context, private val status: (String) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val spoken = mutableSetOf<String>()
    private var pending: Pair<String, String>? = null

    private val app = context.applicationContext

    init { connect(null) }

    /** Default engine first; Pleos Connect has no default set, so fall back to its installed engine by package. */
    private fun connect(engine: String?) {
        tts = TextToSpeech(app, { code ->
            val t = tts
            if (code == TextToSpeech.SUCCESS && t != null) {
                val lang = t.setLanguage(Locale.KOREAN)
                ready = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
                t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                status(if (ready) "음성 안내 켜짐" else "한국어 음성 없음")
                pending?.let { (k, s) -> say(k, s) }
            } else if (engine == null && FALLBACK != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { tts?.shutdown(); connect(FALLBACK) }
            } else status("음성 엔진 연결 안 됨 (코드 $code)")
        }, engine)
    }

    fun say(key: String, text: String, interrupt: Boolean = false) {
        if (key in spoken) return
        if (!ready) { pending = key to text; return }
        spoken += key
        tts?.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, key)
    }

    fun reset() { spoken.clear(); pending = null; tts?.stop() }

    fun release() { tts?.shutdown(); tts = null; ready = false }

    companion object { val FALLBACK: String? = "ai.pleos.playground.caas" }
}
