package com.esp32ide.ui.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.esp32ide.R
import com.esp32ide.data.AppPreferences
import com.esp32ide.data.Project
import com.esp32ide.data.ProjectFile
import com.esp32ide.data.SketchDatabase
import com.esp32ide.serial.SerialState
import com.esp32ide.ui.editor.EditorFragment
import com.esp32ide.ui.flash.FlashFragment
import com.esp32ide.ui.ota.OTAFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Menu level ────────────────────────────────────────────────────────────────

sealed class MenuLevel {
    data object Root            : MenuLevel()
    data object Sketch          : MenuLevel()
    data object OpenFrom        : MenuLevel()
    data object File            : MenuLevel()
    data object Edit            : MenuLevel()
    data object Actions         : MenuLevel()
    data object LibraryExamples : MenuLevel()
}

// ── Entry point ───────────────────────────────────────────────────────────────

@Composable
fun IDEMenuBar(
    modifier        : Modifier = Modifier,
    showStatusOnly  : Boolean = false,
    serialStateFlow : StateFlow<SerialState>
) {
    val serialState by serialStateFlow.collectAsState()
    val context     = LocalContext.current

    Row(
        modifier        = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showStatusOnly) {
            // ── Serial connection indicator (replaces menu in monitor mode) ───
            SerialIndicator(serialState)
        } else {
            // ── 3-dot menu (shown in editor/normal mode) ─────────────────────
            IDEMenu(context)
        }
    }
}

// ── Serial status dot ─────────────────────────────────────────────────────────

@Composable
fun SerialIndicator(state: SerialState) {
    val color = when (state) {
        is SerialState.Connected    -> Color.Green
        is SerialState.Connecting   -> Color.Yellow
        else                        -> Color.Red
    }
    Box(
        Modifier
            .padding(end = 20.dp)
            .size(20.dp)
            .background(color, CircleShape)
    )
}

// ── Dropdown menu ─────────────────────────────────────────────────────────────

@Composable
fun IDEMenu(context: Context) {
    var expanded by remember { mutableStateOf(false) }
    var level    by remember { mutableStateOf<MenuLevel>(MenuLevel.Root) }

    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { loadFileFromUri(context, it) }
        }
    }

    val driveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { loadFileFromUri(context, it) }
        }
    }

    Box {
        IconButton(onClick = { level = MenuLevel.Root; expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
        }

        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false }
        ) {
            val dismiss = { expanded = false }

            when (level) {
                MenuLevel.Root -> RootMenu { level = it }

                MenuLevel.Sketch -> SketchMenu(
                    onNavigate = { level = it },
                    onDismiss  = dismiss,
                    context    = context
                )

                MenuLevel.OpenFrom -> OpenFromMenu(
                    onNavigate        = { level = it },
                    onDismiss         = dismiss,
                    context           = context,
                    openFileLauncher  = { openFileLauncher.launch(filePickerIntent()) },
                    openDriveLauncher = { driveFileLauncher.launch(drivePickerIntent()) }
                )

                MenuLevel.File -> FileMenu(
                    onNavigate = { level = it },
                    onDismiss  = dismiss,
                    context    = context
                )

                MenuLevel.Edit -> EditMenu(
                    onNavigate = { level = it },
                    onDismiss  = dismiss,
                    context    = context
                )

                MenuLevel.Actions -> ActionsMenu(
                    onNavigate = { level = it },
                    onDismiss  = dismiss,
                    context    = context
                )

                MenuLevel.LibraryExamples -> LibraryExamplesMenu(
                    onNavigate = { level = it },
                    onDismiss  = dismiss,
                    context    = context
                )
            }
        }
    }
}

// ── Root ──────────────────────────────────────────────────────────────────────

@Composable
fun RootMenu(onNavigate: (MenuLevel) -> Unit) {
    listOf(
        "Sketch"  to MenuLevel.Sketch,
        "File"    to MenuLevel.File,
        "Edit"    to MenuLevel.Edit,
        "Actions" to MenuLevel.Actions
    ).forEach { (label, dest) ->
        DropdownMenuItem(
            text         = { Text(label) },
            onClick      = { onNavigate(dest) },
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
        )
    }
}

// ── Sketch ────────────────────────────────────────────────────────────────────

