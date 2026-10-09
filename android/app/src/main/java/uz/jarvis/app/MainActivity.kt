package uz.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import uz.jarvis.core.Action
import uz.jarvis.core.Brain
import uz.jarvis.core.Commands
import uz.jarvis.core.I18n
import uz.jarvis.core.SpeechText
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("kartal", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val apps by lazy { Apps(this) }

    private lateinit var hud: HudView
    private lateinit var caption: TextView
    private lateinit var input: EditText

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Фразы, которые ещё читаются, и идёт ли ещё ответ: слушать снова только когда всё закончено.
    private val pendingSpeech = mutableSetOf<String>()
    private var answerStreaming = false
    private var answerId = 0
    private var brain: Brain? = null

    private var lang = I18n.DEFAULT
    private var listening = false
    private var waitingForCommand = false
    private var resumed = false

    private val name get() = prefs.getString("name", "Kartal")!!.ifBlank { "Kartal" }
    private val wakeWords get() = (listOf(name) + prefs.getString("wake_words", DEFAULT_WAKE)!!.split(","))
        .map { it.trim() }.filter { it.isNotEmpty() }
    private val alwaysListen get() = prefs.getBoolean("always_listen", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lang = I18n.code(prefs.getString("lang", I18n.DEFAULT))
        buildUi()
        rebuildBrain()
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                applyVoiceLanguage()
                say(I18n.t(lang, "hello", name = name))
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                main.post { speechFinished(id) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                main.post { speechFinished(id) }
            }
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (prefs.getString("api_key", "").isNullOrBlank()) main.postDelayed({ showSettings() }, 600)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (alwaysListen && !listening) main.postDelayed({ startListening() }, 800)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        tts?.shutdown()
        worker.shutdownNow()
    }

    // --- экран ---------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        hud = HudView(this).apply {
            title = name
            status = I18n.t(lang, "status_idle")
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        caption = TextView(this).apply {
            setTextColor(Color.rgb(207, 239, 255))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
        }
        input = EditText(this).apply {
            hint = "…"
            setHintTextColor(Color.rgb(70, 110, 130))
            setTextColor(Color.WHITE)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { v, actionId, event ->
                val send = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (send && v.text.isNotBlank()) {
                    val text = v.text.toString()
                    v.text = ""
                    onHeard(text, typed = true)
                }
                send
            }
        }
        val settings = TextView(this).apply {
            text = "⚙"
            textSize = 26f
            setTextColor(Color.rgb(0, 180, 216))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { showSettings() }
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(20))
            addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) })
        }
        val root = FrameLayout(this).apply {
            addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            addView(settings, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        setContentView(root)
    }

    private fun setState(state: HudState) {
        hud.state = state
        hud.status = I18n.t(lang, when (state) {
            HudState.IDLE -> "status_idle"
            HudState.LISTENING -> "status_listening"
            HudState.THINKING -> "status_thinking"
            HudState.SPEAKING -> "status_speaking"
        })
    }

    private fun showSettings() {
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, dp(10), 0, 0) }
        val nameField = EditText(this).apply { setText(name) }
        val wakeField = EditText(this).apply { setText(prefs.getString("wake_words", DEFAULT_WAKE)) }
        val keyField = EditText(this).apply {
            setText(prefs.getString("api_key", ""))
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val langNames = listOf("Русский", "O'zbekcha", "Türkçe")
        val langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langNames)
            setSelection(I18n.LANGS.indexOf(lang))
        }
        val alwaysSwitch = Switch(this).apply {
            text = "Слушать всегда (откликаться на имя без нажатия)"
            isChecked = alwaysListen
        }
        box.addView(label("Имя ассистента"))
        box.addView(nameField)
        box.addView(label("На какие слова откликаться (через запятую)"))
        box.addView(wakeField)
        box.addView(label("Язык"))
        box.addView(langSpinner)
        box.addView(label("Ключ Claude API (console.anthropic.com)"))
        box.addView(keyField)
        box.addView(alwaysSwitch.apply { setPadding(0, dp(14), 0, 0) })
        box.addView(android.widget.Button(this).apply {
            text = "Голос телефона (скачать качественный голос)"
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (e: Exception) {
                    caption.text = "Откройте: Настройки → Специальные возможности → Синтез речи"
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit()
                    .putString("name", nameField.text.toString().trim())
                    .putString("wake_words", wakeField.text.toString())
                    .putString("api_key", keyField.text.toString().trim())
                    .putString("lang", I18n.LANGS[langSpinner.selectedItemPosition])
                    .putBoolean("always_listen", alwaysSwitch.isChecked)
                    .apply()
                lang = I18n.LANGS[langSpinner.selectedItemPosition]
                hud.title = name
                applyVoiceLanguage()
                rebuildBrain()
                setState(HudState.IDLE)
                if (alwaysListen) startListening() else stopListening()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun rebuildBrain() {
        brain = Brain(name, prefs.getString("api_key", "") ?: "")
    }

    // --- голос -----------------------------------------------------------------------

    private fun applyVoiceLanguage() {
        val t = tts ?: return
        if (!ttsReady) return
        // Узбекского голоса на телефонах обычно нет, тогда читаем турецким.
        val wanted = when (lang) { "uz" -> listOf("uz-UZ", "tr-TR"); "tr" -> listOf("tr-TR"); else -> listOf("ru-RU") }
        for (tag in wanted) {
            val locale = Locale.forLanguageTag(tag)
            val r = t.setLanguage(locale)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                pickBestVoice(t, locale)
                break
            }
        }
        t.setSpeechRate(1.0f)
        t.setPitch(1.0f)
    }

    /** Самый качественный установленный голос для языка, а не первый попавшийся. */
    private fun pickBestVoice(t: TextToSpeech, locale: Locale) {
        val voices = try { t.voices } catch (e: Exception) { null } ?: return
        val best = voices
            .filter { it.locale.language == locale.language }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .maxWithOrNull(compareBy<android.speech.tts.Voice>(
                { it.locale.country == locale.country },
                { it.quality },
                { !it.isNetworkConnectionRequired },
                { it.latency <= android.speech.tts.Voice.LATENCY_NORMAL },
            )) ?: return
        t.setVoice(best)
    }

    /** Сказать фразу целиком, прервав то, что говорилось раньше. */
    private fun say(text: String) {
        answerStreaming = false
        answerId++
        pendingSpeech.clear()
        caption.text = text
        speak(text, TextToSpeech.QUEUE_FLUSH)
    }

    /** Добавить фразу в очередь: так ответ читается по предложениям, пока остальное ещё пишется. */
    private fun speak(text: String, mode: Int) {
        setState(HudState.SPEAKING)
        val clean = SpeechText.clean(text)
        val t = tts
        if (!ttsReady || t == null || clean.isEmpty()) { speechFinished(null); return }
        stopListening()
        val id = "kartal-" + System.nanoTime()
        pendingSpeech += id
        t.speak(clean, mode, null, id)
    }

    private fun speechFinished(id: String?) {
        if (id != null && !pendingSpeech.remove(id)) return
        if (pendingSpeech.isEmpty() && !answerStreaming) afterSpeaking()
    }

    private fun afterSpeaking() {
        setState(HudState.IDLE)
        if (waitingForCommand || (alwaysListen && resumed)) main.postDelayed({ startListening() }, 300)
    }

    private fun startListening() {
        if (listening || isFinishing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            caption.text = "На телефоне нет распознавания речи Google. Можно писать текстом."
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, I18n.speechTag(lang))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        listening = true
        setState(HudState.LISTENING)
        r.startListening(intent)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        recognizer?.cancel()
        if (hud.state == HudState.LISTENING) setState(HudState.IDLE)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            setState(HudState.IDLE)
            if (alwaysListen && resumed) {
                main.postDelayed({ startListening() }, 400)
            } else if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                caption.text = I18n.t(lang, "not_heard")
                waitingForCommand = false
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                setState(HudState.IDLE)
                if (alwaysListen && resumed) main.postDelayed({ startListening() }, 300)
                return
            }
            onHeard(text, typed = false)
        }
    }

    // --- команды ---------------------------------------------------------------------

    private fun onHeard(heard: String, typed: Boolean) {
        val (called, rest) = Commands.stripWakeWord(heard, wakeWords)
        // В режиме «слушать всегда» реагируем только на обращение по имени.
        if (alwaysListen && !typed && !called && !waitingForCommand) {
            setState(HudState.IDLE)
            if (resumed) main.postDelayed({ startListening() }, 300)
            return
        }
        caption.text = heard
        if (called && rest.isEmpty()) {
            waitingForCommand = true
            say(I18n.t(lang, "listening"))
            return
        }
        waitingForCommand = false
        handle(rest)
    }

    private fun handle(text: String) {
        val command = Commands.parse(text)
        val now = LocalDateTime.now()
        when (command.action) {
            Action.EXIT -> { say(I18n.t(lang, "bye")); main.postDelayed({ finish() }, 1500) }
            Action.EMPTY -> { waitingForCommand = true; say(I18n.t(lang, "listening")) }
            Action.RESET -> { brain?.reset(); say(I18n.t(lang, "reset")) }
            Action.LANG -> {
                lang = command.arg
                prefs.edit().putString("lang", lang).apply()
                applyVoiceLanguage()
                say(I18n.t(lang, "switched"))
            }
            Action.TIME -> say(I18n.t(lang, "time", "%02d:%02d".format(now.hour, now.minute)))
            Action.DATE -> say(I18n.t(lang, "date", I18n.formatDate(lang, now.dayOfMonth, now.monthValue)))
            Action.OPEN -> {
                val (ok, what) = apps.open(command.arg)
                say(I18n.t(lang, if (ok) "open" else "not_found", what))
            }
            Action.SEARCH -> { apps.webSearch(command.arg); say(I18n.t(lang, "search", command.arg)) }
            Action.YOUTUBE -> { apps.youtubeSearch(command.arg); say(I18n.t(lang, "youtube", command.arg)) }
            Action.ASK -> ask(command.arg)
        }
    }

    private fun ask(question: String) {
        val b = brain ?: return
        setState(HudState.THINKING)
        val askLang = lang
        answerStreaming = true
        val myId = ++answerId
        pendingSpeech.clear()
        val shown = StringBuilder()
        worker.execute {
            var spoken = 0
            val answer = try {
                b.ask(question, askLang) { sentence ->
                    spoken++
                    main.post {
                        if (answerId != myId) return@post
                        shown.append(if (shown.isEmpty()) "" else " ").append(sentence)
                        caption.text = shown.toString()
                        speak(sentence, TextToSpeech.QUEUE_ADD)
                    }
                }
            } catch (e: Exception) {
                I18n.t(askLang, "failed", e.message ?: e.javaClass.simpleName)
            }
            main.post {
                if (answerId != myId) return@post
                if (spoken == 0) { say(answer); return@post }
                answerStreaming = false
                caption.text = answer
                speechFinished(null)
            }
        }
    }

    companion object {
        const val DEFAULT_WAKE = "картал, kartal, qartal, kartel, картель, карталь"
    }
}
