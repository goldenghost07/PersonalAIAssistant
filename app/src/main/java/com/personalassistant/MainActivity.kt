package com.personalassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.window.DialogProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureStore(this)
        setContent { AssistantApp(store) }
    }
}

data class ChatMessage(val role: String, val content: String)

data class ApiConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My AI Provider",
    val protocol: String = ApiProtocol.OPENAI,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = ""
)

object ApiProtocol {
    const val OPENAI = "OpenAI-compatible"
    const val ANTHROPIC = "Anthropic Messages"
    const val GEMINI = "Google Gemini"
    val all = listOf(OPENAI, ANTHROPIC, GEMINI)
}

class SecureStore(activity: ComponentActivity) {
    private val masterKey = MasterKey.Builder(activity)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        activity,
        "assistant_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun loadProfiles(): MutableList<ApiConfig> {
        val saved = get("providerProfiles")
        if (saved.isNotBlank()) {
            return try {
                val array = JSONArray(saved)
                MutableList(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    ApiConfig(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name", "My AI Provider"),
                        protocol = item.optString("protocol", ApiProtocol.OPENAI),
                        baseUrl = item.optString("baseUrl", "https://api.openai.com/v1"),
                        model = item.optString("model", ""),
                        apiKey = item.optString("apiKey", "")
                    )
                }.ifEmpty { listOf(defaultProfile()) }.toMutableList()
            } catch (_: Exception) {
                listOf(defaultProfile()).toMutableList()
            }
        }

