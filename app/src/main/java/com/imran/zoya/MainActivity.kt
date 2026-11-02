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
        when {
            lower.contains("समय") || lower.contains("time") ->
                say("अभी समय है " + java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date()))
            lower.contains("google") || lower.contains("गूगल") || lower.contains("search") || lower.contains("सर्च") -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(s))))
                say("Google search खोल रही हूँ।")
            }
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
            else -> say("मैंने सुना: $s। सामान्य AI जवाबों के लिए अभी सुरक्षित AI API connection जोड़ना बाकी है।")
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
    override fun onDestroy() { recognizer?.destroy(); tts?.shutdown(); super.onDestroy() }
    
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
