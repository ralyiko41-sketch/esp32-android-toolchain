package com.esp32ide.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

sealed class MenuLevel {
    data object Root : MenuLevel()
    data object Sketch : MenuLevel()
    data object OpenFrom : MenuLevel()
    data object SaveAsTo : MenuLevel()
    data object File : MenuLevel()
    data object Edit : MenuLevel()
    data object Actions : MenuLevel()
    data object LibraryExamples : MenuLevel()
}

@Composable
fun IDEMenuBar(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf<MenuLevel>(MenuLevel.Root) }

    Box(modifier = modifier) {
        IconButton(onClick = { 
            level = MenuLevel.Root
            expanded = true 
        }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = Color.White
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            when (level) {
                MenuLevel.Root -> RootMenu(onNavigate = { level = it })
                MenuLevel.Sketch -> SketchMenu(onNavigate = { level = it }, onAction = { expanded = false })
                MenuLevel.OpenFrom -> OpenFromMenu(onNavigate = { level = it }, onAction = { expanded = false })
                MenuLevel.File -> FileMenu(onNavigate = { level = it }, onAction = { expanded = false })
                MenuLevel.Edit -> EditMenu(onNavigate = { level = it }, onAction = { expanded = false })
                MenuLevel.Actions -> ActionsMenu(onNavigate = { level = it }, onAction = { expanded = false })
                MenuLevel.LibraryExamples -> LibraryExamplesMenu(onNavigate = { level = it }, onAction = { expanded = false })
                else -> {}
            }
        }
    }
}

@Composable
fun RootMenu(onNavigate: (MenuLevel) -> Unit) {
    DropdownMenuItem(
        text = { Text("Sketch") },
        onClick = { onNavigate(MenuLevel.Sketch) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
    )
    DropdownMenuItem(
        text = { Text("File") },
        onClick = { onNavigate(MenuLevel.File) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
    )
    DropdownMenuItem(
        text = { Text("Edit") },
        onClick = { onNavigate(MenuLevel.Edit) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
    )
    DropdownMenuItem(
        text = { Text("Actions") },
        onClick = { onNavigate(MenuLevel.Actions) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
    )
}

@Composable
fun SketchMenu(onNavigate: (MenuLevel) -> Unit, onAction: () -> Unit) {
    BackMenuItem { onNavigate(MenuLevel.Root) }
    DropdownMenuItem(text = { Text("New") }, onClick = onAction)
    DropdownMenuItem(
        text = { Text("Open from >") },
        onClick = { onNavigate(MenuLevel.OpenFrom) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
    )
    DropdownMenuItem(text = { Text("Save") }, onClick = onAction)
    DropdownMenuItem(
        text = { Text("Save as to >") },
        onClick = { onNavigate(MenuLevel.SaveAsTo) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
    )
    DropdownMenuItem(text = { Text("Save precompiled") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Close") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Delete") }, onClick = onAction)
    DropdownMenuItem(
        text = { Text("Libraries examples >") },
        onClick = { onNavigate(MenuLevel.LibraryExamples) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
    )
}

@Composable
fun OpenFromMenu(onNavigate: (MenuLevel) -> Unit, onAction: () -> Unit) {
    BackMenuItem { onNavigate(MenuLevel.Sketch) }
    DropdownMenuItem(text = { Text("Recent") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Device") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Dropbox") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Google Drive") }, onClick = onAction)
}

@Composable
fun FileMenu(onNavigate: (MenuLevel) -> Unit, onAction: () -> Unit) {
    BackMenuItem { onNavigate(MenuLevel.Root) }
    DropdownMenuItem(text = { Text("New") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Rename") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Remove") }, onClick = onAction)
}

@Composable
fun EditMenu(onNavigate: (MenuLevel) -> Unit, onAction: () -> Unit) {
    BackMenuItem { onNavigate(MenuLevel.Root) }
    DropdownMenuItem(text = { Text("Undo") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Redo") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Search") }, onClick = onAction)
}

@Composable
fun ActionsMenu(onNavigate: (MenuLevel) -> Unit, onAction: () -> Unit) {
    BackMenuItem { onNavigate(MenuLevel.Root) }
    DropdownMenuItem(text = { Text("Code complete") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Upload over WiFi") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Clear compile cache") }, onClick = onAction)
    DropdownMenuItem(text = { Text("Upload precompiled") }, onClick = onAction)
}

@Composable
fun LibraryExamplesMenu(onNavigate: (MenuLevel) -> Unit, onAction: () -> Unit) {
    BackMenuItem { onNavigate(MenuLevel.Sketch) }
    DropdownMenuItem(text = { Text("No examples found") }, onClick = onAction, enabled = false)
}

@Composable
fun BackMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Back", style = MaterialTheme.typography.labelMedium) },
        onClick = onClick,
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowLeft, null) }
    )
    HorizontalDivider()
}
