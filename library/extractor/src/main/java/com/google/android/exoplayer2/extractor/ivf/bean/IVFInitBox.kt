package com.google.android.exoplayer2.extractor.ivf.bean

import com.google.android.exoplayer2.extractor.ivf.Atom

/**
 * IVFInitBox 表示 IVF MP4 格式里面的 IVFInit box。
 */
class IVFInitBox(type: Int) : Atom(type) {
    var configFileName: String = ""
    var initScriptFileName: String = ""
}