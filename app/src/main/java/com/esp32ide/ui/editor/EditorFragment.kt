package com.esp32ide.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.MainActivity
import com.esp32ide.R
import com.esp32ide.compiler.ArduinoCompiler
import com.esp32ide.data.AppPreferences
import com.esp32ide.data.Sketch
import com.esp32ide.data.SketchDatabase
import com.esp32ide.databinding.FragmentEditorBinding
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorFragment : Fragment() {

    private var _binding: FragmentEditorBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    private val dao by lazy { SketchDatabase.getInstance(requireContext()).sketchDao() }
    private val compiler by lazy { ArduinoCompiler(requireContext()) }
    private var currentSketchId: Int = -1
    private var currentSketchName: String = "sketch"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupEditor()
        setupToolbar()
        setupSymbolBar()
        loadLastSketch()
    }

    private fun setupEditor() {
        binding.editor.apply {
            // Font size from prefs
            setTextSize(prefs.fontSize.toFloat())

            // Color scheme
            colorScheme = if (prefs.darkTheme) SchemeDarcula() else SchemeGitHub()

            // Word wrap
            isWordwrap = prefs.wordWrap

            // Basic C++ language (avoids crash if TextMate assets are missing)
            try {
                // Try to use TextMate if registry is ready
                setEditorLanguage(TextMateLanguage.create("source.cpp", true))
            } catch (e: Exception) {
                // Fallback to plain text if grammar is missing to prevent crash
                android.util.Log.e("EditorFragment", "TextMate language failed: ${e.message}. Using default.")
                setEditorLanguage(null)
            }
        }
    }

    private fun setupToolbar() {
        binding.btnCompile.setOnClickListener { compile() }
        binding.btnFlash.setOnClickListener {
            saveCurrentSketch()
            (activity as MainActivity).navigateTo(R.id.nav_flash)
        }
        binding.btnMonitor.setOnClickListener {
            saveCurrentSketch()
            (activity as MainActivity).navigateTo(R.id.nav_monitor)
        }
        binding.btnNewFile.setOnClickListener { showNewFileDialog() }
        binding.btnUndo.setOnClickListener { binding.editor.undo() }
        binding.btnRedo.setOnClickListener { binding.editor.redo() }
        binding.btnSave.setOnClickListener {
            saveCurrentSketch()
            Toast.makeText(context, "Saved ✓", Toast.LENGTH_SHORT).show()
        }

        // Board name in toolbar
        binding.tvBoardName.text = prefs.selectedBoard
    }

    private fun setupSymbolBar() {
        val symbols = listOf("{","}"," (",")",";","<",">","\"","'","/","+","-","*","=","!","&","|",".","#","[","]","->","::")
        binding.symbolBar.adapter = SymbolAdapter(symbols) { sym ->
            binding.editor.insertText(sym, sym.length)
        }
    }

    private fun loadLastSketch() {
        lifecycleScope.launch {
            val count = dao.getCount()
            if (count == 0) {
                // Insert default sketch
                val id = dao.insertSketch(
                    Sketch(
                        name = "sketch_main",
                        content = DEFAULT_SKETCH
                    )
                )
                currentSketchId = id.toInt()
                currentSketchName = "sketch_main"
                _binding?.editor?.setText(DEFAULT_SKETCH)
            } else {
                // Get only the first emit from the flow
                val sketches = dao.getAllSketches().first()
                if (sketches.isNotEmpty() && currentSketchId == -1) {
                    val s = sketches.first()
                    currentSketchId = s.id
                    currentSketchName = s.name
                    withContext(Dispatchers.Main) {
                        _binding?.let { b ->
                            b.editor.setText(s.content)
                            b.tvFileName.text = s.name
                        }
                    }
                }
            }
        }
    }

    fun loadSketch(sketch: Sketch) {
        currentSketchId = sketch.id
        currentSketchName = sketch.name
        binding.editor.setText(sketch.content)
        binding.tvFileName.text = sketch.name
    }

    fun getEditorText(): String {
        return if (_binding != null) {
            binding.editor.text.toString()
        } else {
            ""
        }
    }

    private fun saveCurrentSketch() {
        if (currentSketchId == -1) return
        val content = binding.editor.text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            dao.updateContent(currentSketchId, content)
        }
    }

    private fun compile() {
        val activity = activity as MainActivity
        if (activity.isCompiling) {
            Toast.makeText(context, "Already compiling...", Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentSketch()

        val code = binding.editor.text.toString()
        val fqbn = prefs.boardFQBN

        binding.btnCompile.isEnabled = false
        binding.compileProgress.visibility = View.VISIBLE
        binding.tvCompileStatus.visibility = View.VISIBLE
        binding.tvCompileStatus.text = "Compiling..."
        activity.isCompiling = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                compiler.compile(code, fqbn) { line ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        _binding?.let { b ->
                            b.tvCompileStatus.text = line.take(80)
                        }
                    }
                }
            }

            activity.isCompiling = false
            _binding?.let { b ->
                b.btnCompile.isEnabled = true
                b.compileProgress.visibility = View.GONE

                if (result.success) {
                    activity.lastCompiledBinPath = result.binPath
                    activity.lastCompiledBinSize = result.binSize
                    val kb = result.binSize / 1024
                    b.tvCompileStatus.text = "✓ Compiled! ${kb} KB — tap FLASH to upload"
                    b.tvCompileStatus.setTextColor(resources.getColor(R.color.green, null))
                    Toast.makeText(context, "Compiled! ${kb} KB", Toast.LENGTH_SHORT).show()
                } else {
                    b.tvCompileStatus.text = "✗ ${result.error.lines().firstOrNull() ?: "Compile failed"}"
                    b.tvCompileStatus.setTextColor(resources.getColor(R.color.red, null))
                    // Show error dialog
                    showCompileError(result.error)
                }
            }
        }
    }

    private fun showNewFileDialog() {
        val dialog = NewFileDialog { name ->
            lifecycleScope.launch {
                val id = dao.insertSketch(
                    Sketch(name = name, content = "void setup() {\n  Serial.begin(115200);\n}\n\nvoid loop() {\n  \n}\n")
                )
                currentSketchId = id.toInt()
                currentSketchName = name
                withContext(Dispatchers.Main) {
                    binding.editor.setText("void setup() {\n  Serial.begin(115200);\n}\n\nvoid loop() {\n  \n}\n")
                    binding.tvFileName.text = name
                    Toast.makeText(context, "Created: $name", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show(parentFragmentManager, "new_file")
    }

    private fun showCompileError(error: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Compile Error")
            .setMessage(error)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentSketch()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val DEFAULT_SKETCH = """// ESP32 Blink
#define LED_BUILTIN 2

void setup() {
  Serial.begin(115200);
  pinMode(LED_BUILTIN, OUTPUT);
  Serial.println("ESP32 IDE Ready!");
}

void loop() {
  digitalWrite(LED_BUILTIN, HIGH);
  delay(500);
  digitalWrite(LED_BUILTIN, LOW);
  delay(500);
  Serial.println("tick");
}"""
    }
}
