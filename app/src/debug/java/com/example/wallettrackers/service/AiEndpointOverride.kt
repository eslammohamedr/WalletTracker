package com.example.wallettrackers.service

import android.content.Context
import java.io.File

object AiEndpointOverride {
    private var directory: File? = null

    fun initialize(context: Context) {
        directory = context.applicationContext.filesDir
    }

    val baseUrl: String?
        get() = directory?.resolve("qa_ai_endpoint")?.takeIf { it.isFile }
            ?.readText()?.trim()?.takeIf { it == "http://127.0.0.1:8765" }
}
