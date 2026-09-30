package com.example.wallettrackers.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import com.example.wallettrackers.remote.ExchangeRateApi
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.launch

import com.example.wallettrackers.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyConverterScreen(onBack: () -> Unit, rateApi: ExchangeRateApi? = null) {
    val coroutineScope = rememberCoroutineScope()
    val exchangeRateApi = remember(rateApi) { rateApi ?: ExchangeRateApi.create() }

    var usdToEgpRate by remember { mutableStateOf<Double?>(null) }
    var eurToEgpRate by remember { mutableStateOf<Double?>(null) }
    var goldPriceEgpPerGram by remember { mutableStateOf<Double?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var ratesAsOf by remember { mutableStateOf<String?>(null) }
    var amountInput by remember { mutableStateOf("1") }
    var sourceCurrency by remember { mutableStateOf("EGP") }
    var currencyMenuExpanded by remember { mutableStateOf(false) }

    val rateFreshness = ratesAsOf?.let { date ->
        val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
        when {
            parsedDate == null -> "Quote date unavailable"
            parsedDate.isBefore(LocalDate.now(ZoneOffset.UTC)) -> "Rates as of $date · stale"
            else -> "Rates as of $date"
        }
    } ?: "Quote date unavailable"

    fun fetchRates() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val usdResponse = exchangeRateApi.getLatestRates("USD")
                val eurResponse = exchangeRateApi.getLatestRates("EUR")
                val usdRate = usdResponse.rates["EGP"]?.takeIf { it > 0.0 && it.isFinite() }
                    ?: error("USD/EGP rate is missing or invalid")
                val eurRate = eurResponse.rates["EGP"]?.takeIf { it > 0.0 && it.isFinite() }
                    ?: error("EUR/EGP rate is missing or invalid")
                usdToEgpRate = usdRate
                eurToEgpRate = eurRate
                ratesAsOf = listOf(usdResponse.date, eurResponse.date).minOrNull()
            } catch (e: Exception) {
                errorMessage = if (usdToEgpRate != null && eurToEgpRate != null) {
                    "Failed to refresh rates. Showing previously fetched rates."
                } else {
                    "Failed to fetch rates. Check your connection."
                }
            }
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
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
                            text = rateFreshness,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Refresh to check for newer quotes",
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

            val amount = amountInput.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
            val amountValue = amount?.toDouble()?.takeIf { it.isFinite() }
            val usdRate = usdToEgpRate
            val eurRate = eurToEgpRate
            val egpValue = when (sourceCurrency) {
                "USD" -> amountValue?.let { value -> usdRate?.let { value * it } }
                "EUR" -> amountValue?.let { value -> eurRate?.let { value * it } }
                else -> amountValue
            }?.takeIf { it.isFinite() }
            val usdValue = egpValue?.div(usdRate ?: Double.NaN)?.takeIf { it.isFinite() }
            val eurValue = egpValue?.div(eurRate ?: Double.NaN)?.takeIf { it.isFinite() }

            Text("Convert an amount", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                ExposedDropdownMenuBox(
                    expanded = currencyMenuExpanded,
                    onExpandedChange = { currencyMenuExpanded = !currencyMenuExpanded },
                    modifier = Modifier.width(120.dp)
                ) {
                    OutlinedTextField(
                        value = sourceCurrency,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("From") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyMenuExpanded) },
                        modifier = Modifier.menuAnchor().testTag("converterSourceCurrency")
                    )
                    ExposedDropdownMenu(
                        expanded = currencyMenuExpanded,
                        onDismissRequest = { currencyMenuExpanded = false }
                    ) {
                        listOf("EGP", "USD", "EUR").forEach { currency ->
                            DropdownMenuItem(
                                text = { Text(currency) },
                                onClick = {
                                    sourceCurrency = currency
                                    currencyMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            if (amount == null) {
                Text("Enter a valid non-negative amount", color = AppRed, style = MaterialTheme.typography.bodySmall)
            } else if (usdRate == null || eurRate == null) {
                Text("Conversion unavailable until USD and EUR rates load", color = AppTextSecondary, style = MaterialTheme.typography.bodySmall)
            } else if (egpValue == null || usdValue == null || eurValue == null) {
                Text("Amount is outside the supported conversion range", color = AppRed, style = MaterialTheme.typography.bodySmall)
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${egpValue.format(2)} EGP", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary, fontWeight = FontWeight.Bold)
                        HorizontalDivider()
                        Text("USD equivalent: ${usdValue.format(2)} USD", color = AppTextSecondary)
                        Text("EUR equivalent: ${eurValue.format(2)} EUR", color = AppTextSecondary)
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

private fun Double.format(digits: Int) = String.format(Locale.ENGLISH, "%.${digits}f", this)
