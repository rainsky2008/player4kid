package com.netdisk.player4kid.ui.parent

import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.netdisk.player4kid.App
import com.netdisk.player4kid.BuildConfig
import com.netdisk.player4kid.R
import com.netdisk.player4kid.core.FolderCovers
import com.netdisk.player4kid.core.Kiosk
import com.netdisk.player4kid.core.PlayTimeManager
import com.netdisk.player4kid.core.Prefs
import com.netdisk.player4kid.core.TimeTexts
import com.netdisk.player4kid.databinding.ActivityParentBinding
import com.netdisk.player4kid.databinding.ItemRowBinding
import com.netdisk.player4kid.player.AudioLevelMeter
import com.netdisk.player4kid.web.WebLibrary
import com.netdisk.player4kid.web.WebSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 家长设置（已通过密码验证才能进入） */
class ParentActivity : AppCompatActivity() {

    private lateinit var b: ActivityParentBinding
    private val prefs get() = App.prefs

    private val pickDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra(DirPickerActivity.EXTRA_PATH)
        if (r.resultCode == Activity.RESULT_OK && path != null) {
            if (path == prefs.panHome) {
                Toast.makeText(this, "这是资源目录，里面的文件夹已经显示在桌面上了", Toast.LENGTH_SHORT).show()
            } else {
                prefs.rootDirs = prefs.rootDirs + path
            }
            renderDirs()
        }
    }

    private val pickHome = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra(DirPickerActivity.EXTRA_PATH)
        if (r.resultCode == Activity.RESULT_OK && path != null) {
            prefs.panHome = path
            renderDirs()
        }
    }

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        renderAccount()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityParentBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnSave.setOnClickListener {
            if (save(showToast = true)) finish()
        }

        // 系统
        b.btnSystemSettings.setOnClickListener { save(); Kiosk.openSystemSettings(this) }
        b.btnWifi.setOnClickListener { save(); ParentGate.openWifi(this) }
        b.btnOtherLauncher.setOnClickListener { save(); openOtherLauncher() }
        b.btnTestSound.setOnClickListener { testSound() }

        // 账号
        b.editAppKey.setText(prefs.appKey)
        b.editSecretKey.setText(prefs.secretKey)
        b.btnLogin.setOnClickListener {
            if (!saveKeys()) return@setOnClickListener
            login.launch(android.content.Intent(this, LoginActivity::class.java))
        }
        b.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("确定退出百度网盘登录吗？")
                .setPositiveButton("退出") { _, _ ->
                    prefs.clearAccount()
                    App.api.clearListCache()
                    renderAccount()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 目录
        b.btnPanHome.setOnClickListener {
            if (!prefs.isLoggedIn) {
                Toast.makeText(this, "请先登录百度网盘", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickHome.launch(android.content.Intent(this, DirPickerActivity::class.java))
        }
        b.btnPanHomeDefault.setOnClickListener {
            prefs.panHome = Prefs.DEFAULT_PAN_HOME
            renderDirs()
        }
        b.btnAddDir.setOnClickListener {
            if (!prefs.isLoggedIn) {
                Toast.makeText(this, "请先登录百度网盘", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickDir.launch(android.content.Intent(this, DirPickerActivity::class.java))
        }
        b.btnClearCache.setOnClickListener {
            App.api.clearListCache()
            App.web.clearCache()
            FolderCovers.markAllStale()
            Toast.makeText(this, "已清除目录缓存，下次打开目录时重新读取（封面也会重新挑选）", Toast.LENGTH_SHORT).show()
        }

        // 视频服务器
        b.btnAddWeb.setOnClickListener { addWebSource() }

        // 时长
        b.editDaily.setText(prefs.dailyLimitMin.toString())
        b.editSession.setText(prefs.sessionLimitMin.toString())
        b.editRest.setText(prefs.restMin.toString())
        b.editScreenOff.setText(prefs.audioScreenOffSec.toString())
        b.btnBonus.setOnClickListener {
            App.playTime.addBonusMinutes(15)
            renderToday()
            Toast.makeText(this, "今天增加了 15 分钟", Toast.LENGTH_SHORT).show()
        }
        b.btnEndRest.setOnClickListener {
            App.playTime.endRestNow()
            renderToday()
            Toast.makeText(this, "已结束休息", Toast.LENGTH_SHORT).show()
        }
        b.btnResetToday.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("清零今天已看的时长？")
                .setPositiveButton("清零") { _, _ ->
                    App.playTime.resetToday()
                    renderToday()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 播放方式
        b.radioMode.check(
            when (prefs.videoMode) {
                Prefs.MODE_STREAM_480 -> R.id.modeStream480
                Prefs.MODE_STREAM_1080 -> R.id.modeStream1080
                Prefs.MODE_ORIGINAL -> R.id.modeOriginal
                else -> R.id.modeStream720
            }
        )

        // 桌面与防护
        b.checkKiosk.isChecked = prefs.kioskEnabled
        b.checkKiosk.setOnCheckedChangeListener { _, checked ->
            prefs.kioskEnabled = checked
            if (!checked) Kiosk.exitLockTask(this)
            renderKiosk()
        }
        b.btnSetHome.setOnClickListener { save(); Kiosk.openHomeSettings(this) }
        b.btnAdmin.setOnClickListener {
            if (Kiosk.isAdminActive(this)) {
                Toast.makeText(this, "设备管理器已激活", Toast.LENGTH_SHORT).show()
            } else {
                Kiosk.suspendGuard(3)
                startActivity(Kiosk.adminRequestIntent(this))
            }
        }
        b.btnChangePin.setOnClickListener { ParentGate.setPin(this) }
        b.btnReleaseOwner.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("解除设备所有者")
                .setMessage("解除后锁定模式失效，本程序可以被卸载。确定吗？")
                .setPositiveButton("解除") { _, _ ->
                    Kiosk.releaseDeviceOwner(this)
                    renderKiosk()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        b.txtVersion.text = "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}"
    }

    override fun onResume() {
        super.onResume()
        renderAccount()
        renderDirs()
        renderWebSources()
        renderToday()
        renderKiosk()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderAccount() {
        if (!prefs.isLoggedIn) {
            b.txtAccount.text = "未登录"
            b.btnLogout.visibility = View.GONE
            b.btnLogin.text = "扫码登录"
            return
        }
        b.btnLogout.visibility = View.VISIBLE
        b.btnLogin.text = "重新登录"
        b.txtAccount.text = "已登录：${prefs.userName.ifEmpty { "…" }}${vipName(prefs.vipType)}"
        // 未审核应用接口次数很少：账号信息每 6 小时最多查一次
        val now = System.currentTimeMillis()
        if (prefs.userName.isNotEmpty() && now - lastInfoFetch < 6 * 3600_000L) return
        lastInfoFetch = now
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { App.api.userInfo() } }
            r.onSuccess {
                b.txtAccount.text = "已登录：${prefs.userName}${vipName(prefs.vipType)}"
            }.onFailure {
                b.txtAccount.text = "已登录：${prefs.userName}（${it.message}）"
            }
        }
    }

    companion object {
        private var lastInfoFetch = 0L
    }

    private fun vipName(t: Int) = when (t) {
        1 -> "（会员）"
        2 -> "（超级会员）"
        else -> "（普通用户）"
    }

    private fun renderDirs() {
        val home = prefs.panHome
        b.txtPanHome.text = home.ifEmpty { "（未设置）" }
        b.btnPanHomeDefault.visibility = if (home == Prefs.DEFAULT_PAN_HOME) View.GONE else View.VISIBLE

        b.dirList.removeAllViews()
        // 资源目录本身已经平铺显示，不再作为单独的目录
        val dirs = prefs.rootDirs.filter { it != home }
        if (dirs.isEmpty()) {
            val row = ItemRowBinding.inflate(LayoutInflater.from(this), b.dirList, false)
            row.txtName.text = "没有"
            row.txtName.alpha = 0.5f
            row.imgIcon.setImageResource(R.drawable.ic_folder)
            b.dirList.addView(row.root)
            return
        }
        dirs.forEach { path ->
            val row = ItemRowBinding.inflate(LayoutInflater.from(this), b.dirList, false)
            row.imgIcon.setImageResource(R.drawable.ic_folder)
            row.txtName.text = path
            row.txtAction.visibility = View.VISIBLE
            row.txtAction.text = "移除"
            row.txtAction.setOnClickListener {
                prefs.rootDirs = prefs.rootDirs.filter { it != path }
                renderDirs()
            }
            b.dirList.addView(row.root)
        }
    }

    private fun renderWebSources() {
        b.webList.removeAllViews()
        val sources = prefs.webSources
        if (sources.isEmpty()) {
            val row = ItemRowBinding.inflate(LayoutInflater.from(this), b.webList, false)
            row.txtName.text = "还没有添加视频服务器"
            row.txtName.alpha = 0.5f
            row.imgIcon.setImageResource(R.drawable.ic_movie)
            b.webList.addView(row.root)
            return
        }
        sources.forEachIndexed { i, s ->
            val row = ItemRowBinding.inflate(LayoutInflater.from(this), b.webList, false)
            row.imgIcon.setImageResource(R.drawable.ic_movie)
            row.txtName.text = "${s.name}  ${s.url}"
            row.txtAction.visibility = View.VISIBLE
            row.txtAction.text = "移除"
            row.txtAction.setOnClickListener {
                prefs.webSources = sources.filterIndexed { j, _ -> j != i }
                renderWebSources()
            }
            b.webList.addView(row.root)
        }
    }

    /** 添加视频服务器：先读一次节目列表，确认地址可用 */
    private fun addWebSource() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val existing = prefs.webSources
        // 旧版播放器的服务器被移除后，方便再加回来
        val default = WebLibrary.DEFAULT_SOURCES.firstOrNull { d -> existing.none { it.url == d.url } }
        val editName = EditText(this).apply {
            hint = "名称（显示在桌面上）"
            setSingleLine()
            setText(default?.name ?: "")
        }
        val editUrl = EditText(this).apply {
            hint = "服务器地址，例如 http://192.168.1.10/"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(default?.url ?: "http://")
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(editName)
            addView(editUrl)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("添加视频服务器")
            .setView(box)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = WebLibrary.normalizeSource(editUrl.text.toString())
                if (url == null) {
                    editUrl.error = "地址不正确"
                    return@setOnClickListener
                }
                val name = editName.text.toString().trim().ifEmpty { "视频" }
                dialog.dismiss()
                checkAndAddWebSource(WebSource(name, url))
            }
        }
        dialog.show()
    }

    private fun checkAndAddWebSource(source: WebSource) {
        val add = {
            prefs.webSources = prefs.webSources.filter { it.url != source.url } + source
            renderWebSources()
        }
        Toast.makeText(this, "正在读取节目列表…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { App.web.listDir(source.url, useCache = false) } }
            r.onSuccess { list ->
                add()
                Toast.makeText(this@ParentActivity, "已添加“${source.name}”，共 ${list.size} 个节目", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                AlertDialog.Builder(this@ParentActivity)
                    .setTitle("读取节目列表失败")
                    .setMessage("${e.message}\n\n${WebLibrary.jsonUrlOf(source.url)}\n\n仍然添加吗？")
                    .setPositiveButton("仍然添加") { _, _ -> add() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun renderToday() {
        val pt = App.playTime
        val used = pt.usedTodayMs()
        val left = pt.dailyRemainingMs()
        val rest = pt.restRemainingMs()
        b.txtToday.text = buildString {
            // 精确到秒；孩子界面显示的是向下取整的整分钟（TimeTexts）
            append("今天已看 ${TimeTexts.allowancePrecise(used)}，还剩 ${TimeTexts.allowancePrecise(left)}")
            if (rest > 0) append("\n正在休息，还剩 ${TimeTexts.allowancePrecise(rest)}")
        }
        b.btnEndRest.isEnabled = rest > 0
    }

    private fun renderKiosk() {
        val owner = Kiosk.isDeviceOwner(this)
        b.txtKioskState.text = buildString {
            append("默认桌面：").append(if (Kiosk.isDefaultLauncher(this@ParentActivity)) "是" else "否（请点“设为默认桌面”）")
            append("\n设备管理器（息屏）：").append(if (Kiosk.isAdminActive(this@ParentActivity)) "已激活" else "未激活")
            append("\n锁定模式（设备所有者）：").append(if (owner) "已启用" else "未启用")
        }
        b.btnReleaseOwner.visibility = if (owner) View.VISIBLE else View.GONE
        b.btnAdmin.text = if (Kiosk.isAdminActive(this)) "设备管理器已激活" else "激活设备管理器（用于听音频时息屏）"
    }

    // ------------------------------------------------------------------ 保存

    private fun saveKeys(): Boolean {
        val key = b.editAppKey.text.toString().trim()
        val secret = b.editSecretKey.text.toString().trim()
        if (key.isEmpty() || secret.isEmpty()) {
            Toast.makeText(this, "请先填写 AppKey 和 SecretKey", Toast.LENGTH_SHORT).show()
            return false
        }
        prefs.appKey = key
        prefs.secretKey = secret
        return true
    }

    private fun save(showToast: Boolean = false): Boolean {
        fun num(t: CharSequence?, def: Int) = t?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: def
        prefs.dailyLimitMin = num(b.editDaily.text, prefs.dailyLimitMin)
        prefs.sessionLimitMin = num(b.editSession.text, prefs.sessionLimitMin)
        prefs.restMin = num(b.editRest.text, prefs.restMin)
        prefs.audioScreenOffSec = num(b.editScreenOff.text, prefs.audioScreenOffSec)
        prefs.videoMode = when (b.radioMode.checkedRadioButtonId) {
            R.id.modeStream480 -> Prefs.MODE_STREAM_480
            R.id.modeStream1080 -> Prefs.MODE_STREAM_1080
            R.id.modeOriginal -> Prefs.MODE_ORIGINAL
            else -> Prefs.MODE_STREAM_720
        }
        val key = b.editAppKey.text.toString().trim()
        val secret = b.editSecretKey.text.toString().trim()
        if (key.isNotEmpty()) prefs.appKey = key
        if (secret.isNotEmpty()) prefs.secretKey = secret
        renderToday()
        if (showToast) Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        return true
    }

    /** 用系统 ToneGenerator 在“媒体”通道播放 2 秒测试音，排查设备是否能出声 */
    private fun testSound() {
        val vol = AudioLevelMeter.mediaVolumeText(this)
        android.util.Log.i(AudioLevelMeter.TAG, "test tone, media volume $vol")
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tg.startTone(ToneGenerator.TONE_DTMF_9, 2000)
            b.root.postDelayed({ tg.release() }, 2500)
            Toast.makeText(this, "正在播放测试音（媒体音量 $vol）", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "播放测试音失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOtherLauncher() {
        val others = Kiosk.otherLaunchers(this)
        when {
            others.isEmpty() -> Toast.makeText(this, "没有找到其它桌面", Toast.LENGTH_SHORT).show()
            others.size == 1 -> Kiosk.openLauncher(this, others[0])
            else -> AlertDialog.Builder(this)
                .setTitle("选择桌面")
                .setItems(others.map { Kiosk.appLabel(this, it) }.toTypedArray()) { _, i ->
                    Kiosk.openLauncher(this, others[i])
                }
                .show()
        }
    }
}
