package com.google.android.exoplayer2.extractor.ivf.bean


import android.util.ArrayMap
import com.google.android.exoplayer2.extractor.ivf.Atom

/**
 * MP4IVFMediaFileIndexBox 表示 IVF MP4 格式里面的 IVFMediaFileIndex box。
 */
class IVFMediaFileIndexBox(type: Int) : Atom(type) {
    var fileName: String = ""
    var mime: String = ""
    var timeScale: Int = 0
    var headerIndex: Int = 0
    var indexCount: Int = 0
    val entries: ArrayMap<Int, Long> = ArrayMap()//存储对应索引和时长的对应关系
}