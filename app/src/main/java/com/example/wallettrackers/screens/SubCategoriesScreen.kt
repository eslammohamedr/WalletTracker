package com.example.wallettrackers.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.CustomSubCategory

import com.example.wallettrackers.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubCategoriesScreen(
    categoryName: String,
    customSubCategories: List<CustomSubCategory>,
    onBack: () -> Unit,
    onSubCategoryClick: (String) -> Unit,
    onAddSubCategory: (String) -> Unit,
    onDeleteSubCategory: (String) -> Unit = {}
) {
    val category = Categories.list.find { it.name == categoryName }

    var showAddDialog by remember { mutableStateOf(false) }
    var newSubCategoryName by remember { mutableStateOf("") }

    val customForThis = customSubCategories.filter { it.parentCategory == categoryName }
    val normalizedNewName = newSubCategoryName.trim()
    val duplicateName = normalizedNewName.isNotEmpty() && (
        category?.subCategories?.any { it.name.equals(normalizedNewName, ignoreCase = true) } == true ||
            customForThis.any { it.name.equals(normalizedNewName, ignoreCase = true) }
        )
    val canAddSubCategory = normalizedNewName.isNotEmpty() && !duplicateName

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newSubCategoryName = "" },
            title = { Text("New Subcategory", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newSubCategoryName,
                        onValueChange = { newSubCategoryName = it },
                        label = { Text("Subcategory name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppPrimary,
                            unfocusedBorderColor = AppPrimary.copy(alpha = 0.4f),
                            focusedLabelColor = AppPrimary,
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            focusedContainerColor = AppSurface,
                            unfocusedContainerColor = AppSurface,
                        )
                    )
                    if (duplicateName) {
                        Text(
                            "A subcategory with this name already exists",
                            color = AppRed,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (newSubCategoryName.isNotEmpty() && normalizedNewName.isEmpty()) {
                        Text(
                            "Subcategory name is required",
                            color = AppRed,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canAddSubCategory,
                    onClick = {
                        if (!canAddSubCategory) return@TextButton
                        onAddSubCategory(normalizedNewName)
                        newSubCategoryName = ""
                        showAddDialog = false
                    }
                ) { Text("Add", color = AppPrimary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newSubCategoryName = "" }) {
                    Text("Cancel", color = AppTextSecondary)
                }
            },
            containerColor = AppSurface
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = category?.name ?: "Subcategories",
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = AppPrimary,
                contentColor = AppTextPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add subcategory")
            }
        },
        containerColor = AppBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (category != null) {
                items(category.subCategories) { subCategory ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSubCategoryClick(subCategory.name) },
                        shape = RoundedCornerShape(16.dp),
                        color = AppSurface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(category.color.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = subCategory.icon,
                                    contentDescription = subCategory.name,
                                    tint = category.color,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Text(
                                text = subCategory.name,
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = AppTextPrimary
                            )

                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = AppPrimary.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Custom subcategories
            if (customForThis.isNotEmpty()) {
                item {
                    Text(
                        text = "Custom",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                items(customForThis, key = { it.id }) { custom ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSubCategoryClick(custom.name) },
                        shape = RoundedCornerShape(16.dp),
                        color = AppSurface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background((category?.color ?: AppPrimary).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Label,
                                    contentDescription = custom.name,
                                    tint = category?.color ?: AppPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Text(
                                text = custom.name,
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = AppTextPrimary
                            )

                            IconButton(onClick = { onDeleteSubCategory(custom.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = AppRed.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
