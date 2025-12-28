package com.example.wallettrackers.remote

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface ExchangeRateApi {

    suspend fun getLatestRates(base: String): ExchangeRateResponse

    companion object {
        fun create(): ExchangeRateApi {
            val client = HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                    })
                }
            }
            return ExchangeRateApiImpl(client)
        }
    }
}

class ExchangeRateApiImpl(private val client: HttpClient) : ExchangeRateApi {

    private val apiUrl = "https://api.exchangerate-api.com/v4/latest/"

    override suspend fun getLatestRates(base: String): ExchangeRateResponse {
        return client.get(apiUrl + base).body()
    }
}

@Serializable
data class ExchangeRateResponse(
    val base: String,
    val date: String,
    val rates: Map<String, Double>
)
