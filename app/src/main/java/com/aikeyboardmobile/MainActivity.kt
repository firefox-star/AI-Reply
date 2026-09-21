package com.aikeyboardmobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var presets: List<Preset>
    private lateinit var presetButtons: List<MaterialButton>
    private var selectedPreset = 0
    private var generating = false

    private lateinit var etUrl: TextInputEditText
    private lateinit var etKey: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var tvPresetHint: TextView
    private lateinit var tvSaveStatus: TextView
    private lateinit var etTestMessage: EditText
    private lateinit var tgTones: MaterialButtonToggleGroup
    private lateinit var btnGenerate: MaterialButton
    private lateinit var tvTestStatus: TextView
    private lateinit var boxResult: View
    private lateinit var tvResult: TextView
    private lateinit var btnCopy: MaterialButton
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccStatus: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        presets = Presets.all
        etUrl = findViewById(R.id.etUrl)
        etKey = findViewById(R.id.etKey)
        etModel = findViewById(R.id.etModel)
        tvPresetHint = findViewById(R.id.tvPresetHint)
        tvSaveStatus = findViewById(R.id.tvSaveStatus)
        etTestMessage = findViewById(R.id.etTestMessage)
        tgTones = findViewById(R.id.tgTones)
        btnGenerate = findViewById(R.id.btnGenerate)
        tvTestStatus = findViewById(R.id.tvTestStatus)
        boxResult = findViewById(R.id.boxResult)
        tvResult = findViewById(R.id.tvResult)
        btnCopy = findViewById(R.id.btnCopy)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvAccStatus = findViewById(R.id.tvAccStatus)

        presetButtons = listOf(
            findViewById(R.id.btnPz0), findViewById(R.id.btnPz1), findViewById(R.id.btnPz2),
            findViewById(R.id.btnPz3), findViewById(R.id.btnPz4), findViewById(R.id.btnPz5)
        )
        presetButtons.forEachIndexed { i, b -> b.setOnClickListener { selectPreset(i) } }

        // Restore whatever the user saved last time (default = Z.ai GLM)
        val cfg = Prefs.load(this)
        val savedIdx = presets.indexOfFirst { it.url == cfg.baseUrl }
        if (savedIdx >= 0) {
            selectPreset(savedIdx)
            etKey.setText(cfg.apiKey)
        } else {
            selectPreset(0)
            etUrl.setText(cfg.baseUrl)
            etModel.setText(cfg.model)
            etKey.setText(cfg.apiKey)
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveConfig() }
        btnGenerate.setOnClickListener { onGenerate() }
        btnCopy.setOnClickListener { copy(tvResult.text?.toString().orEmpty()) }

        findViewById<MaterialButton>(R.id.btnOverlay).setOnClickListener { openOverlaySettings() }
        findViewById<MaterialButton>(R.id.btnShowBubble).setOnClickListener { showBubble() }
        findViewById<MaterialButton>(R.id.btnAcc).setOnClickListener { openAccessibility() }
        findViewById<MaterialButton>(R.id.btnRestricted).setOnClickListener { showRestrictedGuide() }

        tgTones.check(R.id.btnToneFriendly)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun selectPreset(i: Int) {
        selectedPreset = i
        val p = presets[i]
        etUrl.setText(p.url)
        etModel.setText(p.model)
        tvPresetHint.text = p.hint
        tvPresetHint.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl))) }
        }
        presetButtons.forEachIndexed { j, b ->
            if (j == i) {
                b.setBackgroundColor(ContextCompat.getColor(this, R.color.brand))
                b.setTextColor(ContextCompat.getColor(this, R.color.white))
                b.strokeWidth = 0
            } else {
                b.setBackgroundColor(ContextCompat.getColor(this, R.color.white))
                b.setTextColor(ContextCompat.getColor(this, R.color.textMain))
                b.strokeWidth = 2
            }
        }
    }

    private fun readConfig(): Prefs.Config =
        Prefs.Config(
            baseUrl = etUrl.text?.toString()?.trim().orEmpty(),
            apiKey = etKey.text?.toString()?.trim().orEmpty(),
            model = etModel.text?.toString()?.trim().orEmpty()
        )

    private fun saveConfig() {
        val c = readConfig()
        if (!c.baseUrl.startsWith("http")) {
            tvSaveStatus.setTextColor(ContextCompat.getColor(this, R.color.errorRed))
            tvSaveStatus.text = "Base URL must start with http"
            return
        }
        if (c.model.isBlank()) {
            tvSaveStatus.setTextColor(ContextCompat.getColor(this, R.color.errorRed))
            tvSaveStatus.text = "Model name can't be empty"
            return
        }
        Prefs.save(this, c)
        tvSaveStatus.setTextColor(ContextCompat.getColor(this, R.color.accentGreen))
        tvSaveStatus.text = "Saved ✓ — test it below"
        Toast.makeText(this, "AI settings saved on this phone", Toast.LENGTH_SHORT).show()
    }

    /** ONE tapped tone → exactly ONE reply. */
    private fun onGenerate() {
        if (generating) return
        val tone = when (tgTones.checkedButtonId) {
            R.id.btnToneFriendly -> Tone.FRIENDLY
            R.id.btnToneProfessional -> Tone.PROFESSIONAL
            R.id.btnToneShort -> Tone.SHORT
            R.id.btnTonePlayful -> Tone.PLAYFUL
            else -> Tone.FRIENDLY
        }
        val cfg = readConfig()
        val msg = etTestMessage.text?.toString()?.trim().orEmpty()

        if (msg.isEmpty()) {
            setStatus(tvTestStatus, "Type a message you received first.", R.color.errorRed)
            return
        }
        if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
            setStatus(tvTestStatus, "Pick a preset in step 1 first.", R.color.errorRed)
            return
        }
        Prefs.save(this, cfg)

        generating = true
        btnGenerate.isEnabled = false
        btnGenerate.text = "Thinking…"
        tvTestStatus.text = ""
        boxResult.visibility = View.GONE

        Thread {
            AiClient.generate(cfg, tone, msg) { r ->
                mainHandler.post {
                    generating = false
                    btnGenerate.isEnabled = true
                    btnGenerate.text = "Generate reply"
                    when (r) {
                        is AiClient.Ok -> {
                            tvResult.text = r.text
                            boxResult.visibility = View.VISIBLE
                            setStatus(tvTestStatus, "Done ✓ — tap Copy, then paste it in your chat.", R.color.accentGreen)
                        }
                        is AiClient.Err -> setStatus(tvTestStatus, r.msg, R.color.errorRed)
                    }
                }
            }
        }.start()
    }

    private fun setStatus(tv: TextView, text: String, colorRes: Int) {
        tv.setTextColor(ContextCompat.getColor(this, colorRes))
        tv.text = text
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AI Reply", text))
        Toast.makeText(this, "Copied ✓ — now paste it in your chat", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatuses() {
        val overlayOk = Settings.canDrawOverlays(this)
        tvOverlayStatus.text = if (overlayOk) "●  Bubble permission granted ✓"
        else "●  Not granted yet — the bubble can't appear without it"
        tvOverlayStatus.setTextColor(
            ContextCompat.getColor(this, if (overlayOk) R.color.accentGreen else R.color.amberWarn)
        )

        val accEnabled = isAccessibilityEnabled()
        tvAccStatus.text = if (accEnabled) "●  Auto-read is ON ✓"
        else "●  Off — you can still use the bubble by pasting the message"
        tvAccStatus.setTextColor(
            ContextCompat.getColor(this, if (accEnabled) R.color.accentGreen else R.color.textSub)
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${ChatAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
        Toast.makeText(this, "Find AI Reply in the list and switch it ON", Toast.LENGTH_LONG).show()
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        val i = Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_SHOW_PANEL)
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "Bubble added — drag it anywhere, tap to open", Toast.LENGTH_SHORT).show()
    }

    private fun openAccessibility() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
    }

    /**
     * The step-by-step unlock guide for Android 13+'s "Restricted setting —
     * For your security, this setting is unavailable" message.
     */
    private fun showRestrictedGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unlock “Restricted setting”")
            .setMessage(
                "Android 13+ blocks accessibility switches for apps installed from a browser or file manager. " +
                "It's a one-time unlock:\n\n" +
                "1. Tap “Open App info” below.\n" +
                "2. Tap the ⋮ (three dots) in the top-right corner.\n" +
                "3. Tap “Allow restricted settings”.\n" +
                "4. Come back here and tap “Enable in Accessibility settings”, then switch ON AI Reply.\n\n" +
                "Don't see the three dots? Uninstall the old AI app, then install this new APK from your Files app — " +
                "or connect to a PC once and run: adb install AI-Reply-v3.0.0.apk\n\n" +
                "Remember: this is OPTIONAL. The bubble works right now without it — just paste the message in."
            )
            .setPositiveButton("Open App info") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            .setNeutralButton("Accessibility settings") { _, _ -> openAccessibility() }
            .setNegativeButton("Got it", null)
            .show()
    }
}
