package com.esp32ide.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.MainActivity
import com.esp32ide.R
import com.esp32ide.compiler.ArduinoCompiler
import com.esp32ide.data.AppPreferences
import com.esp32ide.data.Project
import com.esp32ide.data.ProjectFile
import com.esp32ide.data.SketchDatabase
import com.esp32ide.databinding.FragmentEditorBinding
import com.esp32ide.ui.settings.BoardConfigDialog
import com.esp32ide.utils.ThemeManager
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

class EditorFragment : Fragment() {

    private var _binding: FragmentEditorBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    private val dao by lazy { SketchDatabase.getInstance(requireContext()).projectDao() }
    
    private var currentProject: Project? = null
    private var projectFiles = mutableListOf<ProjectFile>()
    private var activeFileIndex = -1

    val currentProjectIdPublic: Int get() = currentProject?.id ?: -1
    val currentProjectNamePublic: String? get() = currentProject?.name

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupEditor()
        setupToolbar()
        setupActionButtons()
        setupSymbolBar()
        loadCurrentProject()
    }

    private fun setupEditor() {
        binding.editor.apply {
            setTextSize(prefs.fontSize.toFloat())
            isWordwrap = prefs.wordWrap
            
            // ⚡ RESTORE: Revert to high-performance original engine and colors
            setEditorLanguage(ArduinoLanguage())
            
            // Apply the custom color scheme to ensure 1 2 3 colors are correct
            colorScheme = ArduinoColorScheme()
        }
    }

    private fun setupToolbar() {
        binding.btnUndo.setOnClickListener { binding.editor.undo() }
        binding.btnRedo.setOnClickListener { binding.editor.redo() }
        binding.btnSave.setOnClickListener {
            saveCurrentFile()
            Toast.makeText(context, "Saved ✓", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnNewFile.setOnClickListener { showNewFileInternalDialog() }
        binding.btnNavigator.setOnClickListener { showNavigator() }

        setupTouchToSwitch()
    }

    private fun setupTouchToSwitch() {
        binding.tvFileName.setOnClickListener {
            if (projectFiles.size > 1) {
                val fileNames = projectFiles.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("Switch File")
                    .setItems(fileNames) { _, which ->
                        switchToFile(which)
                    }
                    .show()
            } else {
                Toast.makeText(context, "Only one file in project", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupActionButtons() {
        binding.btnCompile.setOnClickListener { compile() }
        binding.btnFlash.setOnClickListener {
            // Trigger Board Select -> Config -> Flash flow
            (activity as? MainActivity)?.navigateTo(R.id.nav_boards)
        }
        binding.btnMonitor.setOnClickListener {
            (activity as? MainActivity)?.navigateTo(R.id.nav_monitor)
        }
    }

    private fun setupSymbolBar() {
        val symbols = listOf("{", "}", "(", ")", ";", ":", "<", ">", "\"", "'", "/", "+", "-", "*", "=", "!", "&", "|")
        binding.symbolBar.adapter = SymbolAdapter(symbols) { s ->
            // 🐛 BUG FIX: Use direct insert to prevent vanishing words
            binding.editor.insertText(s, 1)
        }
    }

    private fun loadCurrentProject() {
        lifecycleScope.launch {
            val projectId = prefs.lastSketchId // Reusing this for current projectId
            val project = dao.getProjectById(projectId) ?: return@launch
            currentProject = project
            binding.tvBoardName.text = project.boardName
            
            dao.getFilesForProject(projectId).collect { files ->
                projectFiles = files.toMutableList()
                if (projectFiles.isNotEmpty() && activeFileIndex == -1) {
                    val mainIdx = projectFiles.indexOfFirst { it.isMain }.coerceAtLeast(0)
                    switchToFile(mainIdx)
                }
            }
        }
    }

    private fun switchToFile(index: Int) {
        if (activeFileIndex != -1) {
            saveCurrentFile()
        }
        activeFileIndex = index
        val file = projectFiles[index]
        binding.tvFileName.text = file.name
        binding.editor.setText(file.content)
    }

    private fun saveCurrentFile() {
        if (activeFileIndex == -1) return
        val content = binding.editor.text.toString()
        val file = projectFiles[activeFileIndex]
        lifecycleScope.launch(Dispatchers.IO) {
            dao.updateFileContent(file.id, content)
        }
    }

    fun saveCurrentFilePublic() = saveCurrentFile()

    private fun showNewFileInternalDialog() {
        val input = EditText(requireContext()).apply { hint = "filename.h" }
        AlertDialog.Builder(requireContext())
            .setTitle("New File in Project")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    addNewFileToProject(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNewFileToProject(name: String) {
        val project = currentProject ?: return
        lifecycleScope.launch {
            val newFile = ProjectFile(
                projectId = project.id,
                name = name,
                content = "",
                isMain = false
            )
            dao.insertFile(newFile)
        }
    }

    private fun compile() {
        val activity = activity as MainActivity
        if (activity.isCompiling) return
        
        saveCurrentFile()
        
        val project = currentProject ?: return
        val compiler = ArduinoCompiler(requireContext())
        
        val fullContent = StringBuilder()
        projectFiles.forEach { file ->
            if (file.name.endsWith(".h")) {
                fullContent.append("// File: ${file.name}\n")
                fullContent.append(file.content).append("\n\n")
            }
        }
        val mainFile = projectFiles.find { it.isMain } ?: projectFiles[0]
        fullContent.append(mainFile.content)

        binding.btnCompile.isEnabled = false
        binding.compileProgress.visibility = View.VISIBLE
        binding.tvCompileStatus.visibility = View.VISIBLE
        binding.tvCompileStatus.text = "Compiling..."
        activity.isCompiling = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                compiler.compile(fullContent.toString(), project.boardFQBN) { line ->
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
                    b.tvCompileStatus.text = "✓ Compiled Successfully!"
                } else {
                    showCompileError(result.error)
                    b.tvCompileStatus.text = "✗ Compile Failed"
                }
            }
        }
    }

    private fun showNavigator() {
        val text = binding.editor.text.toString()
        val symbols = mutableListOf<Pair<String, Int>>()
        val regex = Regex("""(void|int|float|String|bool|uint\d+_t)\s+([a-zA-Z0-9_]+)\s*\(""")
        val lines = text.split("\n")
        lines.forEachIndexed { index, line ->
            val match = regex.find(line)
            if (match != null) symbols.add("${match.groupValues[1]} ${match.groupValues[2]}()" to index)
        }
        if (symbols.isEmpty()) {
            Toast.makeText(context, "No functions found", Toast.LENGTH_SHORT).show()
            return
        }
        val names = symbols.map { it.first }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Code Navigator")
            .setItems(names) { _, which ->
                binding.editor.jumpToLine(symbols[which].second)
            }
            .show()
    }

    private fun showCompileError(error: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Compile Error")
            .setMessage(error)
            .setPositiveButton("OK", null)
            .show()
    }

    fun getEditorText() = binding.editor.text.toString()

    fun undoPublic() = binding.editor.undo()

    fun redoPublic() = binding.editor.redo()

    fun showSearchPublic() {
        Toast.makeText(context, "Search coming soon", Toast.LENGTH_SHORT).show()
    }

    fun triggerCompletionPublic() {
        // Placeholder
    }

    override fun onPause() {
        super.onPause()
        saveCurrentFile()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