@Composable
fun SketchMenu(
    onNavigate : (MenuLevel) -> Unit,
    onDismiss  : () -> Unit,
    context    : Context
) {
    val activity = context as? FragmentActivity
    val dao      = remember { SketchDatabase.getInstance(context).projectDao() }
    val prefs    = remember { AppPreferences(context) }

    BackMenuItem { onNavigate(MenuLevel.Root) }

    // NEW PROJECT
    DropdownMenuItem(text = { Text("New Project") }, onClick = {
        onDismiss()
        activity?.lifecycleScope?.launch {
            val sdf = SimpleDateFormat("dd/M/yy_HH:mm", Locale.getDefault())
            val name = "project_${sdf.format(Date())}"
            val projectId = dao.insertProject(Project(name = name)).toInt()
            val mainFile = ProjectFile(
                projectId = projectId,
                name = "$name.ino",
                content = DEFAULT_SKETCH,
                isMain = true
            )
            dao.insertFile(mainFile)
            prefs.lastSketchId = projectId
            withContext(Dispatchers.Main) {
                activity.navigateToEditor()
            }
        }
    })

    // OPEN FROM >
    DropdownMenuItem(
        text         = { Text("Open from") },
        onClick      = { onNavigate(MenuLevel.OpenFrom) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
    )

    // SAVE
    DropdownMenuItem(text = { Text("Save") }, onClick = {
        onDismiss()
        context.editorFragment()?.saveCurrentFilePublic()
            ?: toast(context, "Open the editor first")
    })

    // SAVE AS
    DropdownMenuItem(text = { Text("Save as…") }, onClick = {
        onDismiss()
        val editor = context.editorFragment()
            ?: return@DropdownMenuItem toast(context, "Open the editor first")
        inputDialog(context, "Save As", "Name", editor.currentProjectNamePublic ?: "") { name ->
            activity?.lifecycleScope?.launch {
                val newProjectId = dao.insertProject(Project(name = name)).toInt()
                // Copy all files from current project
                val currentFiles = dao.getFilesForProject(editor.currentProjectIdPublic).first()
                currentFiles.forEach { file ->
                    dao.insertFile(file.copy(id = 0, projectId = newProjectId))
                }
                prefs.lastSketchId = newProjectId
                withContext(Dispatchers.Main) {
                    activity.navigateToEditor()
                    toast(context, "Saved as \"$name\"")
                }
            }
        }
    })

    // SAVE PRECOMPILED
    DropdownMenuItem(text = { Text("Save precompiled") }, onClick = {
        onDismiss()
        val binPath = prefs.lastBinPath
        if (binPath.isEmpty()) {
            toast(context, "Compile first to generate a .bin")
        } else {
            toast(context, "Last .bin: $binPath (${prefs.lastBinSize} bytes)")
        }
    })

    // CLOSE — load first other project without deleting
    DropdownMenuItem(text = { Text("Close") }, onClick = {
        onDismiss()
        val currentId = context.editorFragment()?.currentProjectIdPublic ?: -1
        activity?.lifecycleScope?.launch {
            val others = dao.getAllProjects().first().filter { it.id != currentId }
            withContext(Dispatchers.Main) {
                if (others.isNotEmpty()) {
                    prefs.lastSketchId = others[0].id
                    activity.navigateToEditor()
                } else {
                    toast(context, "No other project to switch to")
                }
            }
        }
    })

    // DELETE
    DropdownMenuItem(
        text    = { Text("Delete", color = MaterialTheme.colorScheme.error) },
        onClick = {
            onDismiss()
            val editor = context.editorFragment()
                ?: return@DropdownMenuItem toast(context, "Open the editor first")
            confirmDialog(
                context,
                "Delete sketch",
                "Delete \"${editor.currentProjectNamePublic}\"? This cannot be undone."
            ) {
                activity?.lifecycleScope?.launch {
                    val id = editor.currentProjectIdPublic
                    dao.getProjectById(id)?.let { dao.deleteProject(it) }
                    val remaining = dao.getAllProjects().first()
                    if (remaining.isNotEmpty()) prefs.lastSketchId = remaining[0].id
                    withContext(Dispatchers.Main) {
                        activity.navigateToEditor()
                        toast(context, "Deleted")
                    }
                }
            }
        }
    )

    // LIBRARY EXAMPLES >
    DropdownMenuItem(
        text         = { Text("Library examples") },
        onClick      = { onNavigate(MenuLevel.LibraryExamples) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) }
    )
}

// ── Open From ─────────────────────────────────────────────────────────────────

