package com.esp32ide

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.data.AppPreferences
import com.esp32ide.databinding.ActivityMainBinding
import com.esp32ide.serial.UsbSerialManager
import com.esp32ide.ui.boards.BoardManagerFragment
import com.esp32ide.ui.compose.IDEMenuBar
import com.esp32ide.ui.editor.EditorFragment
import com.esp32ide.ui.examples.ExamplesFragment
import com.esp32ide.ui.flash.FlashFragment
import com.esp32ide.ui.git.GitFragment
import com.esp32ide.ui.libraries.LibraryFragment
import com.esp32ide.ui.monitor.SerialMonitorFragment
import com.esp32ide.ui.ota.OTAFragment
import com.esp32ide.ui.settings.SettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    lateinit var serialManager: UsbSerialManager
    private val prefs by lazy { AppPreferences(this) }

    // Shared compile state
    var lastCompiledBinPath: String = ""
    var lastCompiledBinSize: Int = 0
    var isCompiling: Boolean = false

    // Callbacks from editor to monitor
    var onSerialLine: ((String, String) -> Unit)? = null // (timestamp, line)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.darkTheme) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serialManager = UsbSerialManager(this)

        setupDrawer()
        setupComposeMenu()
        setupBackNavigation()

        if (savedInstanceState == null) {
            lifecycleScope.launch {
                // Pre-warm DB
                com.esp32ide.data.SketchDatabase.getInstance(this@MainActivity).sketchDao().getCount()
                
                withContext(Dispatchers.Main) {
                    loadFragment(EditorFragment())
                    binding.navView.setCheckedItem(R.id.nav_editor)
                }
            }
        }

        // Handle USB device attached intent
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttached()
        }
    }

    private fun setupDrawer() {
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }

        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_editor   -> loadFragment(EditorFragment())
                R.id.nav_monitor  -> loadFragment(SerialMonitorFragment())
                R.id.nav_flash    -> loadFragment(FlashFragment())
                R.id.nav_libs     -> loadFragment(LibraryFragment())
                R.id.nav_boards   -> loadFragment(BoardManagerFragment())
                R.id.nav_examples -> loadFragment(ExamplesFragment())
                R.id.nav_git      -> loadFragment(GitFragment())
                R.id.nav_ota      -> loadFragment(OTAFragment())
                R.id.nav_settings -> loadFragment(SettingsFragment())
            }
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            true
        }
    }

    private fun setupComposeMenu() {
        // Add the Compose 3-dot menu to the Toolbar
        val composeView = ComposeView(this).apply {
            setContent {
                IDEMenuBar()
            }
        }
        binding.toolbar.addView(composeView, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // Position it to the right
        composeView.layoutParams = (composeView.layoutParams as? androidx.appcompat.widget.Toolbar.LayoutParams)?.apply {
            gravity = android.view.Gravity.END
        } ?: androidx.appcompat.widget.Toolbar.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = android.view.Gravity.END
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (currentFragment !is EditorFragment) {
                    // Navigate back to Editor
                    navigateTo(R.id.nav_editor)
                } else {
                    // Already on Editor, perform standard back (exit)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    fun loadFragment(fragment: Fragment) {
        val tag = if (fragment is EditorFragment) "editor" else null
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commitAllowingStateLoss()
    }

    fun navigateTo(itemId: Int) {
        binding.navView.setCheckedItem(itemId)
        // Simulate click to load fragment
        when (itemId) {
            R.id.nav_editor   -> loadFragment(EditorFragment())
            R.id.nav_monitor  -> loadFragment(SerialMonitorFragment())
            R.id.nav_flash    -> loadFragment(FlashFragment())
            R.id.nav_libs     -> loadFragment(LibraryFragment())
            R.id.nav_boards   -> loadFragment(BoardManagerFragment())
            R.id.nav_examples -> loadFragment(ExamplesFragment())
            R.id.nav_git      -> loadFragment(GitFragment())
            R.id.nav_ota      -> loadFragment(OTAFragment())
            R.id.nav_settings -> loadFragment(SettingsFragment())
        }
    }

    private fun handleUsbAttached() {
        navigateTo(R.id.nav_flash)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttached()
        }
    }

    override fun onDestroy() {
        serialManager.destroy()
        super.onDestroy()
    }
}
