package com.medo.gamebot

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            val i = Intent(this, BotService::class.java)
                .setAction(BotService.ACTION_START)
                .putExtra("code", r.resultCode)
                .putExtra("data", d)
            startForegroundService(i)
            packageManager.getLaunchIntentForPackage(Prefs.game(this))?.let { startActivity(it) }
        } else {
            toast("لازم توافق على تسجيل الشاشة عشان البوت يشوف")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(24), dp(16), dp(24))
        col.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val title = TextView(this)
        title.text = "GameBot"
        title.textSize = 26f
        title.gravity = Gravity.CENTER
        col.addView(title)

        status = TextView(this)
        status.textSize = 15f
        status.setPadding(0, dp(12), 0, dp(12))
        col.addView(status)

        col.addView(button("١) صلاحية الظهور فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("٢) تفعيل خدمة التحكم (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("٣) اختار لعبتك") { pickGame() })

        val gainLabel = TextView(this)
        gainLabel.text = "حساسية التصويب (لو بيزوّغ زوّدها أو قلّلها)"
        gainLabel.setPadding(0, dp(12), 0, 0)
        col.addView(gainLabel)
        val sb = SeekBar(this)
        sb.max = 170
        sb.progress = ((Prefs.gain(this) * 100).toInt() - 30).coerceIn(0, 170)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { Prefs.setGain(this@MainActivity, (p + 30) / 100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(sb)

        col.addView(button("٤) شغّل البوت وافتح اللعبة") { startBot() })
        col.addView(button("إيقاف كامل") {
            startService(Intent(this, BotService::class.java).setAction(BotService.ACTION_STOP))
        })

        val help = TextView(this)
        help.textSize = 13f
        help.setPadding(0, dp(16), 0, 0)
        help.text = "بعد ما تدخل ماتش: اضغط مطوّلاً على الفقاعة العائمة ثم «معايرة»، والمس كل زر مرة واحدة. بعدها اضغط الفقاعة لتشغيل البوت، والمربع الأحمر ■ لإيقافه فوراً. التطبيق بلا إنترنت، ولا يعمل إلا لما لعبتك تكون قدامك."
        col.addView(help)

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun refresh() {
        val ok = "✅"
        val no = "⬜"
        val game = Prefs.game(this)
        val cal = Prefs.buttons(this).containsKey("FIRE")
        status.text = listOf(
            "${if (Settings.canDrawOverlays(this)) ok else no} صلاحية الظهور فوق التطبيقات",
            "${if (a11yOn()) ok else no} خدمة التحكم",
            "${if (game.isNotEmpty()) ok else no} اللعبة: ${if (game.isNotEmpty()) game else "لم تُختر"}",
            "${if (cal) ok else no} معايرة الأزرار (من الفقاعة)"
        ).joinToString("\n")
    }

    private fun pickGame() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("اختار لعبتك")
            .setItems(apps.map { it.first }.toTypedArray()) { _, k ->
                Prefs.setGame(this, apps[k].second)
                refresh()
            }
            .show()
    }

    private fun startBot() {
        if (!Settings.canDrawOverlays(this)) { toast("فعّل صلاحية الظهور فوق التطبيقات (الزر ١)"); return }
        if (!a11yOn()) { toast("فعّل خدمة التحكم من الإعدادات (الزر ٢)"); return }
        if (Prefs.game(this).isEmpty()) { toast("اختار لعبتك الأول (الزر ٣)"); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        capture.launch(mpm.createScreenCaptureIntent())
    }
}
