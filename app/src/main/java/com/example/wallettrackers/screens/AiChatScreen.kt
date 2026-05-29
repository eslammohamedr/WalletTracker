package com.example.wallettrackers.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.ui.theme.*
import com.example.wallettrackers.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.chatMessages
    val isLoading by viewModel.isChatLoading
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val quickSuggestions = listOf(
        "Monthly summary",
        "Top expenses",
        "Budget status",
        "How can I save more?"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Assistant", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                    }
                },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearChat() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear chat", tint = AppTextSecondary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        },
        containerColor = AppBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                // Show quick suggestions when chat is empty
                if (messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Ask me anything about your finances",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppTextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "I can analyze your spending, suggest budgets, and help you plan better.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary
                            )
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            quickSuggestions.forEach { suggestion ->
                                SuggestionChipRow(
                                    text = suggestion,
                                    onClick = {
                                        viewModel.sendChatMessage(suggestion)
                                    }
                                )
                            }
                        }
                    }
                }

                items(messages) { msg ->
                    ChatBubble(
                        message = msg.content,
                        isUser = msg.role == "user"
                    )
                }

                // Loading indicator
                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = AppVioletLight,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking...", color = AppTextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Input area
            Surface(
                color = AppSurface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Ask about your finances...", color = AppTextMuted) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppVioletLight,
                            unfocusedBorderColor = AppTextMuted.copy(alpha = 0.3f),
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            cursorColor = AppVioletLight
                        ),
                        maxLines = 3,
                        singleLine = false
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank() && !isLoading) {
                                viewModel.sendChatMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && !isLoading,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = AppVioletLight,
                            contentColor = AppTextPrimary
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: String, isUser: Boolean) {
    val bgColor = if (isUser) AppVioletLight.copy(alpha = 0.15f) else AppSurface
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bgColor)
                .padding(12.dp)
        ) {
            Text(
                text = message,
                color = AppTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun SuggestionChipRow(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppVioletLight),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(AppVioletLight.copy(alpha = 0.4f))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, fontSize = 14.sp)
    }
}