@Composable
fun OpenFromMenu(
    onNavigate        : (MenuLevel) -> Unit,
    onDismiss         : () -> Unit,
    context           : Context,
    openFileLauncher  : () -> Unit,
    openDriveLauncher : () -> Unit
) {
    val activity = context as? FragmentActivity
    val dao      = remember { SketchDatabase.getInstance(context).projectDao() }
    val prefs    = remember { AppPreferences(context) }

    BackMenuItem { onNavigate(MenuLevel.Sketch) }

    // RECENT PROJECTS
    DropdownMenuItem(text = { Text("Recent Projects") }, onClick = {
        onDismiss()
        activity?.lifecycleScope?.launch {
            val projects = dao.getAllProjects().first()
            withContext(Dispatchers.Main) {
                if (projects.isEmpty()) { toast(context, "No recent projects"); return@withContext }
                android.app.AlertDialog.Builder(context)
                    .setTitle("Recent projects")
                    .setItems(projects.map { it.name }.toTypedArray()) { _, i ->
                        val chosen = projects[i]
                        prefs.lastSketchId = chosen.id
                        activity.lifecycleScope.launch(Dispatchers.Main) {
                             activity.navigateToEditor()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    })

    // DEVICE — system file picker
    DropdownMenuItem(text = { Text("Device") }, onClick = {
        onDismiss()
        openFileLauncher()
    })

    // GOOGLE DRIVE — system picker (Drive app surfaces automatically)
    DropdownMenuItem(text = { Text("Google Drive") }, onClick = {
        onDismiss()
        openDriveLauncher()
    })
}

// ── File ──────────────────────────────────────────────────────────────────────

@Composable
fun FileMenu(
    onNavigate : (MenuLevel) -> Unit,
    onDismiss  : () -> Unit,
    context    : Context
) {
    val activity = context as? FragmentActivity
    val dao      = remember { SketchDatabase.getInstance(context).projectDao() }
    val prefs    = remember { AppPreferences(context) }

    BackMenuItem { onNavigate(MenuLevel.Root) }

    // NEW PROJECT
    DropdownMenuItem(text = { Text("New Project") }, onClick = {
        onDismiss()
        activity?.lifecycleScope?.launch {
            val sdf = SimpleDateFormat("dd/M/yy_HH:mm", Locale.getDefault())
            val name = "project_${sdf.format(Date())}"
            val projectId = dao.insertProject(Project(name = name)).toInt()
            val mainFile = ProjectFile(
                projectId = projectId,
                name = "$name.ino",
                content = DEFAULT_SKETCH,
                isMain = true
            )
            dao.insertFile(mainFile)
            prefs.lastSketchId = projectId
            withContext(Dispatchers.Main) {
                activity.navigateToEditor()
                toast(context, "New project created")
            }
        }
    })

    // RENAME
    DropdownMenuItem(text = { Text("Rename") }, onClick = {
        onDismiss()
        val editor = context.editorFragment()
            ?: return@DropdownMenuItem toast(context, "Open the editor first")
        inputDialog(context, "Rename", "New name", editor.currentProjectNamePublic ?: "") { name ->
            activity?.lifecycleScope?.launch {
                val project = dao.getProjectById(editor.currentProjectIdPublic)
                if (project != null) {
                    dao.updateProject(project.copy(name = name))
                    withContext(Dispatchers.Main) {
                        activity.navigateToEditor()
                        toast(context, "Renamed")
                    }
                }
            }
        }
    })

    // REMOVE
    DropdownMenuItem(
        text    = { Text("Remove", color = MaterialTheme.colorScheme.error) },
        onClick = {
            onDismiss()
            val editor = context.editorFragment()
                ?: return@DropdownMenuItem toast(context, "Open the editor first")
            confirmDialog(context, "Remove", "Remove \"${editor.currentProjectNamePublic}\"?") {
                activity?.lifecycleScope?.launch {
                    dao.getProjectById(editor.currentProjectIdPublic)?.let { dao.deleteProject(it) }
                    val remaining = dao.getAllProjects().first()
                    if (remaining.isNotEmpty()) prefs.lastSketchId = remaining[0].id
                    withContext(Dispatchers.Main) {
                        activity.navigateToEditor()
                        toast(context, "Removed")
                    }
                }
            }
        }
    )
}

// ── Edit ──────────────────────────────────────────────────────────────────────

@Composable
fun EditMenu(
    onNavigate : (MenuLevel) -> Unit,
    onDismiss  : () -> Unit,
    context    : Context
) {
    BackMenuItem { onNavigate(MenuLevel.Root) }

    DropdownMenuItem(text = { Text("Undo") }, onClick = {
        onDismiss()
        context.editorFragment()?.undoPublic() ?: toast(context, "Open the editor first")
    })
    DropdownMenuItem(text = { Text("Redo") }, onClick = {
        onDismiss()
        context.editorFragment()?.redoPublic() ?: toast(context, "Open the editor first")
    })
    DropdownMenuItem(text = { Text("Search") }, onClick = {
        onDismiss()
        context.editorFragment()?.showSearchPublic() ?: toast(context, "Open the editor first")
    })
}

// ── Actions ───────────────────────────────────────────────────────────────────

@Composable
fun ActionsMenu(
    onNavigate : (MenuLevel) -> Unit,
    onDismiss  : () -> Unit,
    context    : Context
) {
    val activity = context as? FragmentActivity

    BackMenuItem { onNavigate(MenuLevel.Root) }

    // CODE COMPLETE
    DropdownMenuItem(text = { Text("Code complete") }, onClick = {
        onDismiss()
        context.editorFragment()?.triggerCompletionPublic()
            ?: toast(context, "Open the editor first")
    })

    // UPLOAD OVER WIFI (OTA)
    DropdownMenuItem(text = { Text("Upload over WiFi (OTA)") }, onClick = {
        onDismiss()
        activity?.supportFragmentManager?.beginTransaction()
            ?.replace(R.id.fragment_container, OTAFragment(), null)
            ?.commitAllowingStateLoss()
    })

    // CLEAR COMPILE CACHE — left for you to wire to ArduinoCompiler
    DropdownMenuItem(text = { Text("Clear compile cache") }, onClick = {
        onDismiss()
        toast(context, "Wire your compiler cache clear here")
    })

    // UPLOAD PRECOMPILED
    DropdownMenuItem(text = { Text("Upload precompiled") }, onClick = {
        onDismiss()
        activity?.supportFragmentManager?.beginTransaction()
            ?.replace(R.id.fragment_container, FlashFragment(), null)
            ?.commitAllowingStateLoss()
    })
}

// ── Library Examples ──────────────────────────────────────────────────────────

@Composable
fun LibraryExamplesMenu(
    onNavigate : (MenuLevel) -> Unit,
    onDismiss  : () -> Unit,
    context    : Context
) {
    val activity = context as? FragmentActivity
    val dao      = remember { SketchDatabase.getInstance(context).projectDao() }
    val prefs    = remember { AppPreferences(context) }

    BackMenuItem { onNavigate(MenuLevel.Sketch) }

    EXAMPLES.forEach { (name, code) ->
        DropdownMenuItem(text = { Text(name) }, onClick = {
            onDismiss()
            activity?.lifecycleScope?.launch {
                val projectId = dao.insertProject(Project(name = name)).toInt()
                val mainFile = ProjectFile(
                    projectId = projectId,
                    name = "$name.ino",
                    content = code,
                    isMain = true
                )
                dao.insertFile(mainFile)
                prefs.lastSketchId = projectId
                withContext(Dispatchers.Main) {
                    activity.navigateToEditor()
                    toast(context, "Loaded: $name")
                }
            }
        })
    }
}

// ── Back button ───────────────────────────────────────────────────────────────

@Composable
fun BackMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text        = { Text("Back", style = MaterialTheme.typography.labelMedium) },
        onClick     = onClick,
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowLeft, null) }
    )
    HorizontalDivider()
}

