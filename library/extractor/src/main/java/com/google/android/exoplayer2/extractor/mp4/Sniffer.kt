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
package com.google.android.exoplayer2.extractor.mp4

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.extractor.ExtractorInput
import com.google.android.exoplayer2.util.ParsableByteArray
import java.io.IOException

/**
 * Provides methods that peek data from an [ExtractorInput] and return whether the input
 * appears to be in MP4 format.
 */
/* package */
internal object Sniffer {
    /** Brand stored in the ftyp atom for QuickTime media.  */
    const val BRAND_QUICKTIME = 0x71742020

    /** Brand stored in the ftyp atom for HEIC media.  */
    const val BRAND_HEIC = 0x68656963

    /** The maximum number of bytes to peek when sniffing.  */
    private const val SEARCH_LENGTH = 4 * 1024
    private val COMPATIBLE_BRANDS = intArrayOf(
        0x69736f6d,  // isom
        0x69736f32,  // iso2
        0x69736f33,  // iso3
        0x69736f34,  // iso4
        0x69736f35,  // iso5
        0x69736f36,  // iso6
        0x69736f39,  // iso9
        0x61766331,  // avc1
        0x68766331,  // hvc1
        0x68657631,  // hev1
        0x61763031,  // av01
        0x6d703431,  // mp41
        0x6d703432,  // mp42
        0x33673261,  // 3g2a
        0x33673262,  // 3g2b
        0x33677236,  // 3gr6
        0x33677336,  // 3gs6
        0x33676536,  // 3ge6
        0x33676736,  // 3gg6
        0x4d345620,  // M4V[space]
        0x4d344120,  // M4A[space]
        0x66347620,  // f4v[space]
        0x6b646469,  // kddi
        0x4d345650,  // M4VP
        BRAND_QUICKTIME,  // qt[space][space]
        0x4d534e56,  // MSNV, Sony PSP
        0x64627931,  // dby1, Dolby Vision
        0x69736d6c,  // isml
        0x70696666
    )

    /**
     * Returns whether data peeked from the current position in `input` is consistent with the
     * input being a fragmented MP4 file.
     *
     * @param input The extractor input from which to peek data. The peek position will be modified.
     * @return Whether the input appears to be in the fragmented MP4 format.
     * @throws IOException If an error occurs reading from the input.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun sniffFragmented(input: ExtractorInput, ivf: Boolean = false): Boolean {
        return sniffInternal(input, fragmented = true, acceptHeic = false, ivf = ivf)
    }

    /**
     * Returns whether data peeked from the current position in `input` is consistent with the
     * input being an unfragmented MP4 file.
     *
     * @param input The extractor input from which to peek data. The peek position will be modified.
     * @return Whether the input appears to be in the unfragmented MP4 format.
     * @throws IOException If an error occurs reading from the input.
     */
    @Throws(IOException::class)
    fun sniffUnfragmented(input: ExtractorInput): Boolean {
        return sniffInternal(input,  /* fragmented= */false,  /* acceptHeic= */false)
    }

