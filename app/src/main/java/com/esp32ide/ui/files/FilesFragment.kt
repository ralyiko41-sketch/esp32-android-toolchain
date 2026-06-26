package com.esp32ide.ui.files

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.esp32ide.MainActivity
import com.esp32ide.R
import com.esp32ide.data.AppPreferences
import com.esp32ide.data.Project
import com.esp32ide.data.ProjectFile
import com.esp32ide.data.SketchDatabase
import com.esp32ide.databinding.FragmentFilesBinding
import com.esp32ide.ui.editor.NewFileDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    private val dao by lazy { SketchDatabase.getInstance(requireContext()).projectDao() }
    private lateinit var adapter: ProjectAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        observeProjects()
    }

    private fun setupRecyclerView() {
        adapter = ProjectAdapter(
            onProjectClick = { project ->
                prefs.lastSketchId = project.id // Reusing this pref to store active projectId
                (activity as? MainActivity)?.navigateTo(R.id.nav_editor)
            },
            onDeleteClick = { project ->
                deleteProject(project)
            }
        )
        binding.rvProjects.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProjects.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnNewProject.setOnClickListener {
            showNewProjectDialog()
        }
    }

    private fun observeProjects() {
        lifecycleScope.launch {
            dao.getAllProjects().collectLatest { projects ->
                if (projects.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.rvProjects.visibility = View.GONE
                } else {
                    binding.tvEmpty.visibility = View.GONE
                    binding.rvProjects.visibility = View.VISIBLE
                    adapter.submitList(projects)
                }
            }
        }
    }

    private fun showNewProjectDialog() {
        NewFileDialog { name ->
            lifecycleScope.launch {
                val projectId = dao.insertProject(Project(name = name)).toInt()
                val mainFile = ProjectFile(
                    projectId = projectId,
                    name = "$name.ino",
                    content = DEFAULT_INO_CONTENT,
                    isMain = true
                )
                dao.insertFile(mainFile)
                
                prefs.lastSketchId = projectId
                (activity as? MainActivity)?.navigateTo(R.id.nav_editor)
                Toast.makeText(requireContext(), "Project '$name' created", Toast.LENGTH_SHORT).show()
            }
        }.show(parentFragmentManager, "new_project")
    }

    private fun deleteProject(project: Project) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete Project")
            .setMessage("Are you sure you want to delete '${project.name}'? This will remove all files in this project.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    dao.deleteProject(project)
                    Toast.makeText(requireContext(), "Project deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val DEFAULT_INO_CONTENT = """// ESP32 Sketch
void setup() {
  Serial.begin(115200);
}

void loop() {
  delay(1000);
}
"""
    }
}
