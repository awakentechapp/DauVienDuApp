package com.awaken.sightseeingtravel.ui

import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaken.sightseeingtravel.R

private enum class AuthPage {
    WELCOME,
    SIGN_IN,
    REGISTER,
    FORGOT_PASSWORD,
    RESET_PASSWORD
}

private object AuthPalette {
    val ink = BrandColors.Text
    val brick = BrandColors.Primary
    val teal = BrandColors.Terracotta
    val paper = BrandColors.Surface
    val muted = BrandColors.MutedText
}

@Composable
internal fun AuthScreen(
    selectedLanguage: String,
    isLoading: Boolean,
    errorMessage: String?,
    onLanguageChange: (String) -> Unit,
    onClearError: () -> Unit,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (email: String, password: String, displayName: String) -> Unit,
    onRequestPasswordReset: (email: String) -> Unit,
    onConfirmPasswordReset: (email: String, code: String, newPassword: String, onSuccess: () -> Unit) -> Unit,
    noticeMessage: String? = null
) {
    var page by rememberSaveable { mutableStateOf(AuthPage.WELCOME) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var resetCode by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    val trimmedEmail = email.trim()
    val emailIsValid = Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()
    val canSubmit = when (page) {
        AuthPage.WELCOME -> false
        AuthPage.SIGN_IN -> emailIsValid && password.isNotBlank()
        AuthPage.REGISTER -> emailIsValid && displayName.isNotBlank() &&
            password.length >= 8 && password == confirmPassword
        AuthPage.FORGOT_PASSWORD -> emailIsValid
        AuthPage.RESET_PASSWORD -> emailIsValid && resetCode.length == 8 &&
            password.length >= 8 && password == confirmPassword
    }
    val navigateBack: () -> Unit = {
        page = if (page == AuthPage.FORGOT_PASSWORD || page == AuthPage.RESET_PASSWORD) {
            AuthPage.SIGN_IN
        } else {
            AuthPage.WELCOME
        }
        password = ""
        confirmPassword = ""
        resetCode = ""
        passwordVisible = false
        confirmPasswordVisible = false
        localError = null
        onClearError()
    }

    BackHandler(enabled = page != AuthPage.WELCOME) {
        navigateBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BrandColors.Sand, AuthPalette.paper, BrandColors.Paper)
                )
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 68.dp, end = 20.dp)
                .size(94.dp),
            shape = CircleShape,
            color = BrandColors.Cream.copy(alpha = 0.28f)
        ) {}

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onLanguageChange(if (isEn) "vi" else "en") },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.88f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = if (isEn) "Switch to Vietnamese" else "Chuyển sang tiếng Anh",
                        tint = AuthPalette.teal
                    )
                }
            }

            Spacer(Modifier.height(if (page == AuthPage.WELCOME) 14.dp else 2.dp))

            if (page == AuthPage.WELCOME) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = AuthPalette.paper,
                    shadowElevation = 5.dp
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.logo_doc_do_gach),
                        contentDescription = if (isEn) "Dau Vien Du logo" else "Logo Dấu Viễn Du",
                        modifier = Modifier
                            .size(width = 132.dp, height = 132.dp)
                            .padding(13.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Dấu Viễn Du",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AuthPalette.ink,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isEn) "Travel deeper. Discover the stories within." else "Đi để chạm vào chiều sâu văn hóa.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuthPalette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 7.dp, bottom = 20.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AuthFeature(Icons.Filled.LocationOn, if (isEn) "Places" else "Điểm đến", Modifier.weight(1f))
                    AuthFeature(Icons.Filled.AutoStories, if (isEn) "Stories" else "Câu chuyện", Modifier.weight(1f))
                    AuthFeature(Icons.Filled.Explore, "AR", Modifier.weight(1f))
                }

                Spacer(Modifier.height(26.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (isEn) "Start your journey" else "Bắt đầu hành trình",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = AuthPalette.ink
                        )
                        Text(
                            text = if (isEn) "Create an account to save places and unlock local stories." else "Tạo tài khoản để lưu điểm đến và khám phá những câu chuyện địa phương.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuthPalette.muted
                        )
                        Button(
                            onClick = { page = AuthPage.REGISTER; localError = null; onClearError() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AuthPalette.brick)
                        ) {
                            Text(
                                text = if (isEn) "Create an account" else "Tạo tài khoản",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { page = AuthPage.SIGN_IN; localError = null; onClearError() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = if (isEn) "I already have an account · Sign in" else "Đã có tài khoản · Đăng nhập",
                                color = AuthPalette.teal,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else {
                AuthForm(
                    page = page,
                    isEn = isEn,
                    displayName = displayName,
                    onDisplayNameChange = { displayName = it },
                    email = email,
                    onEmailChange = { email = it },
                    password = password,
                    onPasswordChange = { password = it },
                    confirmPassword = confirmPassword,
                    onConfirmPasswordChange = { confirmPassword = it },
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    confirmPasswordVisible = confirmPasswordVisible,
                    onToggleConfirmPassword = { confirmPasswordVisible = !confirmPasswordVisible },
                    isLoading = isLoading,
                    canSubmit = canSubmit,
                    errorMessage = localError ?: errorMessage,
                    noticeMessage = noticeMessage,
                    resetCode = resetCode,
                    onResetCodeChange = { resetCode = it.filter(Char::isDigit).take(8) },
                    onForgotPassword = {
                        page = AuthPage.FORGOT_PASSWORD
                        resetCode = ""
                        localError = null
                        onClearError()
                    },
                    onResendResetCode = { onRequestPasswordReset(trimmedEmail) },
                    onBack = navigateBack,
                    onSwitchPage = {
                        page = if (page == AuthPage.SIGN_IN) AuthPage.REGISTER else AuthPage.SIGN_IN
                        password = ""
                        confirmPassword = ""
                        resetCode = ""
                        passwordVisible = false
                        confirmPasswordVisible = false
                        localError = null
                        onClearError()
                    },
                    onSubmit = {
                        when (page) {
                            AuthPage.REGISTER -> {
                                if (password != confirmPassword) {
                                    localError = if (isEn) "Passwords do not match." else "Mật khẩu xác nhận chưa khớp."
                                } else {
                                    localError = null
                                    onRegister(trimmedEmail, password, displayName.trim())
                                }
                            }
                            AuthPage.SIGN_IN -> {
                                localError = null
                                onLogin(trimmedEmail, password)
                            }
                            AuthPage.FORGOT_PASSWORD -> {
                                localError = null
                                onClearError()
                                page = AuthPage.RESET_PASSWORD
                                onRequestPasswordReset(trimmedEmail)
                            }
                            AuthPage.RESET_PASSWORD -> {
                                if (password != confirmPassword) {
                                    localError = if (isEn) "Passwords do not match." else "Mật khẩu xác nhận chưa khớp."
                                } else {
                                    localError = null
                                    onConfirmPasswordReset(trimmedEmail, resetCode, password) {
                                        page = AuthPage.SIGN_IN
                                        password = ""
                                        confirmPassword = ""
                                        resetCode = ""
                                    }
                                }
                            }
                            AuthPage.WELCOME -> Unit
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AuthFeature(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.82f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = AuthPalette.teal, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(5.dp))
            Text(label, fontSize = 11.sp, color = AuthPalette.ink, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AuthForm(
    page: AuthPage,
    isEn: Boolean,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    confirmPasswordVisible: Boolean,
    onToggleConfirmPassword: () -> Unit,
    isLoading: Boolean,
    canSubmit: Boolean,
    errorMessage: String?,
    noticeMessage: String?,
    resetCode: String,
    onResetCodeChange: (String) -> Unit,
    onForgotPassword: () -> Unit,
    onResendResetCode: () -> Unit,
    onBack: () -> Unit,
    onSwitchPage: () -> Unit,
    onSubmit: () -> Unit
) {
    val isRegister = page == AuthPage.REGISTER
    val isSignIn = page == AuthPage.SIGN_IN
    val isForgotPassword = page == AuthPage.FORGOT_PASSWORD
    val isResetPassword = page == AuthPage.RESET_PASSWORD
    val isResetFlow = isForgotPassword || isResetPassword

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = AuthPalette.brick)
            Spacer(Modifier.size(6.dp))
            Text(
                if (isResetFlow) {
                    if (isEn) "Back to sign in" else "Quay lại đăng nhập"
                } else {
                    if (isEn) "Back" else "Quay lại"
                },
                color = AuthPalette.brick
            )
        }

        if (isSignIn) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = AuthPalette.paper,
                    shadowElevation = 5.dp
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.logo_doc_do_gach),
                        contentDescription = if (isEn) "Dau Vien Du logo" else "Logo Dấu Viễn Du",
                        modifier = Modifier.size(132.dp).padding(8.dp)
                    )
                }
            }
        }

        Text(
            text = when {
                isRegister -> if (isEn) "Create your account" else "Tạo tài khoản mới"
                isForgotPassword -> if (isEn) "Forgot password?" else "Quên mật khẩu?"
                isResetPassword -> if (isEn) "Reset your password" else "Đặt lại mật khẩu"
                else -> if (isEn) "Welcome back" else "Chào mừng bạn trở lại"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = AuthPalette.ink,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = when {
                isRegister -> if (isEn) "Join Dấu Viễn Du and keep your journeys close." else "Tham gia Dấu Viễn Du để lưu lại những hành trình đáng nhớ."
                isForgotPassword -> if (isEn) "Enter your email and we’ll send a one-time reset code." else "Nhập email để nhận mã xác minh đặt lại mật khẩu."
                isResetPassword -> if (isEn) "Enter the 8-digit code from your email and choose a new password." else "Nhập mã 8 chữ số trong email và chọn mật khẩu mới."
                else -> if (isEn) "Sign in to continue exploring." else "Đăng nhập để tiếp tục khám phá."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AuthPalette.muted
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isRegister) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = onDisplayNameChange,
                        label = { Text(if (isEn) "Display name" else "Tên hiển thị") },
                        placeholder = { Text(if (isEn) "Your name" else "Tên của bạn") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!isResetPassword) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = onEmailChange,
                        label = { Text("Email") },
                        placeholder = { Text("ban@example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = email,
                        onValueChange = {},
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = false,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = resetCode,
                        onValueChange = onResetCodeChange,
                        label = { Text(if (isEn) "8-digit reset code" else "Mã đặt lại gồm 8 số") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!isForgotPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = {
                            Text(
                                when {
                                    isRegister -> if (isEn) "Password" else "Mật khẩu"
                                    isResetPassword -> if (isEn) "New password" else "Mật khẩu mới"
                                    else -> if (isEn) "Password" else "Mật khẩu"
                                }
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = onTogglePassword) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) {
                                        if (isEn) "Hide password" else "Ẩn mật khẩu"
                                    } else {
                                        if (isEn) "Show password" else "Hiện mật khẩu"
                                    }
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isRegister || isResetPassword) {
                    Text(
                        text = if (isEn) "Use at least 8 characters." else "Mật khẩu cần ít nhất 8 ký tự.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AuthPalette.muted
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = onConfirmPasswordChange,
                        label = {
                            Text(
                                if (isResetPassword) {
                                    if (isEn) "Confirm new password" else "Xác nhận mật khẩu mới"
                                } else {
                                    if (isEn) "Confirm password" else "Xác nhận mật khẩu"
                                }
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = onToggleConfirmPassword) {
                                Icon(
                                    imageVector = if (confirmPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (confirmPasswordVisible) {
                                        if (isEn) "Hide password" else "Ẩn mật khẩu"
                                    } else {
                                        if (isEn) "Show password" else "Hiện mật khẩu"
                                    }
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (confirmPassword.isNotEmpty() && confirmPassword != password) {
                        Text(
                            text = if (isEn) "Passwords do not match yet." else "Mật khẩu xác nhận chưa khớp.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuthPalette.brick
                        )
                    }
                }

                if (isSignIn) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onForgotPassword) {
                            Text(
                                if (isEn) "Forgot password?" else "Quên mật khẩu?",
                                color = AuthPalette.teal,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (!noticeMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BrandColors.Cream,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = noticeMessage,
                            color = AuthPalette.brick,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BrandColors.Sand,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = BrandColors.Error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = onSubmit,
                    enabled = canSubmit && !isLoading,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AuthPalette.brick)
                ) {
                    Text(
                        text = if (isLoading) {
                            if (isEn) "Please wait…" else "Đang xử lý…"
                        } else when {
                            isRegister -> if (isEn) "Create account" else "Tạo tài khoản"
                            isForgotPassword -> if (isEn) "Send reset code" else "Gửi mã đặt lại"
                            isResetPassword -> if (isEn) "Reset password" else "Đặt lại mật khẩu"
                            else -> if (isEn) "Sign in" else "Đăng nhập"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isResetPassword) {
                    TextButton(
                        onClick = onResendResetCode,
                        enabled = email.isNotBlank() && !isLoading,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(if (isEn) "Resend code" else "Gửi lại mã", color = AuthPalette.teal)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            isResetFlow -> if (isEn) "Return to sign in" else "Quay lại đăng nhập"
                            isRegister -> if (isEn) "Already have an account?" else "Đã có tài khoản?"
                            else -> if (isEn) "New to Dấu Viễn Du?" else "Chưa có tài khoản?"
                        },
                        color = AuthPalette.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = if (isResetFlow) onBack else onSwitchPage) {
                        Text(
                            text = when {
                                isResetFlow -> if (isEn) "Sign in" else "Đăng nhập"
                                isRegister -> if (isEn) "Sign in" else "Đăng nhập"
                                else -> if (isEn) "Create one" else "Đăng ký"
                            },
                            color = AuthPalette.teal,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
