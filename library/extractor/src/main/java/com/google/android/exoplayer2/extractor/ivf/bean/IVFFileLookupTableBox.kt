package com.google.android.exoplayer2.extractor.ivf.bean

import android.util.ArrayMap
import com.google.android.exoplayer2.extractor.ivf.Atom

/**
 * IVFFileLookupTableBox 表示 IVF MP4 格式里面的 IVFFileLookupTable box。
 */
class IVFFileLookupTableBox(type: Int) : Atom(type) {
    var entryCount: Int = 0
    val entries: ArrayMap<Long, Long> = ArrayMap()//存储片段的偏移量及长度
}