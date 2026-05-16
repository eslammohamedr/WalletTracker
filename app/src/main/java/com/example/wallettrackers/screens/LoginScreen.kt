package com.example.wallettrackers.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wallettrackers.auth.AuthViewModel
import com.example.wallettrackers.ui.theme.WalletTrackersTheme
import com.example.wallettrackers.ui.theme.*

@Composable
fun LoginScreen(
    viewModel: AuthViewModel = viewModel(),
    onSignInClick: () -> Unit,
    onFacebookSignInClick: () -> Unit,
    onLoginSuccess: () -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onSignUpClick: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        contentVisible = true
    }

    // Floating animation for logo
    val infiniteTransition = rememberInfiniteTransition(label = "logo_float")
    val logoOffsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_float_y"
    )
    val logoGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_glow"
    )

    LaunchedEffect(key1 = state.isSignInSuccessful) {
        if (state.isSignInSuccessful) {
            Toast.makeText(context, "Sign in successful", Toast.LENGTH_LONG).show()
            onLoginSuccess()
            viewModel.resetState()
        }
    }

    LaunchedEffect(key1 = state.signInError) {
        state.signInError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
    ) {
        // Subtle glow at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(AppPrimary.copy(alpha = 0.2f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))

            // Floating animated logo
            Box(
                modifier = Modifier.graphicsLayer { translationY = logoOffsetY },
                contentAlignment = Alignment.Center
            ) {
                // Animated glow ring
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    AppViolet.copy(alpha = logoGlowAlpha),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(AccentGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { -30 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Wallet Trackers",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = AppTextPrimary,
                        letterSpacing = (-1).sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Intelligence in every transaction",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // Email/Password Glass Card — slides in from below
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Authentication",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = AppTextPrimary,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email address") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Email,
                                contentDescription = null,
                                tint = AppVioletLight
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            focusedContainerColor = AppBackground.copy(alpha = 0.5f),
                            unfocusedContainerColor = AppBackground.copy(alpha = 0.5f),
                            focusedBorderColor = AppVioletLight,
                            unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                            focusedLabelColor = AppVioletLight,
                            unfocusedLabelColor = AppTextSecondary
                        )
                    )

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = AppVioletLight
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = AppTextSecondary
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            focusedContainerColor = AppBackground.copy(alpha = 0.5f),
                            unfocusedContainerColor = AppBackground.copy(alpha = 0.5f),
                            focusedBorderColor = AppVioletLight,
                            unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                            focusedLabelColor = AppVioletLight,
                            unfocusedLabelColor = AppTextSecondary
                        )
                    )

                    Spacer(Modifier.height(24.dp))

                    // Gradient sign-in button
                    val canLogin = email.isNotBlank() && password.isNotBlank()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (canLogin) AccentGradient else SolidColor(AppPrimary.copy(alpha = 0.2f)))
                    ) {
                        Button(
                            onClick = { onSignInWithEmail(email, password) },
                            modifier = Modifier.fillMaxSize(),
                            enabled = canLogin,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent
                            ),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Text(
                                "Sign In",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (canLogin) Color.White else AppTextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = AppPrimary.copy(alpha = 0.3f))
                Text(
                    "  social access  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextMuted,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = AppPrimary.copy(alpha = 0.3f))
            }

            Spacer(Modifier.height(24.dp))

            // Google Sign In
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSignInClick() },
                color = AppSurface,
                border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("G", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black, color = Color(0xFF4285F4))
                    Spacer(Modifier.width(12.dp))
                    Text("Continue with Google", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold, color = AppTextPrimary)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Facebook Sign In
            Button(
                onClick = onFacebookSignInClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1877F2))
            ) {
                Text("f", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(12.dp))
                Text("Continue with Facebook", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(40.dp))

            // Sign Up link
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "New to Wallet Trackers?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary
                )
                TextButton(
                    onClick = onSignUpClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        "Create Account",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Black,
                        color = AppVioletLight
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    WalletTrackersTheme {
        LoginScreen(
            onSignInClick = {},
            onFacebookSignInClick = {},
            onLoginSuccess = {},
            onSignInWithEmail = { _, _ -> },
            onSignUpClick = {}
        )
    }
}
