package com.example.wallettrackers.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.remote.ExchangeRateApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyConverterScreen(onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val exchangeRateApi = remember { ExchangeRateApi.create() }

    var usdToEgpRate by remember { mutableStateOf<Double?>(null) }
    var eurToEgpRate by remember { mutableStateOf<Double?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun fetchRates() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val usdResponse = exchangeRateApi.getLatestRates("USD")
                usdToEgpRate = usdResponse.rates["EGP"]

                val eurResponse = exchangeRateApi.getLatestRates("EUR")
                eurToEgpRate = eurResponse.rates["EGP"]
            } catch (e: Exception) {
                errorMessage = "Error fetching exchange rates"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        fetchRates()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Exchange Rates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { fetchRates() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            }

            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            usdToEgpRate?.let {
                RateDisplayCard(fromCurrency = "USD", toCurrency = "EGP", rate = it)
            }

            eurToEgpRate?.let {
                RateDisplayCard(fromCurrency = "EUR", toCurrency = "EGP", rate = it)
            }
        }
    }
}

@Composable
private fun RateDisplayCard(fromCurrency: String, toCurrency: String, rate: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "1 $fromCurrency",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "=",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${rate.format(2)} $toCurrency",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun Double.format(digits: Int) = "%.${digits}f".format(this)
