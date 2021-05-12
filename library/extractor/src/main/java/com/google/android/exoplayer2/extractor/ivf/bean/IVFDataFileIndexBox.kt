package com.google.android.exoplayer2.extractor.ivf.bean

import android.util.SparseArray
import com.google.android.exoplayer2.extractor.ivf.Atom

/**
 * MP4IVFDataFileIndexBox 表示 IVF MP4 格式里面的 IVFDataFileIndex box。
 */
class IVFDataFileIndexBox(type: Int) : Atom(type) {
    var fileName: String = ""
    var mime: String = ""
    var indexCount: Int = 0
    val entries: SparseArray<Int> = SparseArray()//存储对应索引和时长的对应关系
}