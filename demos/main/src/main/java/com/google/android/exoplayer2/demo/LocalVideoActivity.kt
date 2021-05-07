package com.google.android.exoplayer2.demo

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.DebugTextViewHelper
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.FileDataSource
import com.google.android.exoplayer2.upstream.ResolvingDataSource
import com.google.android.exoplayer2.util.EventLogger
import com.google.android.exoplayer2.util.Log
import com.google.android.exoplayer2.video.VideoListener
import kotlinx.android.synthetic.main.activity_local_video.*
import java.io.File

/**
 * ExoPlayer的使用
 */
class LocalVideoActivity : AppCompatActivity() {
    private lateinit var player: SimpleExoPlayer
    private lateinit var player2: SimpleExoPlayer
    private var debugViewHelper: DebugTextViewHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_video)
        //获取本地视频文件的Uri
//        val ivfUri = Uri.parse(getExternalFilesDir(null)?.absolutePath + File.separator + "demo2.ivf.mp4")
//        val mp4Uri = Uri.parse(getExternalFilesDir(null)?.absolutePath + File.separator + "output.mp4")
//        val secondUri =
//            Uri.parse(getExternalFilesDir(null)?.absolutePath + File.separator + "video-avc-baseline-480.mp4")
        val ivfUri =
            Uri.parse(Environment.getExternalStorageDirectory().absolutePath + File.separator + "demo2v2.ivf.mp4")
        Log.e("url", "mp4Uri=:$ivfUri")
//        val defaultRenderersFactory = DefaultRenderersFactory(this)
        player = SimpleExoPlayer.Builder(this).build()//构建播放器
        player2 = SimpleExoPlayer.Builder(this).build()//构建播放器
        player.addAnalyticsListener(EventLogger(null))
        spv.player = player//将player设置给StyledPlayerView
        val ivfItem = MediaItem.fromUri(ivfUri)
        val resolver = ResolvingDataSource.Resolver { dataSpec ->
            DataSpec.Builder().setUri(dataSpec.uri).setPosition(dataSpec.position).setLength(14477731).build()
        }
        val mds1 = ProgressiveMediaSource.Factory(
            ResolvingDataSource.Factory(
                FileDataSource.Factory(),
                resolver
            )
        ).createMediaSource(ivfItem)
        val resolver1 = ResolvingDataSource.Resolver { dataSpec ->
            DataSpec.Builder().setUri(dataSpec.uri)
                .setPosition(dataSpec.position + 14478987)
                .setLength(438760).build()
        }
        val mds2 = ProgressiveMediaSource.Factory(
            ResolvingDataSource.Factory(
                FileDataSource.Factory(),
                resolver1
            )
        ).createMediaSource(ivfItem)
        val resolver2 = ResolvingDataSource.Resolver { dataSpec ->
            DataSpec.Builder().setUri(dataSpec.uri)
                .setPosition(dataSpec.position + 14917747)
                .setLength(16026394).build()
        }
        val mds3 = ProgressiveMediaSource.Factory(
            ResolvingDataSource.Factory(
                FileDataSource.Factory(),
                resolver2
            )
        ).createMediaSource(ivfItem)
        val resolver3 = ResolvingDataSource.Resolver { dataSpec ->
            DataSpec.Builder().setUri(dataSpec.uri)
                .setPosition(dataSpec.position + 30944141)
                .setLength(4493772).build()
        }
        val mds4 = ProgressiveMediaSource.Factory(
            ResolvingDataSource.Factory(
                FileDataSource.Factory(),
                resolver3
            )
        ).createMediaSource(ivfItem)
        val concatenatingMediaSource = ConcatenatingMediaSource(mds1, mds2, mds4, mds2, mds1, mds1)
//        val mediaItem = MediaItem.fromUri(mp4Uri)
//        val secondItem = MediaItem.fromUri(secondUri)//通过uri生成 MediaItem
//        player.addMediaItem(ivfItem)
//        player.addMediaItem(mediaItem)
//        player.addMediaItem(secondItem)
//        player.setVideoSurfaceView(sv)
        player.addMediaSource(concatenatingMediaSource)
//        player.addMediaSource(mds1)
//        player.addMediaSource(mds2)
//        player.addMediaSource(mds3)
//        player.addMediaSource(mds4)
        player.prepare()
//        player.seekToDefaultPosition(1)
        player.play()//播放
        player.addVideoListener(object : VideoListener {})
        debugViewHelper = DebugTextViewHelper(player, debug_text_view)
        debugViewHelper?.start()
        iv_one.setOnClickListener {/*seek to 14917747*/
            player.addMediaSource(mds3)
            player.prepare()
        }
        val extraInfoParser = ExtraInfoParser()
        val extraInfoParser2 = ExtraInfoParser()

        val dataSpec1 = DataSpec.Builder().setUri(ivfUri).setPosition(35536708).setLength(23548).build()
        val dataSpec2 = DataSpec.Builder().setUri(ivfUri).setPosition(35437913).setLength(98795).build()
//        Thread(Runnable {
//            extraInfoParser.open(dataSpec)
//            val readBytes = extraInfoParser.readBytes(dataSpec)
//            val bitmap = BitmapFactory.decodeByteArray(readBytes, 0, dataSpec.length.toInt())
//        }).start()
        extraInfoParser.open(dataSpec1)

        extraInfoParser2.open(dataSpec2)
        val readBytes = extraInfoParser.readBytes(dataSpec1)
        val readBytes2 = extraInfoParser2.readBytes(dataSpec2)
        val bitmap = BitmapFactory.decodeByteArray(readBytes, 0, dataSpec1.length.toInt())
        val bitmap2 = BitmapFactory.decodeByteArray(readBytes2, 0, dataSpec2.length.toInt())
        iv_one.setImageBitmap(bitmap)
        iv_two.setImageBitmap(bitmap2)

//        Glide.with(this).load(readBytes).into(iv_one)
        iv_two.setOnClickListener { /*seek to 30944141*/ }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, reason: Int) {
                Log.d("TAG", eventTime.toString() + "reason=" + reason)
            }
        })

    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()//页面销毁时释放播放器
        debugViewHelper?.stop()
    }

}