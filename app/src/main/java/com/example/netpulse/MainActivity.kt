package com.example.netpulse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class NetworkStatus { WIFI, CELLULAR, OFFLINE }

private class NetworkObserver(context: Context) {
    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val status: Flow<NetworkStatus> = callbackFlow {
        fun currentStatus(): NetworkStatus {
            val network = manager.activeNetwork ?: return NetworkStatus.OFFLINE
            val caps = manager.getNetworkCapabilities(network) ?: return NetworkStatus.OFFLINE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return NetworkStatus.OFFLINE
            }
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus.CELLULAR
                else -> NetworkStatus.OFFLINE
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(currentStatus()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(currentStatus())
            }
            override fun onLost(network: Network) { trySend(currentStatus()) }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            manager.registerNetworkCallback(request, callback)
            trySend(currentStatus()) // Do not remain OFFLINE until the next network event.
        } catch (error: Exception) {
            trySend(NetworkStatus.OFFLINE)
            close(error)
        }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }
}

private data class PendingData(val id: Long, val content: String, val isSynced: Boolean)

private class MainViewModel(private val appContext: Context, observer: NetworkObserver) : ViewModel() {
    val networkStatus = MutableStateFlow(NetworkStatus.OFFLINE)
    val dataQueue = mutableStateListOf<PendingData>()
    private val preferences = appContext.getSharedPreferences("netpulse", Context.MODE_PRIVATE)
    private var nextId = 1L

    init {
        restore()
        viewModelScope.launch {
            observer.status.collectLatest { status ->
                networkStatus.value = status
                if (status != NetworkStatus.OFFLINE) syncQueue()
            }
        }
    }

    fun addData(text: String) {
        val item = PendingData(nextId++, text.trim(), networkStatus.value != NetworkStatus.OFFLINE)
        dataQueue.add(0, item)
        persist()
    }

    private fun syncQueue() {
        var changed = false
        dataQueue.indices.forEach { index ->
            if (!dataQueue[index].isSynced) {
                dataQueue[index] = dataQueue[index].copy(isSynced = true)
                changed = true
            }
        }
        if (changed) persist()
    }

    private fun persist() {
        val json = JSONArray()
        dataQueue.forEach { json.put(JSONObject().apply {
            put("id", it.id); put("content", it.content); put("synced", it.isSynced)
        }) }
        preferences.edit().putString("queue", json.toString()).apply()
    }

    private fun restore() {
        val json = preferences.getString("queue", null) ?: return
        runCatching {
            val array = JSONArray(json)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getLong("id")
                dataQueue.add(PendingData(id, item.getString("content"), item.getBoolean("synced")))
                nextId = maxOf(nextId, id + 1)
            }
        }
    }
}

private class MainViewModelFactory(
    private val context: Context,
    private val observer: NetworkObserver
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(context.applicationContext, observer) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val observer = NetworkObserver(applicationContext)
        val viewModel: MainViewModel by viewModels { MainViewModelFactory(applicationContext, observer) }
        setContent { MaterialTheme { NetPulseScreen(viewModel) } }
    }
}

@Composable
private fun NetPulseScreen(viewModel: MainViewModel) {
    val status by viewModel.networkStatus.collectAsState()
    var input by remember { mutableStateOf("") }
    val headerColor by animateColorAsState(
        when (status) {
            NetworkStatus.WIFI -> Color(0xFF2E7D32)
            NetworkStatus.CELLULAR -> Color(0xFF1976D2)
            NetworkStatus.OFFLINE -> Color(0xFFD32F2F)
        }, label = "headerColor"
    )
    val label = when (status) {
        NetworkStatus.WIFI -> "Wi-Fi aktief (optimaal)"
        NetworkStatus.CELLULAR -> "Mobiele data aktief"
        NetworkStatus.OFFLINE -> "Vanlyn-modus — data word plaaslik gestoor"
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(headerColor).padding(16.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OutlinedTextField(input, { input = it }, label = { Text("Tik 'n boodskap of data-item") }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Button({ if (input.isNotBlank()) { viewModel.addData(input); input = "" } }, Modifier.fillMaxWidth()) {
                Text("Stoor / stuur data")
            }
            Spacer(Modifier.height(24.dp))
            Text("Plaaslike data-tou:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(viewModel.dataQueue, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                        containerColor = if (item.isSynced) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                    )) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text(item.content, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Text(if (item.isSynced) "✓ Gestuur" else "⏳ Wag vir sein...",
                                color = if (item.isSynced) Color(0xFF2E7D32) else Color(0xFFC62828),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
