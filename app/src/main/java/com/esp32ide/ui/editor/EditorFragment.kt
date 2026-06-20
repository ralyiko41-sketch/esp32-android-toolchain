package com.esp32ide.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.MainActivity
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
    private var currentSketchId = 0
    private var currentSketchName = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
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
            setTextSize(prefs.fontSize.toFloat())
            colorScheme = if (prefs.darkTheme) SchemeDarcula() else SchemeGitHub()
            isWordwrap = prefs.wordWrap

            try {
                // IMPORTANT: Syntax highlighting requires TextMateRegistry to be initialized in Application class.
                // We attempt to load the C++ language.
                setEditorLanguage(TextMateLanguage.create("source.cpp", true))
            } catch (e: Exception) {
                setEditorLanguage(null)
            }
        }
    }

    private fun setupToolbar() {
        binding.btnUndo.setOnClickListener { binding.editor.undo() }
        binding.btnRedo.setOnClickListener { binding.editor.redo() }
        binding.btnSave.setOnClickListener {
            saveCurrentSketch()
            Toast.makeText(context, "Saved ✓", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnNavigator.setOnClickListener { showNavigator() }

        binding.btnNewFile.setOnClickListener { showNewFileDialog() }

        binding.tvBoardName.text = prefs.selectedBoard
    }

    private fun setupSymbolBar() {
        val symbols = listOf("{", "}", "(", ")", ";", ":", "<", ">", "\"", "'", "/", "+", "-", "*", "=", "!", "&", "|")
        binding.symbolBar.adapter = SymbolAdapter(symbols) { s ->
            binding.editor.pasteText(s)
        }
    }

    private fun showNavigator() {
        val text = binding.editor.text.toString()
        val symbols = mutableListOf<Pair<String, Int>>() // Name to Line
        
        // Regex for common C++ functions
        val regex = Regex("""(void|int|float|String|bool|uint\d+_t)\s+([a-zA-Z0-9_]+)\s*\(""")
        
        val lines = text.split("\n")
        lines.forEachIndexed { index, line ->
            val match = regex.find(line)
            if (match != null) {
                symbols.add("${match.groupValues[1]} ${match.groupValues[2]}()" to index)
            }
        }
        
        if (symbols.isEmpty()) {
            Toast.makeText(context, "No functions found in this file", Toast.LENGTH_SHORT).show()
            return
        }
        
        val names = symbols.map { it.first }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Code Navigator")
            .setItems(names) { _, which ->
                val line = symbols[which].second
                binding.editor.jumpToLine(line)
            }
            .show()
    }

    private fun loadLastSketch() {
        lifecycleScope.launch {
            val sketches = dao.getAllSketches().first()
            if (sketches.isEmpty()) {
                val default = Sketch(name = "sketch_main", content = DEFAULT_SKETCH)
                val id = dao.insertSketch(default).toInt()
                loadSketch(default.copy(id = id))
            } else {
                val lastId = prefs.lastSketchId
                val last = sketches.find { it.id == lastId } ?: sketches[0]
                loadSketch(last)
            }
        }
    }

    private fun loadSketch(sketch: Sketch) {
        currentSketchId = sketch.id
        currentSketchName = sketch.name
        binding.tvFileName.text = sketch.name
        binding.editor.setText(sketch.content)
        prefs.lastSketchId = sketch.id
    }

    private fun getEditorText() = binding.editor.text.toString()

    private fun saveCurrentSketch() {
        val content = getEditorText()
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
                        _binding?.let { b -> b.tvCompileStatus.text = line.take(80) }
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
                    b.tvCompileStatus.text = "✓ Compiled successfully!"
                } else {
                    showCompileError(result.error)
                    b.tvCompileStatus.text = "✗ Compile failed"
                }
            }
        }
    }

    private fun showNewFileDialog() {
        NewFileDialog { name ->
            lifecycleScope.launch {
                val newSketch = Sketch(name = name, content = DEFAULT_SKETCH)
                val id = dao.insertSketch(newSketch).toInt()
                loadSketch(newSketch.copy(id = id))
            }
        }.show(parentFragmentManager, "new_file")
    }

    private fun showCompileError(error: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Compile Error")
            .setMessage(error)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentSketch()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

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
  delay(1000);
  digitalWrite(LED_BUILTIN, LOW);
  delay(1000);
}"""
    }
}
