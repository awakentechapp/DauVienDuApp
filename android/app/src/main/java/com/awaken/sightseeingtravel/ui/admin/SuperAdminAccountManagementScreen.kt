package com.awaken.sightseeingtravel.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaken.sightseeingtravel.core.model.CreateManagedUserRequest
import com.awaken.sightseeingtravel.core.model.UserProfileDto
import com.awaken.sightseeingtravel.ui.BrandColors
import kotlinx.coroutines.launch

@Composable
internal fun SuperAdminAccountManagementScreen(
    currentUser: UserProfileDto?,
    selectedLanguage: String,
    initialRole: String,
    onLoadUsers: suspend () -> List<UserProfileDto>,
    onCreateUser: suspend (CreateManagedUserRequest) -> UserProfileDto,
    onSetUserActive: suspend (String, Boolean) -> UserProfileDto,
    onBack: () -> Unit
) {
    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    var selectedRole by rememberSaveable { mutableStateOf(if (initialRole == "Tourist") 1 else 0) }
    var users by remember { mutableStateOf(emptyList<UserProfileDto>()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshUsers() {
        if (currentUser?.isSuperAdmin != true) return
        isLoading = true
        error = null
        try {
            users = onLoadUsers()
        } catch (exception: Exception) {
            error = exception.message ?: if (isEn) "Could not load accounts." else "Không tải được tài khoản."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(currentUser?.id) { refreshUsers() }
    LaunchedEffect(initialRole) { selectedRole = if (initialRole == "Tourist") 1 else 0 }

    val roleName = if (selectedRole == 0) "Admin" else "Tourist"
    val visibleUsers = users
        .filter { it.role.equals(roleName, ignoreCase = true) }
        .filter { search.isBlank() || it.displayName.contains(search, true) || it.email.contains(search, true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandColors.Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (isEn) "Back" else "Quay lại", tint = BrandColors.Primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEn) "Account management" else "Quản lý tài khoản",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.PrimaryDeep
                )
                Text(
                    text = if (isEn) "Super Admin" else "Super Admin",
                    style = MaterialTheme.typography.labelMedium,
                    color = BrandColors.MutedText
                )
            }
            IconButton(onClick = { scope.launch { refreshUsers() } }, enabled = !isLoading) {
                Text(if (isEn) "↻" else "↻", color = BrandColors.Primary, fontSize = 22.sp)
            }
        }

        if (currentUser?.isSuperAdmin != true) {
            Card(colors = CardDefaults.cardColors(containerColor = BrandColors.Surface)) {
                Text(
                    text = if (isEn) "Only Super Admin can manage accounts." else "Chỉ Super Admin được quản lý tài khoản.",
                    modifier = Modifier.padding(16.dp),
                    color = BrandColors.Error
                )
            }
        } else {
            TabRow(selectedTabIndex = selectedRole) {
                Tab(
                    selected = selectedRole == 0,
                    onClick = { selectedRole = 0 },
                    text = { Text(if (isEn) "Admins" else "Admin (${users.count { it.role.equals("Admin", true) }})") },
                    icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = null) }
                )
                Tab(
                    selected = selectedRole == 1,
                    onClick = { selectedRole = 1 },
                    text = { Text(if (isEn) "Tourists" else "Tourist (${users.count { it.role.equals("Tourist", true) }})") },
                    icon = { Icon(Icons.Default.People, contentDescription = null) }
                )
            }

            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Primary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (isEn) "Create $roleName account" else "Tạo tài khoản $roleName")
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(if (isEn) "Search accounts" else "Tìm tài khoản") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (!error.isNullOrBlank()) {
                Surface(color = BrandColors.Sand, shape = RoundedCornerShape(12.dp)) {
                    Text(error.orEmpty(), color = BrandColors.Error, modifier = Modifier.padding(12.dp))
                }
            }
            if (!notice.isNullOrBlank()) {
                Surface(color = BrandColors.Cream, shape = RoundedCornerShape(12.dp)) {
                    Text(notice.orEmpty(), color = BrandColors.Primary, modifier = Modifier.padding(12.dp))
                }
            }

            if (isLoading && users.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally), color = BrandColors.Primary)
            } else if (visibleUsers.isEmpty()) {
                Text(
                    text = if (isEn) "No $roleName accounts found." else "Chưa có tài khoản $roleName.",
                    color = BrandColors.MutedText,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleUsers, key = { it.id }) { account ->
                        AccountRow(
                            account = account,
                            isEn = isEn,
                            onActiveChange = { active ->
                                scope.launch {
                                    error = null
                                    notice = null
                                    try {
                                        val updated = onSetUserActive(account.id, active)
                                        users = users.map { if (it.id == updated.id) updated else it }
                                        notice = if (isEn) "Account status updated." else "Đã cập nhật trạng thái tài khoản."
                                    } catch (exception: Exception) {
                                        error = exception.message ?: if (isEn) "Could not update account." else "Không cập nhật được tài khoản."
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateManagedAccountDialog(
            roleName = roleName,
            isEn = isEn,
            onDismiss = { showCreateDialog = false },
            onCreate = { displayName, email, password ->
                scope.launch {
                    error = null
                    notice = null
                    try {
                        val roleNumber = if (selectedRole == 0) 2 else 1
                        val created = onCreateUser(
                            CreateManagedUserRequest(
                                email = email.trim(),
                                password = password,
                                displayName = displayName.trim(),
                                role = roleNumber,
                                preferredLanguage = if (isEn) "en" else "vi"
                            )
                        )
                        users = listOf(created) + users
                        selectedRole = if (created.role.equals("Tourist", true)) 1 else 0
                        showCreateDialog = false
                        notice = if (isEn) "Account created." else "Đã tạo tài khoản."
                    } catch (exception: Exception) {
                        error = exception.message ?: if (isEn) "Could not create account." else "Không tạo được tài khoản."
                    }
                }
            }
        )
    }
}

@Composable
private fun AccountRow(
    account: UserProfileDto,
    isEn: Boolean,
    onActiveChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(account.displayName, fontWeight = FontWeight.Bold, color = BrandColors.PrimaryDeep)
                Text(account.email, style = MaterialTheme.typography.bodySmall, color = BrandColors.MutedText)
                Text(
                    text = if (account.isActive) {
                        if (isEn) "Active" else "Đang hoạt động"
                    } else {
                        if (isEn) "Disabled" else "Đã khóa"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (account.isActive) BrandColors.Terracotta else BrandColors.Error
                )
            }
            Switch(checked = account.isActive, onCheckedChange = onActiveChange)
        }
    }
}

@Composable
private fun CreateManagedAccountDialog(
    roleName: String,
    isEn: Boolean,
    onDismiss: () -> Unit,
    onCreate: (displayName: String, email: String, password: String) -> Unit
) {
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canCreate = displayName.isNotBlank() && email.contains('@') && password.length >= 8

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEn) "Create $roleName account" else "Tạo tài khoản $roleName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(if (isEn) "Display name" else "Tên hiển thị") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (isEn) "Temporary password (8+ characters)" else "Mật khẩu ban đầu (từ 8 ký tự)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(displayName, email, password) }, enabled = canCreate) {
                Text(if (isEn) "Create" else "Tạo")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isEn) "Cancel" else "Hủy") }
        }
    )
}
