package com.netdisk.player4kid.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.netdisk.player4kid.ui.AudioPlayerActivity

/**
 * 音频后台播放服务：把 [AudioEngine] 的播放器包装为 MediaSession，
 * 播放时自动成为前台服务（带通知），息屏也不会被系统杀掉。
 */
@OptIn(UnstableApi::class)
class AudioService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, AudioPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val s = MediaSession.Builder(this, AudioEngine.get(this))
            .setSessionActivity(open)
            .build()
        session = s
        // 没有 MediaController 连接时也要显示通知 / 前台运行
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            release()
        }
        session = null
        AudioEngine.release()
        super.onDestroy()
    }
}
