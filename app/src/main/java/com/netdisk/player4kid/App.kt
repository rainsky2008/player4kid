package com.netdisk.player4kid

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.netdisk.player4kid.baidu.BaiduApi
import com.netdisk.player4kid.core.Kiosk
import com.netdisk.player4kid.core.PlayTimeManager
import com.netdisk.player4kid.core.Prefs
import com.netdisk.player4kid.web.WebLibrary
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        playTime = PlayTimeManager(this, prefs)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                // 百度网盘接口及下载要求 UA 为 pan.baidu.com（各请求可自行覆盖）
                if (req.header("User-Agent") == null) {
                    chain.proceed(req.newBuilder().header("User-Agent", BaiduApi.UA_DEFAULT).build())
                } else chain.proceed(req)
            }
            .build()
        api = BaiduApi(this, prefs, http)
        web = WebLibrary(this, http)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    playTime.flush()
                    Kiosk.onAppBackgrounded(this@App)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        Kiosk.applyDeviceOwnerPolicies(this)
    }

    companion object {
        lateinit var instance: App
            private set
        lateinit var prefs: Prefs
            private set
        lateinit var playTime: PlayTimeManager
            private set
        lateinit var http: OkHttpClient
            private set
        lateinit var api: BaiduApi
            private set
        /** 自建视频服务器（可选） */
        lateinit var web: WebLibrary
            private set

        @Volatile
        var startedActivities = 0
    }
}
