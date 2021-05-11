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

import android.net.Uri
import com.google.android.exoplayer2.extractor.Extractor
import com.google.android.exoplayer2.extractor.ExtractorsFactory
import com.google.android.exoplayer2.extractor.amr.AmrExtractor
import com.google.android.exoplayer2.extractor.flac.FlacExtractor
import com.google.android.exoplayer2.extractor.flv.FlvExtractor
import com.google.android.exoplayer2.extractor.jpeg.JpegExtractor
import com.google.android.exoplayer2.extractor.mkv.MatroskaExtractor
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor
import com.google.android.exoplayer2.extractor.mp4.FragmentedMp4Extractor
import com.google.android.exoplayer2.extractor.mp4.Mp4Extractor
import com.google.android.exoplayer2.extractor.ogg.OggExtractor
import com.google.android.exoplayer2.extractor.ts.*
import com.google.android.exoplayer2.extractor.wav.WavExtractor
import com.google.android.exoplayer2.util.FileTypes

/**
 * An [ExtractorsFactory] that provides an array of extractors for the following formats:
 *
 *
 *  * MP4, including M4A ([Mp4Extractor])
 *  * fMP4 ([FragmentedMp4Extractor])
 *  * Matroska and WebM ([MatroskaExtractor])
 *  * Ogg Vorbis/FLAC ([OggExtractor]
 *  * MP3 ([Mp3Extractor])
 *  * AAC ([AdtsExtractor])
 *  * MPEG TS ([TsExtractor])
 *  * MPEG PS ([PsExtractor])
 *  * FLV ([FlvExtractor])
 *  * WAV ([WavExtractor])
 *  * AC3 ([Ac3Extractor])
 *  * AC4 ([Ac4Extractor])
 *  * AMR ([AmrExtractor])
 *  * FLAC
 *
 *  * If available, the FLAC extension's `com.google.android.exoplayer2.ext.flac.FlacExtractor` is used.
 *  * Otherwise, the core [FlacExtractor] is used. Note that Android devices do not
 * generally include a FLAC decoder before API 27. This can be worked around by using
 * the FLAC extension or the FFmpeg extension.
 *
 *  * JPEG ([JpegExtractor])
 *
 */
class IVFExtractorsFactory : ExtractorsFactory {
    companion object {
        // Extractors order is optimized according to
        // https://docs.google.com/document/d/1w2mKaWMxfz2Ei8-LdxqbPs1VLe_oudB-eryXXw9OvQQ.
        // The JPEG extractor appears after audio/video extractors because we expect audio/video input to
        // be more common.
        private val DEFAULT_EXTRACTOR_ORDER = intArrayOf(
            FileTypes.IVF,
            FileTypes.MP4)
    }

    private var constantBitrateSeekingEnabled = false


    @Mp4Extractor.Flags
    private var mp4Flags = 0

    @FragmentedMp4Extractor.Flags
    private var fragmentedMp4Flags = 0

    @IvfExtractor.Flags
    private val ivfFlags = 0

    private var tsTimestampSearchBytes: Int

    /**
     * Convenience method to set whether approximate seeking using constant bitrate assumptions should
     * be enabled for all extractors that support it. If set to true, the flags required to enable
     * this functionality will be OR'd with those passed to the setters when creating extractor
     * instances. If set to false then the flags passed to the setters will be used without
     * modification.
     *
     * @param constantBitrateSeekingEnabled Whether approximate seeking using a constant bitrate
     * assumption should be enabled for all extractors that support it.
     * @return The factory, for convenience.
     */
    @Synchronized
    fun setConstantBitrateSeekingEnabled(
        constantBitrateSeekingEnabled: Boolean
    ): IVFExtractorsFactory {
        this.constantBitrateSeekingEnabled = constantBitrateSeekingEnabled
        return this
    }

    /**
     * Sets flags for [Mp4Extractor] instances created by the factory.
     *
     * @param flags The flags to use.
     * @return The factory, for convenience.
     * @see Mp4Extractor.Mp4Extractor
     */
    @Synchronized
    fun setMp4ExtractorFlags(@Mp4Extractor.Flags flags: Int): IVFExtractorsFactory {
        mp4Flags = flags
        return this
    }

    /**
     * Sets flags for [FragmentedMp4Extractor] instances created by the factory.
     *
     * @param flags The flags to use.
     * @return The factory, for convenience.
     * @see FragmentedMp4Extractor.FragmentedMp4Extractor
     */
    @Synchronized
    fun setFragmentedMp4ExtractorFlags(
        @FragmentedMp4Extractor.Flags flags: Int
    ): IVFExtractorsFactory {
        fragmentedMp4Flags = flags
        return this
    }

    /**
     * Sets the number of bytes searched to find a timestamp for [TsExtractor] instances created
     * by the factory.
     *
     * @param timestampSearchBytes The number of search bytes to use.
     * @return The factory, for convenience.
     * @see TsExtractor.TsExtractor
     */
    @Synchronized
    fun setTsExtractorTimestampSearchBytes(
        timestampSearchBytes: Int
    ): IVFExtractorsFactory {
        tsTimestampSearchBytes = timestampSearchBytes
        return this
    }

    @Synchronized
    override fun createExtractors(): Array<Extractor> {
        return createExtractors(Uri.EMPTY, HashMap())
    }

    @Synchronized
    override fun createExtractors(
        uri: Uri, responseHeaders: Map<String, List<String>>
    ): Array<Extractor> {
        val extractors: MutableList<Extractor> = ArrayList( /* initialCapacity= */14)
        @FileTypes.Type val responseHeadersInferredFileType =
            FileTypes.inferFileTypeFromResponseHeaders(responseHeaders)
        if (responseHeadersInferredFileType != FileTypes.UNKNOWN) {
            addExtractorsForFileType(responseHeadersInferredFileType, extractors)
        }
        @FileTypes.Type val uriInferredFileType = FileTypes.inferFileTypeFromUri(uri)
        if (uriInferredFileType != FileTypes.UNKNOWN
            && uriInferredFileType != responseHeadersInferredFileType
        ) {
            addExtractorsForFileType(uriInferredFileType, extractors)
        }
        for (fileType in DEFAULT_EXTRACTOR_ORDER) {
            if (fileType != responseHeadersInferredFileType && fileType != uriInferredFileType) {
                addExtractorsForFileType(fileType, extractors)
            }
        }
        return extractors.toTypedArray()
    }

    private fun addExtractorsForFileType(@FileTypes.Type fileType: Int, extractors: MutableList<Extractor>) {
        when (fileType) {
            FileTypes.MP4 -> {
                extractors.add(FragmentedMp4Extractor(fragmentedMp4Flags))
                extractors.add(Mp4Extractor(mp4Flags))
            }
            FileTypes.IVF -> extractors.add(
                IvfExtractor(
                    ivfFlags
                )
            )

            else -> {
            }
        }
    }

    init {
        tsTimestampSearchBytes = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
    }
}