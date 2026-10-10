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
import uz.jarvis.core.MobileBrain
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
    private lateinit var providerStatus: TextView
    private lateinit var input: EditText

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Фразы, которые ещё читаются, и идёт ли ещё ответ: слушать снова только когда всё закончено.
    private val pendingSpeech = mutableSetOf<String>()
    private var answerStreaming = false
    private var answerId = 0
    private var brain: MobileBrain? = null
    private val secureKeys by lazy { SecureKeys(this) }
    private val cloudSpeech = CloudSpeech()

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
        cloudSpeech.close()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    // --- экран ---------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val green = Color.rgb(30, 211, 122)
        val pale = Color.rgb(212, 247, 226)
        val muted = Color.rgb(94, 154, 120)
        fun panel() = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(4, 33, 11)); cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.rgb(13, 80, 31))
        }
        fun label(value: String, size: Float, color: Int = pale) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
        }
        fun card(title: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = panel()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label(title, 10f, muted))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(2, 20, 6))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(12), dp(8))
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(uz.jarvis.app.R.drawable.ic_launcher)
                contentDescription = "Kartal"
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
            val brand = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(name.uppercase(), 23f, green))
                addView(label("КОМАНДНЫЙ ЦЕНТР", 10f, muted))
            }
            addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "НАСТРОЙКИ"; textSize = 11f; setTextColor(green)
                gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(12), 0, dp(12), 0)
                contentDescription = "Настройки ассистента и ИИ"
                setOnClickListener { showSettings() }
            })
        }
        root.addView(header)
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        fun addCard(view: android.view.View) {
            body.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        val statusRow = LinearLayout(this)
        val core = card("ЯДРО ИИ").apply {
            providerStatus = label("Gemini / OpenAI", 15f, green)
            addView(providerStatus)
            addView(label("RU · UZ · TR", 10f, muted))
        }
        val system = card("ТЕЛЕФОН").apply {
            val battery = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val percent = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            addView(label(if (percent in 0..100) "Батарея $percent%" else "Android", 15f))
            addView(android.widget.TextClock(this@MainActivity).apply {
                format24Hour = "HH:mm · dd.MM.yyyy"; format12Hour = "HH:mm · dd.MM.yyyy"
                setTextColor(muted); textSize = 10f
            })
        }
        statusRow.addView(core, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        statusRow.addView(system, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addCard(statusRow)
        val rates = card("КУРСЫ ВАЛЮТ · ЦБ УЗБЕКИСТАНА")
        val rateRows = List(3) { label("Загрузка…", 14f).also { rates.addView(it) } }
        val rateDate = label("Изменение к предыдущей публикации", 10f, muted)
        rates.addView(rateDate)
        addCard(rates)
        fun refreshRates() {
            worker.execute {
                val quotes = runCatching { uz.jarvis.core.LiveData.currencies() }.getOrNull()
                main.post {
                    if (isDestroyed) return@post
                    rateRows.forEachIndexed { i, row ->
                        val quote = quotes?.getOrNull(i)
                        row.text = quote?.text() ?: "Данные недоступны"
                        row.setTextColor(when {
                            quote == null || quote.change == 0.0 -> muted
                            quote.change < 0 -> Color.rgb(255, 90, 90)
                            else -> green
                        })
                    }
                    rateDate.text = "${quotes?.firstOrNull()?.date.orEmpty()} · USD/RUB — кросс-курс"
                    main.postDelayed({ if (!isDestroyed) refreshRates() }, 300000)
                }
            }
        }
        refreshRates()
        hud = HudView(this).apply {
            title = name; status = I18n.t(lang, "status_idle")
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        val globe = card("ГОЛОСОВОЙ АССИСТЕНТ")
        globe.addView(hud, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        globe.addView(label("Нажмите на глобус и говорите", 11f, muted).apply { gravity = Gravity.CENTER })
        addCard(globe)
        val quick = card("БЫСТРЫЕ КОМАНДЫ")
        val commands = listOf("Telegram" to "открой телеграм", "YouTube" to "открой ютуб",
            "Время" to "который час", "Погода" to "погода",
            "Курс валют" to "Какой сейчас курс доллара к рублю и суму?", "Криптовалюты" to "Какова текущая цена Bitcoin и Ethereum?")
        for (pair in commands.chunked(2)) {
            val row = LinearLayout(this)
            for ((title, command) in pair) {
                val button = android.widget.Button(this).apply {
                    text = title; isAllCaps = false; textSize = 12f; setTextColor(pale)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(5, 43, 15))
                    setOnClickListener { onHeard(command, typed = true) }
                }
                row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            quick.addView(row)
        }
        addCard(quick)
        caption = label("Kartal готов. Напишите команду или нажмите на глобус.", 14f).apply {
            setPadding(0, dp(8), 0, dp(8)); setTextIsSelectable(true)
        }
        addCard(card("ДИАЛОГ").apply { addView(caption) })
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        input = EditText(this).apply {
            hint = "Напишите команду…"; setHintTextColor(muted); setTextColor(pale)
            textSize = 14f; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEND
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            setOnEditorActionListener { _, action, event ->
                val send = action == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (send) submitText()
                send
            }
        }
        val composer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(8))
            addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(android.widget.Button(this@MainActivity).apply {
                text = "➤"; contentDescription = "Отправить команду"; setTextColor(green)
                setOnClickListener { submitText() }
            }, LinearLayout.LayoutParams(dp(56), dp(52)))
        }
        root.addView(composer)
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun submitText() {
        val text = input.text.toString().trim()
        if (text.isNotEmpty()) { input.setText(""); onHeard(text, typed = true) }
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
        val providerNames = listOf("Gemini", "OpenAI")
        val providers = listOf("gemini", "openai")
        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, providerNames)
            setSelection(providers.indexOf(prefs.getString("provider", "gemini")).coerceAtLeast(0))
        }
        val keyFields = providers.associateWith { provider -> EditText(this).apply {
            setText(secureKeys.read(provider)); hint = "API-ключ $provider"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } }
        val modelFields = providers.associateWith { provider -> EditText(this).apply {
            setText(prefs.getString(provider + "_model", "")); hint = "Выберите или введите модель"
            setSingleLine()
        } }
        val searchSwitch = Switch(this).apply {
            text = "Поиск ИИ в интернете"; isChecked = prefs.getBoolean("web_search", true)
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
        box.addView(label("Какой ИИ отвечает"))
        box.addView(providerSpinner)
        providers.forEachIndexed { index, provider ->
            box.addView(label(providerNames[index] + " — ключ и модель"))
            box.addView(keyFields.getValue(provider))
            box.addView(modelFields.getValue(provider))
            box.addView(android.widget.Button(this).apply {
                text = "Загрузить модели " + providerNames[index]; isAllCaps = false
                setOnClickListener {
                    val key = keyFields.getValue(provider).text.toString().trim()
                    isEnabled = false
                    Thread {
                        try {
                            val models = MobileBrain.models(provider, key)
                            main.post {
                                if (!isFinishing && !isDestroyed) {
                                    isEnabled = true
                                    if (models.isEmpty()) AlertDialog.Builder(this@MainActivity).setMessage("Доступные модели не найдены.").setPositiveButton("OK", null).show()
                                    else AlertDialog.Builder(this@MainActivity).setTitle("Модель " + providerNames[index])
                                        .setItems(models.toTypedArray()) { _, selected -> modelFields.getValue(provider).setText(models[selected]) }.show()
                                }
                            }
                        } catch (e: Exception) {
                            main.post {
                                if (!isFinishing && !isDestroyed) {
                                    isEnabled = true
                                    AlertDialog.Builder(this@MainActivity).setMessage(if (e is uz.jarvis.core.ApiFailure) e.message else "Нет связи с сервисом.")
                                        .setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    }.start()
                }
            })
        }
        box.addView(searchSwitch)
        val cloudSwitch = Switch(this).apply {
            text = "Нейроголос OpenAI (расходует API)"
            isChecked = prefs.getBoolean("cloud_voice", false)
        }
        val voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("cedar", "marin"))
            setSelection(if (prefs.getString("openai_voice", "cedar") == "marin") 1 else 0)
        }
        box.addView(cloudSwitch)
        box.addView(voiceSpinner)
        box.addView(label("OpenAI — синтетический голос. Нужен ключ OpenAI. При недоступности используется голос телефона."))
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
                try {
                    providers.forEach { secureKeys.save(it, keyFields.getValue(it).text.toString()) }
                } catch (e: Exception) {
                    caption.text = "Не удалось сохранить ключи в защищённом хранилище."
                    return@setPositiveButton
                }
                prefs.edit()
                    .putString("name", nameField.text.toString().trim())
                    .putString("wake_words", wakeField.text.toString())
                    .putString("provider", providers[providerSpinner.selectedItemPosition])
                    .putString("gemini_model", modelFields.getValue("gemini").text.toString().trim())
                    .putString("openai_model", modelFields.getValue("openai").text.toString().trim())
                    .putBoolean("web_search", searchSwitch.isChecked)
                    .putBoolean("cloud_voice", cloudSwitch.isChecked)
                    .putString("openai_voice", if (voiceSpinner.selectedItemPosition == 1) "marin" else "cedar")
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
        val provider = prefs.getString("provider", "gemini") ?: "gemini"
        brain = MobileBrain(name, provider, secureKeys.read(provider),
            prefs.getString(provider + "_model", "").orEmpty(), prefs.getBoolean("web_search", true))
            .also { it.importTurns(loadTurns()) }
        providerStatus.text = (if (provider == "openai") "OpenAI" else "Gemini") +
            if (brain?.available == true) " · ключ добавлен" else " · без ключа"
    }

    /** Разговор хранится в настройках приложения, чтобы пережить перезапуск. */
    private fun loadTurns(): List<Pair<String, String>> = try {
        val array = org.json.JSONArray(prefs.getString("history", "[]"))
        (0 until array.length()).map { i ->
            val item = array.getJSONArray(i)
            item.getString(0) to item.getString(1)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveTurns(turns: List<Pair<String, String>>) {
        val array = org.json.JSONArray()
        turns.forEach { (q, a) -> array.put(org.json.JSONArray().put(q).put(a)) }
        prefs.edit().putString("history", array.toString()).apply()
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
        t.setSpeechRate(0.95f)
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
        if (clean.isEmpty()) { speechFinished(null); return }
        stopListening()
        if (mode == TextToSpeech.QUEUE_FLUSH) { cloudSpeech.cancel(); t?.stop() }
        val id = "kartal-" + System.nanoTime()
        pendingSpeech += id
        fun localVoice() {
            if (!ttsReady || t == null || t.speak(clean, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) speechFinished(id)
        }
        if (prefs.getBoolean("cloud_voice", false)) {
            cloudSpeech.speak(clean, secureKeys.read("openai"), prefs.getString("openai_voice", "cedar") ?: "cedar",
                done = { main.post { speechFinished(id) } },
                fallback = { main.post { if (!isDestroyed) localVoice() } })
        } else localVoice()
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
        if (listening || isFinishing || !resumed || answerStreaming) return
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
        if (answerStreaming) { caption.text = "Дождитесь окончания ответа."; return }
        val (called, rest) = if (typed) true to heard else Commands.stripWakeWord(heard, wakeWords)
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
        if (text.trim().lowercase() in listOf("проверь голос", "проверка голоса")) {
            say("Привет! Я Картал. Проверяем чёткость речи: один, два, три. Готов к работе."); return
        }
        val command = Commands.parse(text)
        val now = LocalDateTime.now()
        when (command.action) {
            Action.EXIT -> { say(I18n.t(lang, "bye")); main.postDelayed({ finish() }, 1500) }
            Action.EMPTY -> { waitingForCommand = true; say(I18n.t(lang, "listening")) }
            Action.RESET -> { brain?.reset(); saveTurns(emptyList()); say(I18n.t(lang, "reset")) }
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
            Action.ASK -> ask(text)
        }
    }

    private fun ask(question: String) {
        val b = brain ?: return
        cloudSpeech.cancel()
        tts?.stop()
        stopListening()
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
            saveTurns(b.exportTurns())
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
