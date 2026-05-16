package com.example.wallettrackers.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.remote.ExchangeRateApi
import kotlinx.coroutines.launch

import com.example.wallettrackers.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyConverterScreen(onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val exchangeRateApi = remember { ExchangeRateApi.create() }

    var usdToEgpRate by remember { mutableStateOf<Double?>(null) }
    var eurToEgpRate by remember { mutableStateOf<Double?>(null) }
    var goldPriceEgpPerGram by remember { mutableStateOf<Double?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun fetchRates() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            // Fetch USD and EUR — failure here shows the error message
            try {
                val usdResponse = exchangeRateApi.getLatestRates("USD")
                usdToEgpRate = usdResponse.rates["EGP"]
                val eurResponse = exchangeRateApi.getLatestRates("EUR")
                eurToEgpRate = eurResponse.rates["EGP"]
            } catch (e: Exception) {
                errorMessage = "Failed to fetch rates. Check your connection."
            }
            // Gold price is fetched independently — won't break USD/EUR on failure
            try {
                val goldUsdPerOz = exchangeRateApi.getGoldPriceUSD()
                val usdRate = usdToEgpRate
                if (goldUsdPerOz != null && usdRate != null) {
                    goldPriceEgpPerGram = goldUsdPerOz * usdRate / 31.1035
                }
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { fetchRates() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exchange Rates", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { fetchRates() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AppVioletLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground
                )
            )
        },
        containerColor = AppBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header card — Hero Gradient
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(modifier = Modifier.fillMaxWidth().background(HeroGradient).padding(24.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "LIVE RATES vs EGP",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xB3C4B5FD),
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Market Real-time",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Auto-updates from global markets",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0x99C4B5FD)
                        )
                    }
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppVioletLight)
                }
            }

            errorMessage?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = AppRed.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, AppRed.copy(alpha = 0.3f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, null, tint = AppRed, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppRed,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            usdToEgpRate?.let {
                RateDisplayCard(
                    fromCurrency = "US Dollar",
                    fromSymbol = "$",
                    toCurrency = "EGP",
                    rate = it,
                    flagColor = Color(0xFF4CAF50)
                )
            }

            eurToEgpRate?.let {
                RateDisplayCard(
                    fromCurrency = "Euro",
                    fromSymbol = "€",
                    toCurrency = "EGP",
                    rate = it,
                    flagColor = Color(0xFFFFC107)
                )
            }

            goldPriceEgpPerGram?.let { pricePerGram ->
                RateDisplayCard(
                    fromCurrency = "Gold (24K)",
                    fromSymbol = "Au",
                    toCurrency = "EGP",
                    rate = pricePerGram,
                    flagColor = Color(0xFFFFB300),
                    subtitle = "Price per Gram"
                )
            }
        }
    }
}

@Composable
private fun RateDisplayCard(
    fromCurrency: String,
    fromSymbol: String,
    toCurrency: String,
    rate: Double,
    flagColor: Color,
    subtitle: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Currency badge
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(flagColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fromSymbol,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = flagColor
                )
            }

            Spacer(Modifier.width(20.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fromCurrency,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
                Text(
                    text = subtitle ?: "1 $fromCurrency / $toCurrency",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = rate.format(2),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = flagColor,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = toCurrency,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun Double.format(digits: Int) = "%.${digits}f".format(this)
