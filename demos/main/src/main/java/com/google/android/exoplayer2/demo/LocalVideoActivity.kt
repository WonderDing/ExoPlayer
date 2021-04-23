package com.google.android.exoplayer2.demo

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.util.Log
import kotlinx.android.synthetic.main.activity_local_video.*
import java.io.File

/**
 * ExoPlayer的使用
 */
class LocalVideoActivity : AppCompatActivity() {
    private lateinit var player: SimpleExoPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_video)
        //获取本地视频文件的Uri
//        val ivfUri = Uri.parse(getExternalFilesDir(null)?.absolutePath + File.separator + "demo2.ivf.mp4")
//        val mp4Uri = Uri.parse(getExternalFilesDir(null)?.absolutePath + File.separator + "output.mp4")
        val secondUri =
            Uri.parse(getExternalFilesDir(null)?.absolutePath + File.separator + "video-avc-baseline-480.mp4")
        val ivfUri =
            Uri.parse(Environment.getExternalStorageDirectory().absolutePath + File.separator + "demo2.ivf.mp4")
//        Log.e("url", "mp4Uri=:$mp4Uri")
//        val defaultRenderersFactory = DefaultRenderersFactory(this)
        player = SimpleExoPlayer.Builder(this).build()//构建播放器
        spv.player = player//将player设置给StyledPlayerView
        val ivfItem = MediaItem.fromUri(ivfUri)
//        val mediaItem = MediaItem.fromUri(mp4Uri)
        val secondItem = MediaItem.fromUri(secondUri)//通过uri生成 MediaItem
        player.addMediaItem(ivfItem)
//        player.addMediaItem(mediaItem)
        player.addMediaItem(secondItem)
//        player.setVideoSurfaceView(sv)
        player.prepare()
        player.play()//播放
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()//页面销毁时释放播放器
    }

}