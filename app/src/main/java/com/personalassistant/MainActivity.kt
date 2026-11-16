package com.personalassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = SecureStore(this)
        setContent { AssistantApp(prefs) }
    }
}

data class ChatMessage(val role: String, val content: String)
data class ApiConfig(
    val provider: String = "OpenAI-compatible",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = ""
)

class SecureStore(activity: ComponentActivity) {
    private val masterKey = MasterKey.Builder(activity).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        activity, "assistant_secure_prefs", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun get(k: String, d: String = "") = prefs.getString(k, d) ?: d
    fun put(k: String, v: String) { prefs.edit().putString(k, v).apply() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantApp(store: SecureStore) {
    val providers = listOf("OpenAI-compatible", "Anthropic Claude", "Google Gemini")
    var provider by remember { mutableStateOf(store.get("provider", providers[0])) }
    var baseUrl by remember { mutableStateOf(store.get("baseUrl", "https://api.openai.com/v1")) }
    var model by remember { mutableStateOf(store.get("model", "gpt-4o-mini")) }
    var apiKey by remember { mutableStateOf(store.get("apiKey")) }
    var systemPrompt by remember { mutableStateOf(store.get("systemPrompt", "You are a helpful personal AI assistant.")) }
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()
    val bg = Color(0xFF0B1020)
    val panel = Color(0xFF151D32)
    val accent = Color(0xFF8B7CFF)

    MaterialTheme(colorScheme = darkColorScheme(
        primary = accent, background = bg, surface = panel,
        onBackground = Color(0xFFEAF0FF), onSurface = Color(0xFFEAF0FF)
    )) {
        Scaffold(
            containerColor = bg,
            topBar = {
                TopAppBar(
                    title = { Column {
                        Text("Personal AI", fontWeight = FontWeight.Bold)
                        Text("$provider  •  $model", fontSize = 11.sp, color = Color.LightGray)
                    } },
                    actions = {
                        IconButton(onClick = { messages.clear(); error = "" }) { Icon(Icons.Default.Add, "New chat") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
                )
            },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().background(panel).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f).background(bg, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (input.isBlank()) Text("Message your assistant…", color = Color.Gray)
                        BasicTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                            maxLines = 5)
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        enabled = !busy && input.isNotBlank(),
                        onClick = {
                            val prompt = input.trim()
                            input = ""
                            messages.add(ChatMessage("user", prompt))
                            error = ""
                            busy = true
                            scope.launch {
                                try {
                                    val answer = withContext(Dispatchers.IO) {
                                        AiClient.reply(ApiConfig(provider, baseUrl, model, apiKey), systemPrompt, messages.toList())
                                    }
                                    messages.add(ChatMessage("assistant", answer))
                                } catch (e: Exception) {
                                    error = e.message ?: "Request failed"
                                } finally { busy = false }
                            }
                        }
                    ) { Icon(Icons.Default.Send, "Send") }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (messages.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Text("Your AI. Your choice.", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Connect an API provider in Settings, then start chatting.", color = Color.LightGray)
                        Spacer(Modifier.height(18.dp))
                        Text("Supports OpenAI-compatible APIs, Anthropic Claude, and Google Gemini.", color = accent)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(messages) { msg ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.role == "user") Arrangement.End else Arrangement.Start) {
                                Column(Modifier.widthIn(max = 320.dp).background(if (msg.role == "user") Color(0xFF34305F) else panel, RoundedCornerShape(16.dp)).padding(13.dp)) {
                                    Text(if (msg.role == "user") "YOU" else "ASSISTANT", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text(msg.content, color = Color.White)
                                }
                            }
                        }
                        if (busy) item { Text("Thinking…", color = Color.LightGray, modifier = Modifier.padding(8.dp)) }
                    }
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF8A8A), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("AI provider settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Provider")
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(provider) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                providers.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { provider = p; expanded = false
                                    when (p) {
                                        "Anthropic Claude" -> { baseUrl = "https://api.anthropic.com"; model = "claude-3-5-sonnet-latest" }
                                        "Google Gemini" -> { baseUrl = "https://generativelanguage.googleapis.com"; model = "gemini-2.0-flash" }
                                        else -> { baseUrl = "https://api.openai.com/v1"; model = "gpt-4o-mini" }
                                    }
                                }) }
                            }
                        }
                        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API base URL") }, singleLine = true)
                        OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, singleLine = true)
                        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, singleLine = true)
                        OutlinedTextField(systemPrompt, { systemPrompt = it }, label = { Text("Assistant instructions") }, minLines = 2)
                        Text("API keys are stored encrypted on this device. Requests go directly to your selected provider.", fontSize = 11.sp, color = Color.Gray)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        store.put("provider", provider); store.put("baseUrl", baseUrl.trim().trimEnd('/'))
                        store.put("model", model.trim()); store.put("apiKey", apiKey)
                        store.put("systemPrompt", systemPrompt); showSettings = false
                    }) { Text("Save settings") }
                },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
            )
        }
    }
}

object AiClient {
    fun reply(c: ApiConfig, system: String, history: List<ChatMessage>): String {
        require(c.apiKey.isNotBlank()) { "Add your API key in Settings first." }
        require(c.model.isNotBlank()) { "Enter a model ID in Settings." }
        return when (c.provider) {
            "Anthropic Claude" -> anthropic(c, system, history)
            "Google Gemini" -> gemini(c, system, history)
            else -> openAi(c, system, history)
        }
    }

    private fun post(url: String, key: String, body: JSONObject, headers: Map<String,String> = emptyMap()): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 25000; conn.readTimeout = 90000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
        headers.forEach { (k,v) -> conn.setRequestProperty(k,v) }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            val detail = try { JSONObject(raw).toString(2) } catch (_: Exception) { raw }
            throw IllegalStateException("HTTP $code: $detail")
        }
        return JSONObject(raw)
    }

    private fun openAi(c: ApiConfig, system: String, history: List<ChatMessage>): String {
        val msgs = JSONArray().put(JSONObject().put("role","system").put("content",system))
        history.forEach { msgs.put(JSONObject().put("role",it.role).put("content",it.content)) }
        val body = JSONObject().put("model",c.model).put("messages",msgs)
        val json = post(c.baseUrl.trimEnd('/') + "/chat/completions", c.apiKey, body)
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun anthropic(c: ApiConfig, system: String, history: List<ChatMessage>): String {
        val msgs = JSONArray()
        history.forEach { msgs.put(JSONObject().put("role",it.role).put("content",it.content)) }
        val body = JSONObject().put("model",c.model).put("max_tokens",2048).put("system",system).put("messages",msgs)
        val json = post(c.baseUrl.trimEnd('/') + "/v1/messages", c.apiKey, body,
            mapOf("x-api-key" to c.apiKey, "anthropic-version" to "2023-06-01"))
        val content = json.getJSONArray("content")
        return (0 until content.length()).mapNotNull { i ->
            content.getJSONObject(i).optString("text").takeIf { it.isNotBlank() }
        }.joinToString("\\n")
    }

    private fun gemini(c: ApiConfig, system: String, history: List<ChatMessage>): String {
        val contents = JSONArray()
        history.forEach { m ->
            contents.put(JSONObject().put("role", if (m.role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text",m.content))))
        }
        val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",system))))
            .put("contents",contents).put("generationConfig",JSONObject().put("maxOutputTokens",2048))
        val url = c.baseUrl.trimEnd('/') + "/v1beta/models/${c.model}:generateContent?key=${java.net.URLEncoder.encode(c.apiKey,"UTF-8")}"
        val json = post(url, "", body)
        return json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }
}
