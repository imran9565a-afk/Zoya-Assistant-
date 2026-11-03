package com.imran.zoya

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val green = Color.rgb(57, 255, 120)
    private val dark = Color.rgb(5, 12, 9)
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var wave: WaveView
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val securePrefs by lazy { getSharedPreferences("zoya_secure", MODE_PRIVATE) }
    private val tinyFishAlias = "ZoyaTinyFishKey"
    private val geminiAlias = "ZoyaGeminiKey"
    private val networkExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var geminiBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        tts = TextToSpeech(this, this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 18)
            setBackgroundColor(dark)
        }
        root.addView(TextView(this).apply {
            text = "Z O Y A"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(green)
            letterSpacing = .2f
        })
        root.addView(TextView(this).apply {
            text = "PERSONAL AI • VOICE CONSOLE"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        })
        status = TextView(this).apply {
            text = "SYSTEM READY"
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(0, 18, 0, 0)
        }
        root.addView(status)
        wave = WaveView()
        root.addView(wave, LinearLayout.LayoutParams(-1, 140))
        val listen = Button(this).apply {
            text = "START VOICE"
            setOnClickListener { listen() }
        }
        root.addView(listen)
        root.addView(Button(this).apply {
            text = "SET TINYFISH API KEY"
            setOnClickListener { showTinyFishKeyDialog() }
        })
        root.addView(Button(this).apply {
            text = "SET GEMINI API KEY"
            setOnClickListener { showGeminiKeyDialog() }
        })
        output = TextView(this).apply {
            text = "Zoya: नमस्ते! Voice या text से बात शुरू करो।"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(10, 14, 10, 14)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(12, 25, 18))
            addView(output)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val entry = EditText(this).apply {
            hint = "अपना संदेश लिखें"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
        }
        root.addView(entry)
        root.addView(Button(this).apply {
            text = "SEND"
            setOnClickListener {
                val s = entry.text.toString().trim()
                if (s.isNotEmpty()) {
                    line("You: $s")
                    entry.setText("")
                    command(s)
                }
            }
        })
        setContentView(root)
        val needed = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 11)
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("इस फोन में Speech Recognition उपलब्ध नहीं है।")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11)
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "LISTENING"; wave.active = true; wave.invalidate() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status.text = "PROCESSING" }
            override fun onError(error: Int) { status.text = "SYSTEM READY"; wave.active = false; wave.invalidate() }
            override fun onResults(results: Bundle?) {
                status.text = "SYSTEM READY"
                wave.active = false
                wave.invalidate()
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) { line("You: $spoken"); command(spoken) }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        }
        recognizer?.startListening(intent)
    }

    private fun command(raw: String) {
        val s = raw.trim()
        val lower = s.lowercase(Locale.ROOT)
        val normalized = lower
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val greetings = setOf(
            "assalamualaikum", "assalamu alaikum", "as salamu alaikum",
            "as-salamu alaikum", "salam", "salaam", "hello", "hi", "hey",
            "नमस्ते", "नमस्कार", "सलाम", "अस्सलामुअलैकुम"
        )
        val isGreeting = normalized in greetings ||
            normalized.startsWith("assalamualaikum ") ||
            normalized.startsWith("assalamu alaikum ") ||
            normalized.startsWith("hello ") ||
            normalized.startsWith("hi ") ||
            normalized.startsWith("hey ") ||
            normalized.startsWith("नमस्ते ") ||
            normalized.startsWith("सलाम ") ||
            normalized.startsWith("अस्सलामुअलैकुम ")

        when {
            normalized == "help" || normalized == "commands" || normalized == "मदद" || normalized == "कमांड बताओ" || normalized == "क्या कर सकती हो" ->
                say("आप मुझसे समय और तारीख पूछ सकते हैं, कैलकुलेटर चला सकते हैं, नोट याद रखवा सकते हैं, अलार्म लगा सकते हैं, ऐप या वेबसाइट खोल सकते हैं, Google पर खोज सकते हैं, कॉल और SMS स्क्रीन खोल सकते हैं। उदाहरण: याद रखो कल दूध लेना, मेरा नोट दिखाओ, 25 गुणा 4 कितना है, 7:30 का अलार्म लगाओ, YouTube खोलो।")
            normalized.startsWith("याद रखो ") || normalized.startsWith("याद रखना ") || normalized.startsWith("remember ") || normalized.startsWith("note ") -> {
                val note = s.replace(Regex("(?i)^(याद रखो|याद रखना|remember|note)\\s*"), "").trim()
                if (note.isBlank()) say("क्या याद रखना है, बताइए।")
                else { securePrefs.edit().putString("zoya_last_note", note).apply(); say("ठीक है, मैंने याद रख लिया: $note") }
            }
            normalized in setOf("मेरा नोट दिखाओ", "नोट दिखाओ", "show note", "show my note", "मुझे क्या याद है", "याद दिलाओ") -> {
                val note = securePrefs.getString("zoya_last_note", null)
                say(if (note.isNullOrBlank()) "अभी आपने कोई नोट सेव नहीं कराया है।" else "आपका सेव किया हुआ नोट है: $note")
            }
            normalized.startsWith("गणना ") || normalized.startsWith("calculate ") || normalized.startsWith("कैलकुलेट ") || normalized.startsWith("कितना होता है ") || normalized.matches(Regex(".*\\d+.*(जोड़|प्लस|minus|घटा|गुणा|मल्टीप्लाई|भाग|divide).*")) -> {
                val result = calculateSimple(s)
                say(result ?: "कृपया ऐसे पूछें: 25 गुणा 4 कितना होता है, या calculate 25 * 4")
            }
            normalized.contains("अलार्म") || normalized.contains("alarm") -> {
                val match = Regex("(\\d{1,2})[:.](\\d{2})").find(s)
                val hourMatch = Regex("(\\d{1,2})\\s*(?:बजे|am|pm)", RegexOption.IGNORE_CASE).find(s)
                val hour = match?.groupValues?.get(1)?.toIntOrNull() ?: hourMatch?.groupValues?.get(1)?.toIntOrNull()
                val minute = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
                if (hour == null || hour !in 0..23 || minute !in 0..59) say("समय बताइए, जैसे 7:30 का अलार्म लगाओ।")
                else {
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Zoya Alarm")
                    if (intent.resolveActivity(packageManager) != null) { startActivity(intent); say("अलार्म सेट करने की स्क्रीन खोल रही हूँ।") } else say("इस फोन में अलार्म ऐप उपलब्ध नहीं है।")
                }
            }
            normalized.startsWith("खोलो ") || normalized.startsWith("open ") || normalized.startsWith("launch ") -> {
                val target = s.replace(Regex("(?i)^(खोलो|open|launch)\\s*"), "").trim()
                openAppOrWebsite(target)
            }
            normalized.startsWith("वेबसाइट ") || normalized.startsWith("website ") || normalized.startsWith("खोल वेबसाइट ") -> {
                val target = s.replace(Regex("(?i)^(वेबसाइट|website|खोल वेबसाइट)\\s*"), "").trim()
                openAppOrWebsite(target, true)
            }
            normalized.contains("व्हाट्सएप") || normalized.contains("whatsapp") -> {
                val intent = packageManager.getLaunchIntentForPackage("com.whatsapp")
                if (intent != null) { startActivity(intent); say("WhatsApp खोल रही हूँ।") }
                else { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.whatsapp.com/"))); say("WhatsApp वेबसाइट खोल रही हूँ।") }
            }
            normalized in setOf("आज की तारीख", "तारीख बताओ", "date", "what is the date", "आज कौन सी तारीख है") ->
                say("आज की तारीख है " + java.text.SimpleDateFormat("dd MMMM yyyy", Locale("hi", "IN")).format(java.util.Date()))
            normalized in setOf("बैटरी कितनी है", "battery", "battery status", "बैटरी बताओ") -> {
                val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                say(if (level >= 0) "फोन की बैटरी " + (level * 100 / scale) + " प्रतिशत है।" else "बैटरी की जानकारी नहीं मिल सकी।")
            }
            isGreeting ->
                say("वअलैकुम अस्सलाम! मैं ज़ोया हूँ। बताइए, मैं आपकी क्या मदद करूँ?")
            normalized in setOf("how are you", "how are you doing", "कैसे हो", "कैसी हो", "आप कैसे हैं", "तुम कैसी हो") ->
                say("मैं अच्छी हूँ, धन्यवाद! आप कैसे हैं? मैं आपकी क्या मदद करूँ?")
            normalized in setOf("thank you", "thanks", "धन्यवाद", "शुक्रिया") ->
                say("आपका स्वागत है! बताइए, और क्या मदद करूँ?")
            normalized in setOf("who are you", "तुम कौन हो", "आप कौन हैं", "तुम्हारा नाम क्या है") ->
                say("मैं ज़ोया हूँ, आपकी पर्सनल AI वॉइस असिस्टेंट।")
            normalized in setOf("bye", "goodbye", "अलविदा", "बाय") ->
                say("अल्लाह हाफ़िज़! फिर बात करेंगे।")
            lower.contains("समय") || lower.contains("time") ->
                say("अभी समय है " + java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date()))
            lower.contains("google") || lower.contains("गूगल") || lower.contains("search") ||
                lower.contains("सर्च") || lower.contains("खोज") || lower.contains("इंटरनेट पर") ||
                lower.contains("वेब पर") || lower.contains("news") || lower.contains("खबर") ||
                lower.contains("price") || lower.contains("मौसम") ->
                searchTinyFish(s)
            lower.contains("कॉल") || lower.contains("call") -> {
                val name = s.replace(Regex("(?i)call|कॉल|करो|लगाओ|फोन"), "").trim()
                val number = contactNumber(name)
                if (number == null) say("Contact नहीं मिला या Contacts permission उपलब्ध नहीं है।")
                else {
                    val action = if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL
                    startActivity(Intent(action, Uri.parse("tel:$number")))
                    say("कॉल स्क्रीन खोल रही हूँ।")
                }
            }
            lower.contains("sms") || lower.contains("मैसेज") || lower.contains("message") -> {
                val number = contactNumber(s)
                if (number == null) say("Contact नहीं मिला।")
                else {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
                    say("SMS composer खोल दिया है। भेजने से पहले संदेश जाँच लेना।")
                }
            }
            else ->
                chatWithGemini(s)
        }
    }


    private fun calculateSimple(raw: String): String? {
        val text = raw.lowercase(Locale.ROOT)
            .replace("कितना होता है", "").replace("कितना", "")
            .replace("गणना", "").replace("कैलकुलेट", "").replace("calculate", "")
            .replace("जोड़", "+").replace("प्लस", "+").replace("plus", "+")
            .replace("घटा", "-").replace("माइनस", "-").replace("minus", "-")
            .replace("गुणा", "*").replace("मल्टीप्लाई", "*").replace("multiply", "*").replace("into", "*")
            .replace("भाग", "/").replace("डिवाइड", "/").replace("divide", "/").trim()
        val m = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+*/-])\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").find(text) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[3].toDoubleOrNull() ?: return null
        val value = when (m.groupValues[2]) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> if (b != 0.0) a / b else return "शून्य से भाग नहीं कर सकते।"; else -> return null }
        return "जवाब है " + (if (value % 1.0 == 0.0) value.toLong().toString() else value.toString())
    }

    private fun openAppOrWebsite(target: String, forceWebsite: Boolean = false) {
        if (target.isBlank()) { say("कौन सा ऐप या वेबसाइट खोलनी है?"); return }
        val knownSites = mapOf("google" to "https://www.google.com", "गूगल" to "https://www.google.com", "youtube" to "https://www.youtube.com", "यूट्यूब" to "https://www.youtube.com", "facebook" to "https://www.facebook.com", "फेसबुक" to "https://www.facebook.com", "instagram" to "https://www.instagram.com", "इंस्टाग्राम" to "https://www.instagram.com")
        val site = knownSites[target.lowercase(Locale.ROOT)]
        if (forceWebsite || target.contains(".") || site != null) {
            val url = site ?: if (target.startsWith("http")) target else "https://$target"
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); say("वेबसाइट खोल रही हूँ।") }
            catch (_: Exception) { say("यह वेबसाइट नहीं खुल सकी।") }
            return
        }
        val packages = packageManager.getInstalledApplications(0)
        val app = packages.firstOrNull { packageManager.getApplicationLabel(it).toString().equals(target, true) || packageManager.getApplicationLabel(it).toString().contains(target, true) }
        val launch = app?.let { packageManager.getLaunchIntentForPackage(it.packageName) }
        if (launch != null) { startActivity(launch); say("$target ऐप खोल रही हूँ।") }
        else say("$target ऐप नहीं मिला।")
    }

    private fun readGeminiApiKey(): String? {
        return try {
            val encoded = securePrefs.getString("gemini_api_key", null) ?: return null
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getGeminiSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun chatWithGemini(query: String) {
        val key = readGeminiApiKey()
        if (key.isNullOrBlank()) {
            say("पहले SET GEMINI API KEY में अपनी Gemini API Key सेव करें।")
            return
        }
        if (geminiBusy) {
            say("मैं अभी आपके पिछले सवाल का जवाब तैयार कर रही हूँ। कृपया उसका जवाब आने तक थोड़ा इंतज़ार करें।")
            return
        }
        geminiBusy = true
        status.text = "ZOYA AI THINKING"
        line("Zoya: आपका संदेश मिल गया है। मैं जवाब तैयार कर रही हूँ…")
        networkExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val previousId = securePrefs.getString("gemini_previous_interaction_id", null)
                val payload = JSONObject().apply {
                    put("model", "gemini-3.7-flash")
                    put("input", query)
                    put("system_instruction", "You are Zoya, a helpful, friendly AI assistant. Reply naturally and clearly in the same language as the user. Prefer Hindi when the user writes Hindi. Be accurate; if unsure, say so.")
                    if (!previousId.isNullOrBlank()) put("previous_interaction_id", previousId)
                }
                var code = 0
                var body = ""
                for (attempt in 0..2) {
                    connection = (URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 45000
                        readTimeout = 120000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("x-goog-api-key", key)
                    }
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (code == 503 && attempt < 2) {
                        connection.disconnect()
                        Thread.sleep(1000L shl attempt)
                        continue
                    }
                    break
                }
                if (code !in 200..299) throw Exception("API error $code: " + body.take(500))
                val json = JSONObject(body)
                val interactionId = json.optString("id", "")
                if (interactionId.isNotBlank()) securePrefs.edit().putString("gemini_previous_interaction_id", interactionId).apply()
                var answer = json.optString("output_text", "").trim()
                if (answer.isBlank()) {
                    val outputItems = json.optJSONArray("steps") ?: json.optJSONArray("output")
                    if (outputItems != null) {
                        val pieces = ArrayList<String>()
                        for (i in 0 until outputItems.length()) {
                            val item = outputItems.optJSONObject(i) ?: continue
                            val content = item.optJSONArray("content")
                            if (content != null) for (j in 0 until content.length()) {
                                val part = content.optJSONObject(j) ?: continue
                                if (part.optString("type", "") == "text" || part.has("text")) {
                                    val t = part.optString("text", "")
                                    if (t.isNotBlank()) pieces.add(t)
                                }
                            }
                        }
                        answer = pieces.joinToString("\n").trim()
                    }
                }
                if (answer.isBlank()) answer = "मुझे इस बार जवाब नहीं मिला। कृपया फिर से पूछें।"
                val finalAnswer = answer
                runOnUiThread {
                    status.text = "SYSTEM READY"
                    line("Zoya: $finalAnswer")
                    tts?.speak(finalAnswer, TextToSpeech.QUEUE_FLUSH, null, "zoya_gemini")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "SYSTEM READY"
                    val message = when {
                        e.message?.contains("401") == true || e.message?.contains("403") == true -> "Gemini API Key गलत है या अनुमति नहीं मिली। ऐप में Key दोबारा जाँचें।"
                        e.message?.contains("503") == true -> "Gemini सर्वर अभी व्यस्त है। ZOYA ने 3 बार कोशिश की, लेकिन सेवा उपलब्ध नहीं हुई। 1-2 मिनट बाद फिर पूछें।"
                        e is java.net.SocketTimeoutException -> "Gemini का जवाब आने में बहुत समय लग रहा है। इंटरनेट कनेक्शन जाँचें और थोड़ी देर बाद फिर कोशिश करें।"
                        e.message?.contains("429") == true -> "Gemini की उपयोग सीमा पूरी हो गई है। कुछ देर बाद कोशिश करें या AI Studio में quota जाँचें।"
                        e.message?.contains("400") == true -> {
                            securePrefs.edit().remove("gemini_previous_interaction_id").apply()
                            "Gemini अनुरोध स्वीकार नहीं हुआ। एक बार फिर वही सवाल भेजें।"
                        }
                        else -> "Gemini कनेक्शन त्रुटि: " + (e.message ?: "अज्ञात समस्या").take(220)
                    }
                    say(message)
                }
            } finally {
                connection?.disconnect()
                runOnUiThread {
                    geminiBusy = false
                    status.text = "SYSTEM READY"
                }
            }
        }
    }

    private fun showGeminiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Paste Gemini API key here"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setMessage("अपनी Gemini Key यहाँ ऐप में डालें। यह फोन में encrypted रूप में सेव होगी। इसे किसी के साथ साझा न करें।")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, getGeminiSecretKey())
                    val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                    val packed = cipher.iv + encrypted
                    securePrefs.edit().putString("gemini_api_key", Base64.encodeToString(packed, Base64.NO_WRAP))
                        .remove("gemini_previous_interaction_id").apply()
                    say("Gemini API Key इस फोन में encrypted रूप में सेव हो गई है।")
                } catch (_: Exception) { say("Gemini Key सेव नहीं हो सकी। कृपया दोबारा कोशिश करें।") }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun getGeminiSecretKey(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val saved = store.getKey(geminiAlias, null) as? SecretKey
        if (saved != null) return saved
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            geminiAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun readTinyFishApiKey(): String? {
        return try {
            val encoded = securePrefs.getString("tinyfish_api_key", null) ?: return null
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getTinyFishSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun searchTinyFish(query: String) {
        val key = readTinyFishApiKey()
        if (key.isNullOrBlank()) {
            say("पहले SET TINYFISH API KEY में अपनी API Key सेव करें।")
            return
        }
        status.text = "SEARCHING THE WEB"
        say("मैं इंटरनेट पर खोज रही हूँ।")
        networkExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val endpoint = "https://api.search.tinyfish.ai?query=" + Uri.encode(query) + "&location=IN&language=hi"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("X-API-Key", key)
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (code !in 200..299) throw Exception("API error $code")
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                val answer = StringBuilder()
                if (results != null) {
                    for (i in 0 until minOf(results.length(), 5)) {
                        val item = results.optJSONObject(i) ?: continue
                        val title = item.optString("title", "परिणाम")
                        val snippet = item.optString("snippet", "")
                        val url = item.optString("url", "")
                        answer.append("\n\n").append(i + 1).append(". ").append(title)
                        if (snippet.isNotBlank()) answer.append("\n").append(snippet)
                        if (url.isNotBlank()) answer.append("\n").append(url)
                    }
                }
                val resultText = if (answer.isNotEmpty()) answer.toString()
                    else "इस सवाल के लिए कोई वेब परिणाम नहीं मिला।"
                runOnUiThread {
                    status.text = "SYSTEM READY"
                    line("Zoya: TinyFish Search परिणाम:$resultText")
                    val spoken = if (results != null && results.length() > 0) {
                        results.optJSONObject(0)?.optString("snippet", "खोज पूरी हुई।") ?: "खोज पूरी हुई।"
                    } else "कोई परिणाम नहीं मिला।"
                    tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "zoya_search")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "SYSTEM READY"
                    val message = when {
                        e.message?.contains("401") == true || e.message?.contains("403") == true ->
                            "API Key स्वीकार नहीं हुई। Key जाँचकर दोबारा सेव करें।"
                        e.message?.contains("429") == true -> "TinyFish की सीमा पूरी हुई है। थोड़ी देर बाद कोशिश करें।"
                        else -> "TinyFish से कनेक्शन नहीं हो पाया। इंटरनेट और API Key जाँचें।"
                    }
                    say(message)
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun showTinyFishKeyDialog() {
        val input = EditText(this).apply {
            hint = "Paste TinyFish API key here"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("TinyFish API Key")
            .setMessage("Key केवल इस फोन में encrypted रूप में सेव होगी। इसे किसी के साथ साझा न करें।")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    try {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, getTinyFishSecretKey())
                        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                        val packed = cipher.iv + encrypted
                        securePrefs.edit().putString("tinyfish_api_key", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
                        say("TinyFish API Key इस फोन में सुरक्षित सेव हो गई है।")
                    } catch (_: Exception) {
                        say("Key सेव नहीं हो सकी। कृपया दोबारा कोशिश करें।")
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun getTinyFishSecretKey(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val saved = store.getKey(tinyFishAlias, null) as? SecretKey
        if (saved != null) return saved
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            tinyFishAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun contactNumber(name: String): String? {
        if (name.isBlank() || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val c = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"), null)
        c?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun line(s: String) { output.append("\n\n$s") }
    private fun say(s: String) { line("Zoya: $s"); tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "zoya") }
    override fun onInit(code: Int) { if (code == TextToSpeech.SUCCESS) tts?.language = Locale("hi", "IN") }
    override fun onDestroy() { recognizer?.destroy(); tts?.shutdown(); networkExecutor.shutdownNow(); super.onDestroy() }
    
    private inner class WaveView : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }
        var active = false
        private var phase = 0.0
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val n = 35
            val gap = width.toFloat() / (n + 1)
            for (i in 0 until n) {
                val h = if (active) (12.0 + abs(sin(i * .45 + phase)) * 48.0).toFloat() else (7.0 + abs(sin(i * .45)) * 10.0).toFloat()
                canvas.drawLine(gap * (i + 1), height / 2f - h, gap * (i + 1), height / 2f + h, p)
            }
            if (active) { phase += .25; postInvalidateDelayed(35) }
        }
    }
}
