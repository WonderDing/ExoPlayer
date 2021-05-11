/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.extractor.ivf

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.C.CryptoMode
import com.google.android.exoplayer2.extractor.TrackOutput.CryptoData
import com.google.android.exoplayer2.util.Assertions
import com.google.android.exoplayer2.util.Log

/**
 * Encapsulates information parsed from a track encryption (tenc) box or sample group description
 * (sgpd) box in an MP4 stream.
 */
class TrackEncryptionBox(
    isEncrypted: Boolean,
    schemeType: String?,
    perSampleIvSize: Int,
    keyId: ByteArray?,
    defaultEncryptedBlocks: Int,
    defaultClearBlocks: Int,
    defaultInitializationVector: ByteArray?
) {
    /**
     * Indicates the encryption state of the samples in the sample group.
     */
    @JvmField
    val isEncrypted: Boolean

    /**
     * The protection scheme type, as defined by the 'schm' box, or null if unknown.
     */
    @JvmField
    val schemeType: String?

    /**
     * A [TrackOutput.CryptoData] instance containing the encryption information from this
     * [TrackEncryptionBox].
     */
    @JvmField
    val cryptoData: CryptoData

    /** The initialization vector size in bytes for the samples in the corresponding sample group.  */
    @JvmField
    val perSampleIvSize: Int

    /**
     * If [.perSampleIvSize] is 0, holds the default initialization vector as defined in the
     * track encryption box or sample group description box. Null otherwise.
     */
    @JvmField
    val defaultInitializationVector: ByteArray?

    companion object {
        private const val TAG = "TrackEncryptionBox"

        @CryptoMode
        private fun schemeToCryptoMode(schemeType: String?): Int {
            return if (schemeType == null) {
                // If unknown, assume cenc.
                C.CRYPTO_MODE_AES_CTR
            } else when (schemeType) {
                C.CENC_TYPE_cenc, C.CENC_TYPE_cens -> C.CRYPTO_MODE_AES_CTR
                C.CENC_TYPE_cbc1, C.CENC_TYPE_cbcs -> C.CRYPTO_MODE_AES_CBC
                else -> {
                    Log.w(
                        TAG,
                        "Unsupported protection scheme type '" + schemeType + "'. Assuming AES-CTR "
                                + "crypto mode."
                    )
                    C.CRYPTO_MODE_AES_CTR
                }
            }
        }
    }

    /**
     * @param isEncrypted See [.isEncrypted].
     * @param schemeType See [.schemeType].
     * @param perSampleIvSize See [.perSampleIvSize].
     * @param keyId See [TrackOutput.CryptoData.encryptionKey].
     * @param defaultEncryptedBlocks See [TrackOutput.CryptoData.encryptedBlocks].
     * @param defaultClearBlocks See [TrackOutput.CryptoData.clearBlocks].
     * @param defaultInitializationVector See [.defaultInitializationVector].
     */
    init {
        Assertions.checkArgument((perSampleIvSize == 0) xor (defaultInitializationVector == null))
        this.isEncrypted = isEncrypted
        this.schemeType = schemeType
        this.perSampleIvSize = perSampleIvSize
        this.defaultInitializationVector = defaultInitializationVector
        cryptoData = CryptoData(
            schemeToCryptoMode(schemeType), keyId!!,
            defaultEncryptedBlocks, defaultClearBlocks
        )
    }
}