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

import com.google.android.exoplayer2.extractor.ExtractorInput
import com.google.android.exoplayer2.util.ParsableByteArray
import org.checkerframework.checker.nullness.qual.MonotonicNonNull
import java.io.IOException

/**
 * A holder for information corresponding to a single fragment of an mp4 file.
 */
/* package */
internal class TrackFragment {
    /** The default values for samples from the track fragment header.  */
    var header: @MonotonicNonNull DefaultSampleValues? = null

    /**
     * The position (byte offset) of the start of fragment.
     */
    var atomPosition: Long = 0

    /**
     * The position (byte offset) of the start of data contained in the fragment.
     */
    var dataPosition: Long = 0

    /**
     * The position (byte offset) of the start of auxiliary data.
     */
    var auxiliaryDataPosition: Long = 0

    /**
     * The number of track runs of the fragment.
     */
    var trunCount = 0

    /**
     * The total number of samples in the fragment.
     */
    var sampleCount = 0

    /**
     * The position (byte offset) of the start of sample data of each track run in the fragment.
     */
    var trunDataPosition: LongArray

    /**
     * The number of samples contained by each track run in the fragment.
     */
    var trunLength: IntArray

    /**
     * The size of each sample in the fragment.
     */
    var sampleSizeTable: IntArray

    /** The composition time offset of each sample in the fragment, in microseconds.  */
    var sampleCompositionTimeOffsetUsTable: IntArray

    /** The decoding time of each sample in the fragment, in microseconds.  */
    var sampleDecodingTimeUsTable: LongArray

    /**
     * Indicates which samples are sync frames.
     */
    var sampleIsSyncFrameTable: BooleanArray

    /**
     * Whether the fragment defines encryption data.
     */
    var definesEncryptionData = false

    /**
     * If [.definesEncryptionData] is true, indicates which samples use sub-sample encryption.
     * Undefined otherwise.
     */
    var sampleHasSubsampleEncryptionTable: BooleanArray

    /** Fragment specific track encryption. May be null.  */
    var trackEncryptionBox: TrackEncryptionBox? = null

    /**
     * If [.definesEncryptionData] is true, contains binary sample encryption data. Undefined
     * otherwise.
     */
    val sampleEncryptionData: ParsableByteArray

    /**
     * Whether [.sampleEncryptionData] needs populating with the actual encryption data.
     */
    var sampleEncryptionDataNeedsFill = false

    /**
     * The duration of all the samples defined in the fragments up to and including this one, plus the
     * duration of the samples defined in the moov atom if [.nextFragmentDecodeTimeIncludesMoov]
     * is `true`.
     */
    var nextFragmentDecodeTime: Long = 0

    /**
     * Whether [.nextFragmentDecodeTime] includes the duration of the samples referred to by the
     * moov atom.
     */
    var nextFragmentDecodeTimeIncludesMoov = false

    /**
     * Resets the fragment.
     *
     *
     * [.sampleCount] and [.nextFragmentDecodeTime] are set to 0, and both
     * [.definesEncryptionData] and [.sampleEncryptionDataNeedsFill] is set to false,
     * and [.trackEncryptionBox] is set to null.
     */
    fun reset() {
        trunCount = 0
        nextFragmentDecodeTime = 0
        nextFragmentDecodeTimeIncludesMoov = false
        definesEncryptionData = false
        sampleEncryptionDataNeedsFill = false
        trackEncryptionBox = null
    }

    /**
     * Configures the fragment for the specified number of samples.
     *
     *
     * The [.sampleCount] of the fragment is set to the specified sample count, and the
     * contained tables are resized if necessary such that they are at least this length.
     *
     * @param sampleCount The number of samples in the new run.
     */
    fun initTables(trunCount: Int, sampleCount: Int) {
        this.trunCount = trunCount
        this.sampleCount = sampleCount
        if (trunLength.size < trunCount) {
            trunDataPosition = LongArray(trunCount)
            trunLength = IntArray(trunCount)
        }
        if (sampleSizeTable.size < sampleCount) {
            // Size the tables 25% larger than needed, so as to make future resize operations less
            // likely. The choice of 25% is relatively arbitrary.
            val tableSize = sampleCount * 125 / 100
            sampleSizeTable = IntArray(tableSize)
            sampleCompositionTimeOffsetUsTable = IntArray(tableSize)
            sampleDecodingTimeUsTable = LongArray(tableSize)
            sampleIsSyncFrameTable = BooleanArray(tableSize)
            sampleHasSubsampleEncryptionTable = BooleanArray(tableSize)
        }
    }

    /**
     * Configures the fragment to be one that defines encryption data of the specified length.
     *
     *
     * [.definesEncryptionData] is set to true, and the [ limit][ParsableByteArray.limit] of [.sampleEncryptionData] is set to the specified length.
     *
     * @param length The length in bytes of the encryption data.
     */
    fun initEncryptionData(length: Int) {
        sampleEncryptionData.reset(length)
        definesEncryptionData = true
        sampleEncryptionDataNeedsFill = true
    }

    /**
     * Fills [.sampleEncryptionData] from the provided input.
     *
     * @param input An [ExtractorInput] from which to read the encryption data.
     */
    @Throws(IOException::class)
    fun fillEncryptionData(input: ExtractorInput) {
        input.readFully(sampleEncryptionData.data, 0, sampleEncryptionData.limit())
        sampleEncryptionData.position = 0
        sampleEncryptionDataNeedsFill = false
    }

    /**
     * Fills [.sampleEncryptionData] from the provided source.
     *
     * @param source A source from which to read the encryption data.
     */
    fun fillEncryptionData(source: ParsableByteArray) {
        source.readBytes(sampleEncryptionData.data, 0, sampleEncryptionData.limit())
        sampleEncryptionData.position = 0
        sampleEncryptionDataNeedsFill = false
    }

    /**
     * Returns the sample presentation timestamp in microseconds.
     *
     * @param index The sample index.
     * @return The presentation timestamps of this sample in microseconds.
     */
    fun getSamplePresentationTimeUs(index: Int): Long {
        return sampleDecodingTimeUsTable[index] + sampleCompositionTimeOffsetUsTable[index]
    }

    /** Returns whether the sample at the given index has a subsample encryption table.  */
    fun sampleHasSubsampleEncryptionTable(index: Int): Boolean {
        return definesEncryptionData && sampleHasSubsampleEncryptionTable[index]
    }

    init {
        trunDataPosition = LongArray(0)
        trunLength = IntArray(0)
        sampleSizeTable = IntArray(0)
        sampleCompositionTimeOffsetUsTable = IntArray(0)
        sampleDecodingTimeUsTable = LongArray(0)
        sampleIsSyncFrameTable = BooleanArray(0)
        sampleHasSubsampleEncryptionTable = BooleanArray(0)
        sampleEncryptionData = ParsableByteArray()
    }
}