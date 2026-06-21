package com.esp32ide.ui.examples

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.esp32ide.MainActivity
import com.esp32ide.R
import com.esp32ide.data.AppPreferences
import com.esp32ide.data.Sketch
import com.esp32ide.data.SketchDatabase
import com.esp32ide.databinding.FragmentExamplesBinding
import com.esp32ide.utils.Examples
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ExamplesFragment : Fragment() {

    private var _binding: FragmentExamplesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ExamplesAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentExamplesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ExamplesAdapter(Examples.list) { example ->
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Load \"${example.name}\"?")
                .setMessage("This will open the example in the editor as a new file.")
                .setPositiveButton("Load") { _, _ ->
                    lifecycleScope.launch {
                        val dao = SketchDatabase.getInstance(requireContext()).sketchDao()
                        val id = dao.insertSketch(
                            Sketch(name = example.key, content = example.code)
                        )
                        // CRITICAL: Update lastSketchId so Editor loads THIS example
                        AppPreferences(requireContext()).lastSketchId = id.toInt()

                        requireActivity().runOnUiThread {
                            Toast.makeText(context, "Loaded: ${example.name}", Toast.LENGTH_SHORT).show()
                            (activity as MainActivity).navigateTo(R.id.nav_editor)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.rvExamples.adapter = adapter

        // Category chips
        setupCategories()
    }

    private fun setupCategories() {
        val cats = Examples.categories
        binding.chipGroup.removeAllViews()
        cats.forEach { cat ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = cat
                isCheckable = true
                isChecked = cat == "All"
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        val filtered = if (cat == "All") Examples.list
                        else Examples.list.filter { it.category == cat }
                        adapter.updateList(filtered)
                    }
                }
            }
            binding.chipGroup.addView(chip)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