// ── Private helpers ───────────────────────────────────────────────────────────

private fun Context.editorFragment(): EditorFragment? =
    (this as? FragmentActivity)
        ?.supportFragmentManager
        ?.findFragmentByTag("editor") as? EditorFragment

private fun FragmentActivity.navigateToEditor() {
    supportFragmentManager.beginTransaction()
        .replace(R.id.fragment_container, EditorFragment(), "editor")
        .commitAllowingStateLoss()
}

private fun toast(context: Context, msg: String) =
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

private fun inputDialog(
    context   : Context,
    title     : String,
    hint      : String,
    prefill   : String,
    onConfirm : (String) -> Unit
) {
    val input = android.widget.EditText(context).apply {
        setText(prefill); setHint(hint); setSingleLine()
        setPadding(48, 24, 48, 24)
    }
    android.app.AlertDialog.Builder(context)
        .setTitle(title).setView(input)
        .setPositiveButton("OK") { _, _ ->
            val v = input.text.toString().trim()
            if (v.isNotEmpty()) onConfirm(v) else toast(context, "Name cannot be empty")
        }
        .setNegativeButton("Cancel", null).show()
}

private fun confirmDialog(
    context   : Context,
    title     : String,
    message   : String,
    onConfirm : () -> Unit
) {
    android.app.AlertDialog.Builder(context)
        .setTitle(title).setMessage(message)
        .setPositiveButton("Yes") { _, _ -> onConfirm() }
        .setNegativeButton("Cancel", null).show()
}

