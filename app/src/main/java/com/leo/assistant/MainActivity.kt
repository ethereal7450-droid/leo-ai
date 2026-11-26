
package com.leo.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF101F32)
private val Cyan = Color(0xFF55DDF5)
private val White = Color(0xFFEAF7FF)

data class ChatMessage(val role: String, val text: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { LeoApp() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.UK
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "leo_reply")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun LeoApp() {
        val prefs = remember { getSharedPreferences("leo", MODE_PRIVATE) }
        var apiKey by remember {
            mutableStateOf(prefs.getString("gemini_key", "") ?: "")
        }
        var showSettings by remember { mutableStateOf(apiKey.isBlank()) }
        var input by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        val messages = remember {
            mutableStateListOf<ChatMessage>().apply {
                add(ChatMessage("model", "Systems online. I'm LEO. How can I help?"))
                val saved = prefs.getString("history", "[]") ?: "[]"
                try {
                    val arr = JSONArray(saved)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(ChatMessage(o.getString("role"), o.getString("text")))
                    }
                } catch (_: Exception) {}
            }
        }

        val scope = rememberCoroutineScope()

        val speechLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!spoken.isNullOrBlank()) input = spoken
            }
        }

        val micPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startSpeech(speechLauncher)
            else Toast.makeText(
                this,
                "Microphone permission is needed for voice input.",
                Toast.LENGTH_SHORT
            ).show()
        }

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "L E O",
                                color = Cyan,
                                fontSize = 27.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "PERSONAL AI ASSISTANT",
                                color = White.copy(alpha = .65f),
                                fontSize = 10.sp,
                                letterSpacing = 2.sp
                            )
                        }
                        Button(
                            onClick = { showSettings = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Panel)
                        ) {
                            Text("SETTINGS", color = Cyan, fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatusPill("AI CORE", if (apiKey.isNotBlank()) "CONNECTED" else "SET API KEY")
                        StatusPill("VOICE", "READY")
                        StatusPill("MEMORY", "LOCAL")
                    }

                    Spacer(Modifier.height(14.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .background(Panel, RoundedCornerShape(18.dp))
                            .padding(12.dp)
                    ) {
                        items(messages) { msg ->
                            val isUser = msg.role == "user"
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                            ) {
                                Text(
                                    if (isUser) "YOU" else "LEO",
                                    color = if (isUser) Cyan else White.copy(alpha = .55f),
                                    fontSize = 9.sp
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    msg.text,
                                    color = White,
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .background(
                                            if (isUser) Color(0xFF17384B)
                                            else Color(0xFF17283B),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(11.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text("Message LEO…", color = White.copy(alpha = .4f))
                            },
                            colors = TextFieldDefaults.textFieldColors(
                                textColor = White,
                                backgroundColor = Panel,
                                cursorColor = Cyan,
                                focusedIndicatorColor = Cyan,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(14.dp),
                            maxLines = 4
                        )

                        Spacer(Modifier.width(6.dp))

                        Button(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) startSpeech(speechLauncher)
                                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Panel),
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Text("🎙", fontSize = 18.sp)
                        }
                    }

                    Spacer(Modifier.height(7.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = !busy && input.isNotBlank(),
                        onClick = {
                            val prompt = input.trim()
                            input = ""
                            messages.add(ChatMessage("user", prompt))
                            saveHistory(prefs, messages)

                            if (prompt.startsWith("open ", true)) {
                                val appName = prompt.substringAfter(" ").trim()
                                val opened = launchApp(appName)
                                val reply = if (opened) "Opening $appName."
                                else "I couldn't find an app named $appName."
                                messages.add(ChatMessage("model", reply))
                                speak(reply)
                                saveHistory(prefs, messages)
                            } else if (apiKey.isBlank()) {
                                val reply = "Please add your Gemini API key in Settings to enable AI conversations."
                                messages.add(ChatMessage("model", reply))
                                speak(reply)
                            } else {
                                busy = true
                                scope.launch {
                                    val reply = withContext(Dispatchers.IO) {
                                        GeminiClient.ask(
                                            apiKey,
                                            prompt,
                                            messages.dropLast(1).takeLast(10)
                                        )
                                    }
                                    messages.add(ChatMessage("model", reply))
                                    saveHistory(prefs, messages)
                                    speak(reply)
                                    busy = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Cyan,
                            disabledBackgroundColor = Cyan.copy(alpha = .25f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            if (busy) "THINKING…" else "SEND TO LEO",
                            color = Navy,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                backgroundColor = Panel,
                title = { Text("LEO SETTINGS", color = Cyan) },
                text = {
                    Column {
                        Text("Gemini API key", color = White)
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = { Text("Paste API key") },
                            singleLine = true,
                            colors = TextFieldDefaults.textFieldColors(
                                textColor = White,
                                backgroundColor = Navy,
                                cursorColor = Cyan
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The key is stored locally on this device. Do not share it or include it in screenshots.",
                            color = White.copy(alpha = .65f),
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            prefs.edit().putString("gemini_key", apiKey.trim()).apply()
                            apiKey = apiKey.trim()
                            showSettings = false
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Cyan)
                    ) {
                        Text("SAVE", color = Navy)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showSettings = false },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Navy)
                    ) {
                        Text("CLOSE", color = White)
                    }
                }
            )
        }
    }

    @Composable
    private fun StatusPill(label: String, value: String) {
        Column(
            Modifier.background(Panel, CircleShape)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(label, color = White.copy(alpha = .5f), fontSize = 8.sp)
            Text(value, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }

    private fun startSpeech(
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to LEO")
        }
        try {
            launcher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Speech recognition is unavailable on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun launchApp(name: String): Boolean {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        val match = apps.firstOrNull {
            it.loadLabel(pm).toString().contains(name, ignoreCase = true)
        }
        val launch = match?.activityInfo?.let {
            pm.getLaunchIntentForPackage(it.packageName)
        }
        return if (launch != null) {
            startActivity(launch)
            true
        } else false
    }

    private fun saveHistory(
        prefs: android.content.SharedPreferences,
        messages: List<ChatMessage>
    ) {
        val arr = JSONArray()
        messages.takeLast(40).forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("text", m.text))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }
}

private object GeminiClient {
    fun ask(
        apiKey: String,
        prompt: String,
        history: List<ChatMessage>
    ): String {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$apiKey"
            )
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            val contents = JSONArray()
            history.filter { it.role == "user" || it.role == "model" }.forEach {
                contents.put(
                    JSONObject()
                        .put("role", it.role)
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", it.text))
                        )
                )
            }

            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
            )

            val body = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "You are LEO, a concise, capable, friendly Android personal assistant. Be honest about what you can and cannot do. Never claim to have performed phone actions unless the app did so."
                            )
                        )
                    )
                )
                .put("contents", contents)

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val response = (
                if (connection.responseCode in 200..299) connection.inputStream
                else connection.errorStream
            ).bufferedReader().use { it.readText() }

            if (connection.responseCode !in 200..299) {
                "Gemini error (${connection.responseCode}): " +
                    (try {
                        JSONObject(response).getJSONObject("error")
                            .getString("message")
                    } catch (_: Exception) {
                        "Check your API key and quota."
                    })
            } else {
                JSONObject(response)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
        } catch (e: Exception) {
            "I couldn't reach the AI service. Check your internet connection and API key. (${e.javaClass.simpleName})"
        } finally {
            connection?.disconnect()
        }
    }
}
