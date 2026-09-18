package com.example.netpulse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.activity.viewModels
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
import kotlinx.coroutines.launch

// ==========================================
// 1. NETWERK-MONITOR (HARDWARE LUISTERKERK)
// ==========================================
enum class NetworkStatus {
    WIFI, CELLULAR, OFFLINE
}

class NetworkObserver(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val status: Flow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                val status = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkStatus.WIFI
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> NetworkStatus.CELLULAR
                    else -> NetworkStatus.OFFLINE
                }
                trySend(status)
            }

            override fun onLost(network: Network) {
                trySend(NetworkStatus.OFFLINE)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}

// ==========================================
// 2. DATA-MODEL EN LOGIKA (VIEWMODEL)
// ==========================================
data class PendingData(
    val id: Int,
    val content: String,
    var isSynced: Boolean
)

class MainViewModel(private val networkObserver: NetworkObserver) : ViewModel() {
    val networkStatus = MutableStateFlow(NetworkStatus.OFFLINE)
    val dataQueue = mutableStateListOf<PendingData>()
    private var counter = 1

    init {
        viewModelScope.launch {
            networkObserver.status.collect { status ->
                networkStatus.value = status
                if (status != NetworkStatus.OFFLINE) {
                    syncQueue()
                }
            }
        }
    }

    fun addData(text: String) {
        val isOnline = networkStatus.value != NetworkStatus.OFFLINE
        val newItem = PendingData(
            id = counter++,
            content = text,
            isSynced = isOnline
        )
        dataQueue.add(0, newItem) // Voeg heel bo aan die lys toe
    }

    private fun syncQueue() {
        // Outomatiese her-sinkronisasie sodra netwerk opkom
        dataQueue.forEachIndexed { index, item ->
            if (!item.isSynced) {
                dataQueue[index] = item.copy(isSynced = true)
            }
        }
    }
}

// Factory om die Context skoon oor te dra na die ViewModel
class MainViewModelFactory(private val networkObserver: NetworkObserver) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(networkObserver) as T
        }
        throw IllegalArgumentException("Onbekende ViewModel klas")
    }
}

// ==========================================
// 3. HOOF-AKTIWITEIT EN UI (JETPACK COMPOSE)
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val observer = NetworkObserver(applicationContext)
        val viewModel: MainViewModel by viewModels { MainViewModelFactory(observer) }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF5F5F5)
                ) {
                    NetPulseScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetPulseScreen(viewModel: MainViewModel) {
    val netStatus by viewModel.networkStatus.collectAsState()
    var inputText by remember { mutableStateOf("") }

    // Dynamiese kleurverandering vir die status-balk
    val headerBgColor by animateColorAsState(
        targetValue = when (netStatus) {
            NetworkStatus.WIFI -> Color(0xFF2E7D32)     // Groen
            NetworkStatus.CELLULAR -> Color(0xFF1976D2) // Blou
            NetworkStatus.OFFLINE -> Color(0xFFD32F2F)  // Rooi
        }, label = "HeaderColor"
    )

    val statusLabel = when (netStatus) {
        NetworkStatus.WIFI -> "Wi-Fi Aktief (Optimaal)"
        NetworkStatus.CELLULAR -> "Mobiele Data Aktief"
        NetworkStatus.OFFLINE -> "OFFLINE MODUS - Data word plaaslik gestoor"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // --- BO-BALK (SEIN STATUS) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBgColor)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = statusLabel,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // --- INVOER-VELD ---
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Tik 'n boodskap of data-item") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.addData(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Stoor / Stuur Data", fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Plaaslike Data-Tou (Sync Status):",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.DarkGray
            )

            Spacer(modifier = Modifier.height(8.dp))

            // --- LYS VAN DATA ---
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(viewModel.dataQueue) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (item.isSynced) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.content,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (item.isSynced) "✓ Gestuur" else "⏳ Wag vir sein...",
                                color = if (item.isSynced) Color(0xFF2E7D32) else Color(0xFFC62828),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

