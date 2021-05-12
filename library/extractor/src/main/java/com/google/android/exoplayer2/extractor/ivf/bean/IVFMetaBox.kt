package com.google.android.exoplayer2.extractor.ivf.bean

import android.util.ArrayMap
import com.google.android.exoplayer2.extractor.ivf.Atom

/**
 * IVFInitBox 表示 IVF MP4 格式里面的 IVFInit box。
 */
class IVFMetaBox(type: Int) : Atom(type) {
    val entries: ArrayMap<String, String> = ArrayMap()
    var metaCount: Int = 0
}