        // Migrate the original single-provider settings into the new profile format.
        val oldProvider = get("provider", "OpenAI-compatible")
        val oldProtocol = when (oldProvider) {
            "Anthropic Claude" -> ApiProtocol.ANTHROPIC
            "Google Gemini" -> ApiProtocol.GEMINI
            else -> ApiProtocol.OPENAI
        }
        val migrated = ApiConfig(
            name = oldProvider,
            protocol = oldProtocol,
            baseUrl = get("baseUrl", defaultBaseUrl(oldProtocol)),
            model = get("model", defaultModel(oldProtocol)),
            apiKey = get("apiKey")
        )
        return mutableListOf(migrated)
    }

    fun saveProfiles(profiles: List<ApiConfig>, activeId: String) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("protocol", profile.protocol)
                    .put("baseUrl", profile.baseUrl)
                    .put("model", profile.model)
                    .put("apiKey", profile.apiKey)
            )
        }
        prefs.edit()
            .putString("providerProfiles", array.toString())
            .putString("activeProfileId", activeId)
            .apply()
    }

    fun activeProfileId(): String = get("activeProfileId")

    companion object {
        fun defaultBaseUrl(protocol: String): String = when (protocol) {
            ApiProtocol.ANTHROPIC -> "https://api.anthropic.com"
            ApiProtocol.GEMINI -> "https://generativelanguage.googleapis.com"
            else -> "https://api.openai.com/v1"
        }

        fun defaultModel(protocol: String): String = when (protocol) {
            ApiProtocol.ANTHROPIC -> "claude-3-5-sonnet-latest"
            ApiProtocol.GEMINI -> "gemini-2.0-flash"
            else -> "gpt-4o-mini"
        }

        private fun defaultProfile() = ApiConfig()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantApp(store: SecureStore) {
    val profiles = remember {
        mutableStateListOf<ApiConfig>().apply { addAll(store.loadProfiles()) }
    }
    var activeId by remember {
        mutableStateOf(
            store.activeProfileId().takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.first().id
        )
    }
    val activeProfile = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    var systemPrompt by remember {
        mutableStateOf(store.get("systemPrompt", "You are a helpful personal AI assistant."))
    }
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()

    // Settings editor state.
    var editingId by remember { mutableStateOf(activeProfile.id) }
    var editingName by remember { mutableStateOf(activeProfile.name) }
    var editingProtocol by remember { mutableStateOf(activeProfile.protocol) }
    var editingBaseUrl by remember { mutableStateOf(activeProfile.baseUrl) }
    var editingModel by remember { mutableStateOf(activeProfile.model) }
    var editingApiKey by remember { mutableStateOf(activeProfile.apiKey) }
    var editingSystemPrompt by remember { mutableStateOf(systemPrompt) }
    var protocolMenuExpanded by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }

    val bg = Color(0xFF0B1020)
    val panel = Color(0xFF151D32)
    val accent = Color(0xFF8B7CFF)

    fun loadEditor(profile: ApiConfig) {
        editingId = profile.id
        editingName = profile.name
        editingProtocol = profile.protocol
        editingBaseUrl = profile.baseUrl
        editingModel = profile.model
        editingApiKey = profile.apiKey
    }

    fun startNewProfile() {
        val protocol = ApiProtocol.OPENAI
        editingId = UUID.randomUUID().toString()
        editingName = "New Provider"
        editingProtocol = protocol
        editingBaseUrl = SecureStore.defaultBaseUrl(protocol)
        editingModel = SecureStore.defaultModel(protocol)
        editingApiKey = ""
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            background = bg,
            surface = panel,
            onBackground = Color(0xFFEAF0FF),
            onSurface = Color(0xFFEAF0FF)
        )
    ) {
        Scaffold(
            containerColor = bg,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Personal AI", fontWeight = FontWeight.Bold)
                            Text(
                                "${activeProfile.name}  •  ${activeProfile.model}",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { messages.clear(); error = "" }) {
                            Icon(Icons.Default.Add, contentDescription = "New chat")
                        }
                        IconButton(onClick = {
                            loadEditor(activeProfile)
                            editingSystemPrompt = systemPrompt
                            showSettings = true
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
                )
            },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().background(panel).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.weight(1f)
                            .background(bg, RoundedCornerShape(22.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (input.isBlank()) {
                            Text("Message your assistant…", color = Color.Gray)
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 16.sp
                            ),
                            maxLines = 5
                        )
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
                            val requestProfile = activeProfile
                            val requestHistory = messages.toList()
                            scope.launch {
                                try {
                                    val answer = withContext(Dispatchers.IO) {
                                        AiClient.reply(requestProfile, systemPrompt, requestHistory)
                                    }
                                    messages.add(ChatMessage("assistant", answer))
                                } catch (e: Exception) {
                                    error = e.message ?: "Request failed"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (messages.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Your AI. Your choice.", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Connect an API provider in Settings, then start chatting.",
                            color = Color.LightGray
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Use OpenAI-compatible APIs, Anthropic Messages, or Google Gemini. Save separate profiles for different services and models.",
                            color = accent
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages) { msg ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = if (msg.role == "user") Arrangement.End else Arrangement.Start
                            ) {
                                Column(
                                    Modifier.widthIn(max = 320.dp)
                                        .background(
                                            if (msg.role == "user") Color(0xFF34305F) else panel,
                                            RoundedCornerShape(16.dp)
                                        )
                                        .padding(13.dp)
                                ) {
                                    Text(
                                        if (msg.role == "user") "YOU" else "ASSISTANT",
                                        color = accent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(msg.content, color = Color.White)
                                }
                            }
                        }
                        if (busy) {
                            item { Text("Thinking…", color = Color.LightGray, modifier = Modifier.padding(8.dp)) }
                        }
                    }
                }
                if (error.isNotBlank()) {
                    Text(
                        error,
                        color = Color(0xFFFF8A8A),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier.fillMaxWidth(0.94f),
                title = { Text("AI provider settings") },
                text = {
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text("Saved provider profiles", fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(
                                onClick = { profileMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    profiles.firstOrNull { it.id == editingId }?.name
                                        ?: editingName,
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(
                                expanded = profileMenuExpanded,
                                onDismissRequest = { profileMenuExpanded = false }
                            ) {
                                profiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text(profile.name) },
                                        onClick = {
                                            loadEditor(profile)
                                            profileMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { startNewProfile() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text("New profile")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (profiles.size > 1 && profiles.any { it.id == editingId }) {
                                        profiles.removeAll { it.id == editingId }
                                        val next = profiles.first()
                                        activeId = next.id
                                        loadEditor(next)
                                        store.saveProfiles(profiles, activeId)
                                    } else {
                                        error = "Keep at least one provider profile."
                                    }
                                },
                                enabled = profiles.size > 1 && profiles.any { it.id == editingId },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text("Delete")
                            }
                        }

                        OutlinedTextField(
                            value = editingName,
                            onValueChange = { editingName = it },
                            label = { Text("Provider name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("API protocol")
                        Box {
                            OutlinedButton(
                                onClick = { protocolMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(editingProtocol)
                            }
                            DropdownMenu(
                                expanded = protocolMenuExpanded,
                                onDismissRequest = { protocolMenuExpanded = false }
                            ) {
                                ApiProtocol.all.forEach { protocol ->
                                    DropdownMenuItem(
                                        text = { Text(protocol) },
                                        onClick = {
                                            if (protocol != editingProtocol) {
                                                editingProtocol = protocol
                                                editingBaseUrl = SecureStore.defaultBaseUrl(protocol)
                                                editingModel = SecureStore.defaultModel(protocol)
                                            }
                                            protocolMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = editingBaseUrl,
                            onValueChange = { editingBaseUrl = it },
                            label = { Text("API base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editingModel,
                            onValueChange = { editingModel = it },
                            label = { Text("Model ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editingApiKey,
                            onValueChange = { editingApiKey = it },
                            label = { Text("API key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editingSystemPrompt,
                            onValueChange = { editingSystemPrompt = it },
                            label = { Text("Assistant instructions") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "API keys are stored encrypted on this device. Requests are sent directly to the selected provider. Use the protocol that matches your provider's API.",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (editingName.isBlank() || editingBaseUrl.isBlank() || editingModel.isBlank()) {
                                error = "Provider name, API base URL, and model ID are required."
                            } else {
                                val updated = ApiConfig(
                                    id = editingId,
                                    name = editingName.trim(),
                                    protocol = editingProtocol,
                                    baseUrl = editingBaseUrl.trim().trimEnd('/'),
                                    model = editingModel.trim(),
                                    apiKey = editingApiKey.trim()
                                )
                                val index = profiles.indexOfFirst { it.id == editingId }
                                if (index >= 0) profiles[index] = updated else profiles.add(updated)
                                activeId = updated.id
                                systemPrompt = editingSystemPrompt
                                store.put("systemPrompt", systemPrompt)
                                store.saveProfiles(profiles, activeId)
                                error = ""
                                showSettings = false
                            }
                        }
                    ) { Text("Save profile") }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) { Text("Cancel") }
                }
            )
        }
    }
}

object AiClient {
    fun reply(config: ApiConfig, system: String, history: List<ChatMessage>): String {
        require(config.apiKey.isNotBlank()) { "Add an API key for this provider in Settings first." }
        require(config.model.isNotBlank()) { "Enter a model ID in Settings." }
        require(config.baseUrl.startsWith("https://") || config.baseUrl.startsWith("http://")) {
            "The API base URL must begin with https:// or http://."
        }

        return when (config.protocol) {
            ApiProtocol.ANTHROPIC -> anthropic(config, system, history)
            ApiProtocol.GEMINI -> gemini(config, system, history)
            else -> openAiCompatible(config, system, history)
        }
    }

    private fun post(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 25000
            connection.readTimeout = 90000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val detail = try {
                    JSONObject(raw).toString(2)
                } catch (_: Exception) {
                    raw
                }
                throw IllegalStateException("HTTP $code: $detail")
            }
            return JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun openAiCompatible(
        config: ApiConfig,
        system: String,
        history: List<ChatMessage>
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        history.forEach { message ->
            messages.put(JSONObject().put("role", message.role).put("content", message.content))
        }

        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)

        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val json = post(
            endpoint,
            body,
            mapOf("Authorization" to "Bearer ${config.apiKey}")
        )

        val content = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .get("content")

        return when (content) {
            is String -> content
            is JSONArray -> (0 until content.length()).mapNotNull { index ->
                content.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("\n")
            else -> content.toString()
        }
    }

    private fun anthropic(
        config: ApiConfig,
        system: String,
        history: List<ChatMessage>
    ): String {
        val messages = JSONArray()
        history.forEach { message ->
            messages.put(JSONObject().put("role", message.role).put("content", message.content))
        }

        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 2048)
            .put("system", system)
            .put("messages", messages)

        val endpoint = config.baseUrl.trimEnd('/') + "/v1/messages"
        val json = post(
            endpoint,
            body,
            mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )

        val content = json.getJSONArray("content")
        return (0 until content.length()).mapNotNull { index ->
            content.getJSONObject(index).optString("text").takeIf { it.isNotBlank() }
        }.joinToString("\n")
    }

    private fun gemini(
        config: ApiConfig,
        system: String,
        history: List<ChatMessage>
    ): String {
        val contents = JSONArray()
        history.forEach { message ->
            contents.put(
                JSONObject()
                    .put("role", if (message.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", message.content)))
            )
        }

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048))

        val encodedKey = URLEncoder.encode(config.apiKey, "UTF-8")
        val endpoint = config.baseUrl.trimEnd('/') +
            "/v1beta/models/${config.model}:generateContent?key=$encodedKey"
        val json = post(endpoint, body)

        val candidates = json.getJSONArray("candidates")
        val parts = candidates.getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")

        return (0 until parts.length()).mapNotNull { index ->
            parts.getJSONObject(index).optString("text").takeIf { it.isNotBlank() }
        }.joinToString("\n")
    }
}