private fun filePickerIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
        "text/x-arduino", "text/x-csrc", "text/x-c++src",
        "text/plain", "application/octet-stream"
    ))
}

private fun drivePickerIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "text/plain"
}

private fun loadFileFromUri(context: Context, uri: Uri) {
    val activity = context as? FragmentActivity ?: return
    val dao      = SketchDatabase.getInstance(context).projectDao()
    val prefs    = AppPreferences(context)
    activity.lifecycleScope.launch(Dispatchers.IO) {
        try {
            val content  = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText() ?: return@launch
            val name     = uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
            val projectId = dao.insertProject(Project(name = name)).toInt()
            val mainFile = ProjectFile(
                projectId = projectId,
                name = "$name.ino",
                content = content,
                isMain = true
            )
            dao.insertFile(mainFile)
            prefs.lastSketchId = projectId
            withContext(Dispatchers.Main) {
                activity.navigateToEditor()
                toast(context, "Loaded: $name")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { toast(context, "Failed: ${e.message}") }
        }
    }
}

// ── Default sketch ────────────────────────────────────────────────────────────

private const val DEFAULT_SKETCH = """// ESP32 Sketch
#define LED_BUILTIN 2

void setup() {
  Serial.begin(115200);
  pinMode(LED_BUILTIN, OUTPUT);
}

void loop() {
  digitalWrite(LED_BUILTIN, HIGH);
  delay(1000);
  digitalWrite(LED_BUILTIN, LOW);
  delay(1000);
}"""

// ── Built-in examples ─────────────────────────────────────────────────────────

private val EXAMPLES = listOf(
    "Blink" to """// Blink
#define LED_BUILTIN 2
void setup() { pinMode(LED_BUILTIN, OUTPUT); }
void loop() {
  digitalWrite(LED_BUILTIN, HIGH); delay(500);
  digitalWrite(LED_BUILTIN, LOW);  delay(500);
}""",

    "Serial Echo" to """// Serial Echo
void setup() { Serial.begin(115200); Serial.println("Echo ready"); }
void loop() {
  if (Serial.available()) {
    String line = Serial.readStringUntil('\n');
    Serial.println("Echo: " + line);
  }
}""",

    "WiFi Scan" to """// WiFi Scan
#include <WiFi.h>
void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA); WiFi.disconnect(); delay(100);
  int n = WiFi.scanNetworks();
  for (int i = 0; i < n; i++)
    Serial.printf("%d: %s (%d dBm)\n", i+1, WiFi.SSID(i).c_str(), WiFi.RSSI(i));
}
void loop() {}""",

    "Analog Read" to """// Analog Read — ADC voltage
#define ADC_PIN 34
void setup() { Serial.begin(115200); analogReadResolution(12); }
void loop() {
  int raw = analogRead(ADC_PIN);
  float v = raw * (3.3f / 4095.0f);
  Serial.printf("Raw: %d  Voltage: %.2f V\n", raw, v);
  delay(200);
}""",

    "PWM Fade" to """// PWM Fade
#define LED_PIN 2
void setup() {
  ledcSetup(0, 5000, 8);
  ledcAttachPin(LED_PIN, 0);
}
void loop() {
  for (int d = 0; d < 256; d++) { ledcWrite(0, d); delay(8); }
  for (int d = 255; d >= 0; d--) { ledcWrite(0, d); delay(8); }
}""",

    "Deep Sleep" to """// Deep Sleep — wakes every 10 seconds
#define uS_TO_S_FACTOR 1000000ULL
void setup() {
  Serial.begin(115200);
  Serial.println("Awake! Going to sleep in 2s...");
  delay(2000);
  esp_sleep_enable_timer_wakeup(10 * uS_TO_S_FACTOR);
  esp_deep_sleep_start();
}
void loop() {}""",

    "I2C Scanner" to """// I2C Scanner
#include <Wire.h>
void setup() {
  Wire.begin(); Serial.begin(115200);
  Serial.println("Scanning I2C bus...");
  for (byte addr = 1; addr < 127; addr++) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0)
      Serial.printf("Found device at 0x%02X\n", addr);
  }
  Serial.println("Done.");
}
void loop() {}"""
)