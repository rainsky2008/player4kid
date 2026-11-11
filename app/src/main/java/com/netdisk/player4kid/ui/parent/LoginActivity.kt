package com.netdisk.player4kid.ui.parent

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.netdisk.player4kid.App
import com.netdisk.player4kid.baidu.BaiduApi
import com.netdisk.player4kid.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 百度网盘扫码登录（OAuth 设备码模式）：
 * 设备上显示二维码，家长用手机扫码授权，设备轮询拿到 access_token。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnRefresh.setOnClickListener { start() }
        b.btnClose.setOnClickListener { finish() }
        start()
    }

    private fun start() {
        job?.cancel()
        b.progress.visibility = View.VISIBLE
        b.imgQr.setImageDrawable(null)
        b.txtCode.text = ""
        b.txtStatus.text = "正在获取二维码…"
        job = lifecycleScope.launch {
            val dc = withContext(Dispatchers.IO) { runCatching { App.api.requestDeviceCode() } }
                .getOrElse {
                    b.progress.visibility = View.GONE
                    b.txtStatus.text = "获取二维码失败：${it.message}"
                    return@launch
                }
            b.progress.visibility = View.GONE
            Glide.with(this@LoginActivity).load(dc.qrcodeUrl).into(b.imgQr)
            b.txtCode.text = "也可以在浏览器打开 ${dc.verificationUrl}\n输入验证码：${dc.userCode}"
            b.txtStatus.text = "等待扫码授权…"

            var interval = dc.interval
            val deadline = SystemClock.elapsedRealtime() + dc.expiresIn * 1000L
            while (isActive && SystemClock.elapsedRealtime() < deadline) {
                delay(interval * 1000L)
                when (val r = withContext(Dispatchers.IO) { App.api.pollDeviceToken(dc.deviceCode) }) {
                    BaiduApi.PollResult.Success -> {
                        b.txtStatus.text = "授权成功，正在读取账号信息…"
                        withContext(Dispatchers.IO) { runCatching { App.api.userInfo() } }
                        App.api.clearListCache()
                        Toast.makeText(this@LoginActivity, "登录成功：${App.prefs.userName}", Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_OK)
                        finish()
                        return@launch
                    }
                    BaiduApi.PollResult.Pending -> {}
                    BaiduApi.PollResult.SlowDown -> interval += 2
                    is BaiduApi.PollResult.Fatal -> {
                        b.txtStatus.text = "${r.message}，请点“刷新二维码”重试"
                        return@launch
                    }
                    is BaiduApi.PollResult.Error -> b.txtStatus.text = "授权出错：${r.message}，继续等待…"
                }
            }
            b.txtStatus.text = "二维码已过期，请点“刷新二维码”"
        }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