    /**
     * Returns whether data peeked from the current position in `input` is consistent with the
     * input being an unfragmented MP4 file.
     *
     * @param input The extractor input from which to peek data. The peek position will be modified.
     * @param acceptHeic Whether `true` should be returned for HEIC photos.
     * @return Whether the input appears to be in the unfragmented MP4 format.
     * @throws IOException If an error occurs reading from the input.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun sniffUnfragmented(input: ExtractorInput, acceptHeic: Boolean): Boolean {
        return sniffInternal(input,  /* fragmented= */false, acceptHeic)
    }

    @Throws(IOException::class)
    private fun sniffInternal(
        input: ExtractorInput,
        fragmented: Boolean,
        acceptHeic: Boolean,
        ivf: Boolean = false
    ): Boolean {
        val inputLength = input.length
        var bytesToSearch =
            (if (inputLength == C.LENGTH_UNSET.toLong() || inputLength > SEARCH_LENGTH) SEARCH_LENGTH else inputLength) as Int
        val buffer = ParsableByteArray(64)
        var bytesSearched = 0
        var foundGoodFileType = false
        var isFragmented = false
        while (bytesSearched < bytesToSearch) {
            // Read an atom header.
            var headerSize = Atom.HEADER_SIZE
            buffer.reset(headerSize)
            val success = input.peekFully(buffer.data, 0, headerSize,  /* allowEndOfInput= */true)
            if (!success) {
                // We've reached the end of the file.
                break
            }
            var atomSize = buffer.readUnsignedInt()
            val atomType = buffer.readInt()
            if (atomSize == Atom.DEFINES_LARGE_SIZE.toLong()) {
                // Read the large atom size.
                headerSize = Atom.LONG_HEADER_SIZE
                input.peekFully(
                    buffer.data, Atom.HEADER_SIZE, Atom.LONG_HEADER_SIZE - Atom.HEADER_SIZE
                )
                buffer.setLimit(Atom.LONG_HEADER_SIZE)
                atomSize = buffer.readLong()
            } else if (atomSize == Atom.EXTENDS_TO_END_SIZE.toLong()) {
                // The atom extends to the end of the file.
                val fileEndPosition = input.length
                if (fileEndPosition != C.LENGTH_UNSET.toLong()) {
                    atomSize = fileEndPosition - input.peekPosition + headerSize
                }
            }
            if (atomSize < headerSize) {
                // The file is invalid because the atom size is too small for its header.
                return false
            }
            bytesSearched += headerSize
            if (atomType == Atom.TYPE_ivfh && ivf) {
                return true
            }
            if (atomType == Atom.TYPE_moov) {
                // We have seen the moov atom. We increase the search size to make sure we don't miss an
                // mvex atom because the moov's size exceeds the search length.
                bytesToSearch += atomSize.toInt()
                if (inputLength != C.LENGTH_UNSET.toLong() && bytesToSearch > inputLength) {
                    // Make sure we don't exceed the file size.
                    bytesToSearch = inputLength.toInt()
                }
                // Check for an mvex atom inside the moov atom to identify whether the file is fragmented.
                continue
            }
            if (atomType == Atom.TYPE_moof || atomType == Atom.TYPE_mvex) {
                // The movie is fragmented. Stop searching as we must have read any ftyp atom already.
                isFragmented = true
                break
            }
            if (bytesSearched + atomSize - headerSize >= bytesToSearch) {
                // Stop searching as peeking this atom would exceed the search limit.
                break
            }
            val atomDataSize = (atomSize - headerSize).toInt()
            bytesSearched += atomDataSize
            if (atomType == Atom.TYPE_ftyp) {
                // Parse the atom and check the file type/brand is compatible with the extractors.
                if (atomDataSize < 8) {
                    return false
                }
                buffer.reset(atomDataSize)
                input.peekFully(buffer.data, 0, atomDataSize)
                val brandsCount = atomDataSize / 4
                for (i in 0 until brandsCount) {
                    if (i == 1) {
                        // This index refers to the minorVersion, not a brand, so skip it.
                        buffer.skipBytes(4)
                    } else if (isCompatibleBrand(buffer.readInt(), acceptHeic)) {
                        foundGoodFileType = true
                        break
                    }
                }
                if (!foundGoodFileType) {
                    // The types were not compatible and there is only one ftyp atom, so reject the file.
                    return false
                }
            } else if (atomDataSize != 0) {
                // Skip the atom.
                input.advancePeekPosition(atomDataSize)
            }
        }
        return foundGoodFileType && fragmented == isFragmented
    }

    /**
     * Returns whether `brand` is an ftyp atom brand that is compatible with the MP4 extractors.
     */
    private fun isCompatibleBrand(brand: Int, acceptHeic: Boolean): Boolean {
        if (brand ushr 8 == 0x00336770) {
            // Brand starts with '3gp'.
            return true
        } else if (brand == BRAND_HEIC && acceptHeic) {
            return true
        }
        for (compatibleBrand in COMPATIBLE_BRANDS) {
            if (compatibleBrand == brand) {
                return true
            }
        }
        return false
    }
}