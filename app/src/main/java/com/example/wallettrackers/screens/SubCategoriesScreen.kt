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

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newSubCategoryName = "" },
            title = { Text("New Subcategory", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newSubCategoryName,
                    onValueChange = { newSubCategoryName = it },
                    label = { Text("Subcategory name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DGIndigo,
                        unfocusedBorderColor = DGIndigo.copy(alpha = 0.4f),
                        focusedLabelColor = DGIndigo,
                        focusedTextColor = DGTextPrimary,
                        unfocusedTextColor = DGTextPrimary,
                        focusedContainerColor = DGSurface,
                        unfocusedContainerColor = DGSurface,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newSubCategoryName.isNotBlank()) {
                            onAddSubCategory(newSubCategoryName.trim())
                            newSubCategoryName = ""
                            showAddDialog = false
                        }
                    }
                ) { Text("Add", color = DGIndigo, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newSubCategoryName = "" }) {
                    Text("Cancel", color = DGTextSecondary)
                }
            },
            containerColor = DGSurface
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = category?.name ?: "Subcategories",
                        fontWeight = FontWeight.Bold,
                        color = DGTextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DGTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DGBackground)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = DGIndigo,
                contentColor = DGTextPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add subcategory")
            }
        },
        containerColor = DGBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
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
                        color = DGSurface,
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
                                color = DGTextPrimary
                            )

                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = DGIndigo.copy(alpha = 0.5f),
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
                        color = DGTextSecondary,
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
                        color = DGSurface,
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
                                    .background((category?.color ?: DGIndigo).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Label,
                                    contentDescription = custom.name,
                                    tint = category?.color ?: DGIndigo,
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
                                color = DGTextPrimary
                            )

                            IconButton(onClick = { onDeleteSubCategory(custom.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
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
