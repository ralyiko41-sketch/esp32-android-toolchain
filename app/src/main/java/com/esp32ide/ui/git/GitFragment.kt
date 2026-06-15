package com.esp32ide.ui.git

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.data.AppPreferences
import com.esp32ide.data.Sketch
import com.esp32ide.data.SketchDatabase
import com.esp32ide.databinding.FragmentGitBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.util.Base64

class GitFragment : Fragment() {

    private var _binding: FragmentGitBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentGitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSavedConfig()
        setupButtons()
    }

    private fun loadSavedConfig() {
        binding.etRemote.setText(prefs.gitRemote)
        binding.etUser.setText(prefs.gitUser)
        binding.etToken.setText(prefs.gitToken)
        binding.etBranch.setText(prefs.gitBranch)
    }

    private fun saveConfig() {
        prefs.gitRemote = binding.etRemote.text.toString().trim()
        prefs.gitUser   = binding.etUser.text.toString().trim()
        prefs.gitToken  = binding.etToken.text.toString().trim()
        prefs.gitBranch = binding.etBranch.text.toString().trim()
    }

    private fun setupButtons() {
        binding.btnStatus.setOnClickListener { runStatus() }
        binding.btnClone.setOnClickListener { runClone() }
        binding.btnPush.setOnClickListener { runPush() }
        binding.btnClearOutput.setOnClickListener { binding.tvOutput.text = "" }
    }

    private fun log(msg: String) {
        activity?.runOnUiThread {
            binding.tvOutput.append("$msg\n")
            binding.scrollOutput.post { binding.scrollOutput.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnStatus.isEnabled = !busy
        binding.btnClone.isEnabled  = !busy
        binding.btnPush.isEnabled   = !busy
        binding.progressGit.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun repoPath() = prefs.gitRemote
        .replace("https://github.com/", "")
        .replace(".git", "")

    private fun authHeader() = "token ${prefs.gitToken}"

    private fun runStatus() {
        saveConfig()
        if (prefs.gitRemote.isEmpty()) { Toast.makeText(context, "Enter Remote URL", Toast.LENGTH_SHORT).show(); return }
        setBusy(true)
        log("$ git status")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/${repoPath()}")
                    .header("Authorization", authHeader())
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val j = JSONObject(body)
                    log("Repo: ${j.optString("full_name")}")
                    log("Branch: ${prefs.gitBranch}")
                    log("Private: ${j.optBoolean("private")}")
                    log("Last push: ${j.optString("pushed_at")}")
                } else {
                    log("Error: HTTP ${resp.code}")
                    if (resp.code == 401) log("✗ Invalid token or no access")
                    if (resp.code == 404) log("✗ Repo not found or private (need token)")
                }
            } catch (e: Exception) { log("✗ ${e.message}") }
            withContext(Dispatchers.Main) { setBusy(false) }
        }
    }

    private fun runClone() {
        saveConfig()
        if (prefs.gitRemote.isEmpty()) { Toast.makeText(context, "Enter Remote URL", Toast.LENGTH_SHORT).show(); return }
        setBusy(true)
        log("$ git clone ${prefs.gitRemote}")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/${repoPath()}/contents")
                    .header("Authorization", authHeader())
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) { log("✗ HTTP ${resp.code}"); setBusy(false); return@launch }

                val files = org.json.JSONArray(resp.body?.string() ?: "[]")
                val dao = SketchDatabase.getInstance(requireContext()).sketchDao()
                var loaded = 0

                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val name = f.getString("name")
                    if (!name.endsWith(".ino") && !name.endsWith(".cpp") && !name.endsWith(".h")) continue

                    val dlUrl = f.getString("download_url")
                    val contentResp = client.newCall(Request.Builder().url(dlUrl).build()).execute()
                    val content = contentResp.body?.string() ?: continue

                    val sketchName = name.substringBeforeLast(".")
                    dao.insertSketch(Sketch(name = sketchName, content = content))
                    log("  ✓ Loaded: $name")
                    loaded++
                }
                log("✓ Clone done! $loaded file(s) added to editor.")
            } catch (e: Exception) { log("✗ ${e.message}") }
            withContext(Dispatchers.Main) { setBusy(false) }
        }
    }

    private fun runPush() {
        saveConfig()
        val msg = binding.etCommitMsg.text.toString().trim()
        if (msg.isEmpty()) { Toast.makeText(context, "Enter commit message", Toast.LENGTH_SHORT).show(); return }
        if (prefs.gitToken.isEmpty()) { Toast.makeText(context, "Enter GitHub token", Toast.LENGTH_SHORT).show(); return }
        setBusy(true)
        log("$ git add .\n$ git commit -m \"$msg\"\n$ git push origin ${prefs.gitBranch}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = SketchDatabase.getInstance(requireContext()).sketchDao()
                var pushed = 0

                // Collect all sketches
                val sketches = dao.getAllSketches().first()

                for (sketch in sketches) {
                    val fileName = "${sketch.name}.ino"
                    val content64 = Base64.encodeToString(
                        sketch.content.toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    )
                    // Get current SHA
                    var sha: String? = null
                    try {
                        val getResp = client.newCall(
                            Request.Builder()
                                .url("https://api.github.com/repos/${repoPath()}/contents/$fileName")
                                .header("Authorization", authHeader())
                                .header("Accept", "application/vnd.github.v3+json")
                                .build()
                        ).execute()
                        if (getResp.isSuccessful) {
                            sha = JSONObject(getResp.body?.string() ?: "").optString("sha")
                        }
                    } catch (_: Exception) {}

                    val body = JSONObject().apply {
                        put("message", msg)
                        put("content", content64)
                        put("branch", prefs.gitBranch)
                        if (!sha.isNullOrEmpty()) put("sha", sha)
                    }

                    val resp = client.newCall(
                        Request.Builder()
                            .url("https://api.github.com/repos/${repoPath()}/contents/$fileName")
                            .header("Authorization", authHeader())
                            .header("Accept", "application/vnd.github.v3+json")
                            .header("Content-Type", "application/json")
                            .put(body.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()

                    if (resp.isSuccessful) {
                        log("  ✓ Pushed: $fileName")
                        pushed++
                    } else {
                        val err = JSONObject(resp.body?.string() ?: "{}").optString("message")
                        log("  ✗ $fileName: $err")
                    }
                }
                log("✓ Pushed $pushed file(s) to ${prefs.gitRemote}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                log("✗ Error collecting sketches")
            } catch (e: Exception) {
                log("✗ ${e.message}")
            }
            withContext(Dispatchers.Main) { setBusy(false) }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
