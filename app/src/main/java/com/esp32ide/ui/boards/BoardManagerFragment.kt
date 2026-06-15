package com.esp32ide.ui.boards

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.data.AppPreferences
import com.esp32ide.databinding.FragmentBoardsBinding
import com.esp32ide.utils.BoardEntry
import com.esp32ide.utils.BoardsParser
import kotlinx.coroutines.launch

class BoardManagerFragment : Fragment() {

    private var _binding: FragmentBoardsBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    private var allBoards = listOf<BoardEntry>()
    private lateinit var adapter: BoardAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentBoardsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        setupBoardList()
        loadBoards()
    }

    private fun setupTabs() {
        binding.tabSelect.setOnClickListener { showTab("select") }
        binding.tabImport.setOnClickListener { showTab("import") }
        showTab("select")
    }

    private fun showTab(tab: String) {
        binding.layoutSelect.visibility = if (tab == "select") View.VISIBLE else View.GONE
        binding.layoutImport.visibility = if (tab == "import") View.VISIBLE else View.GONE
        binding.tabSelect.isSelected = tab == "select"
        binding.tabImport.isSelected = tab == "import"
    }

    private fun setupBoardList() {
        adapter = BoardAdapter(
            onSelect = { board ->
                prefs.selectedBoard = board.name
                prefs.boardFQBN = board.fqbn
                binding.tvCurrentBoard.text = "● ${board.name}"
                binding.tvCurrentFqbn.text = board.fqbn
                Toast.makeText(context, "Selected: ${board.name}", Toast.LENGTH_SHORT).show()
            }
        )
        binding.rvBoards.adapter = adapter

        // Search
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().lowercase()
                val filtered = allBoards.filter { it.name.lowercase().contains(q) }
                adapter.submitList(filtered)
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        // Import buttons
        binding.btnImportFile.setOnClickListener { importBoardsFile() }
        binding.btnApplyPaste.setOnClickListener {
            val text = binding.etBoardsTxt.text.toString()
            if (text.isBlank()) {
                Toast.makeText(context, "Paste boards.txt content first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            applyBoardsTxt(text)
        }
        binding.btnClearImport.setOnClickListener {
            prefs.importedBoardsTxt = ""
            loadBoards()
            Toast.makeText(context, "Cleared — using built-in boards", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBoards() {
        val txt = prefs.importedBoardsTxt
        allBoards = if (txt.isNotEmpty()) {
            val parsed = BoardsParser.parse(txt)
            if (parsed.isNotEmpty()) parsed else BoardsParser.FALLBACK
        } else {
            BoardsParser.FALLBACK
        }
        adapter.submitList(allBoards)

        // Show current selection
        binding.tvCurrentBoard.text = "● ${prefs.selectedBoard}"
        binding.tvCurrentFqbn.text = prefs.boardFQBN
        binding.tvBoardCount.text = "${allBoards.size} boards loaded"
        binding.etBoardsTxt.setText(txt)
    }

    private fun applyBoardsTxt(text: String) {
        val parsed = BoardsParser.parse(text)
        if (parsed.isEmpty()) {
            Toast.makeText(context, "No boards found in text. Check format.", Toast.LENGTH_LONG).show()
            return
        }
        prefs.importedBoardsTxt = text
        allBoards = parsed
        adapter.submitList(parsed)
        binding.tvBoardCount.text = "${parsed.size} boards loaded"
        showTab("select")
        Toast.makeText(context, "✓ ${parsed.size} boards loaded!", Toast.LENGTH_SHORT).show()
    }

    private fun importBoardsFile() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri ->
                lifecycleScope.launch {
                    try {
                        val content = requireContext().contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.readText() ?: return@launch
                        applyBoardsTxt(content)
                        binding.etBoardsTxt.setText(content)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    companion object { const val REQUEST_FILE = 101 }
}
