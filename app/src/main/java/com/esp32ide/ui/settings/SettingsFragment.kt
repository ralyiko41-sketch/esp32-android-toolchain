package com.esp32ide.ui.settings

import android.os.Bundle
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.esp32ide.MainActivity
import com.esp32ide.R
import com.esp32ide.data.AppPreferences
import com.esp32ide.databinding.FragmentSettingsBinding
import com.esp32ide.utils.ThemeManager

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }

    private val flashBauds = listOf(115200, 230400, 460800, 921600)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupButtons()
    }

    private fun loadSettings() {
        binding.switchDark.isChecked = prefs.darkTheme
        binding.etCloudUrl.setText(prefs.cloudCompilerUrl)
        binding.tvFontSize.text = prefs.fontSize.toString()
        binding.switchAutoIndent.isChecked = prefs.autoIndent
        binding.switchWordWrap.isChecked = prefs.wordWrap

        binding.spinnerFlashBaud.setSelection(flashBauds.indexOf(prefs.flashBaud).coerceAtLeast(0))

        // Syntax theme spinner
        val themeNames = ThemeManager.listThemeNames(requireContext())
        val themeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, themeNames)
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSyntaxTheme.adapter = themeAdapter
        val currentIndex = themeNames.indexOf(prefs.editorTheme).coerceAtLeast(0)
        binding.spinnerSyntaxTheme.setSelection(currentIndex)
    }

    private fun setupButtons() {
        // Go to Setup Screen
        binding.btnGoToSetup.setOnClickListener {
            (activity as? MainActivity)?.loadFragment(SetupFragment())
        }

        // Dark/light theme
        binding.switchDark.setOnCheckedChangeListener { _, checked ->
            prefs.darkTheme = checked
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // Font size
        binding.btnFontMinus.setOnClickListener {
            if (prefs.fontSize > 10) { prefs.fontSize--; binding.tvFontSize.text = prefs.fontSize.toString() }
        }
        binding.btnFontPlus.setOnClickListener {
            if (prefs.fontSize < 20) { prefs.fontSize++; binding.tvFontSize.text = prefs.fontSize.toString() }
        }

        // Editor toggles
        binding.switchAutoIndent.setOnCheckedChangeListener { _, v -> prefs.autoIndent = v }
        binding.switchWordWrap.setOnCheckedChangeListener { _, v -> prefs.wordWrap = v }

        // Save cloud URL
        binding.btnSaveUrl.setOnClickListener {
            prefs.cloudCompilerUrl = binding.etCloudUrl.text.toString().trim()
            Toast.makeText(context, "Saved ✓", Toast.LENGTH_SHORT).show()
        }

        // Syntax theme selection
        binding.spinnerSyntaxTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val themeName = (p?.getItemAtPosition(pos) as? String) ?: return
                if (prefs.editorTheme != themeName) {
                    prefs.editorTheme = themeName
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Flash baud
        binding.spinnerFlashBaud.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.flashBaud = flashBauds[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
