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
import com.google.android.exoplayer2.util.Util

/**
 * Rechunks fixed sample size media in which every sample is a key frame (e.g. uncompressed audio).
 */
/* package */
internal object FixedSampleSizeRechunker {
    /**
     * Maximum number of bytes for each buffer in rechunked output.
     */
    private const val MAX_SAMPLE_SIZE = 8 * 1024

    /**
     * Rechunk the given fixed sample size input to produce a new sequence of samples.
     *
     * @param fixedSampleSize Size in bytes of each sample.
     * @param chunkOffsets Chunk offsets in the MP4 stream to rechunk.
     * @param chunkSampleCounts Sample counts for each of the MP4 stream's chunks.
     * @param timestampDeltaInTimeUnits Timestamp delta between each sample in time units.
     */
    fun rechunk(
        fixedSampleSize: Int, chunkOffsets: LongArray, chunkSampleCounts: IntArray,
        timestampDeltaInTimeUnits: Long
    ): Results {
        val maxSampleCount = MAX_SAMPLE_SIZE / fixedSampleSize

        // Count the number of new, rechunked buffers.
        var rechunkedSampleCount = 0
        for (chunkSampleCount in chunkSampleCounts) {
            rechunkedSampleCount += Util.ceilDivide(chunkSampleCount, maxSampleCount)
        }
        val offsets = LongArray(rechunkedSampleCount)
        val sizes = IntArray(rechunkedSampleCount)
        var maximumSize = 0
        val timestamps = LongArray(rechunkedSampleCount)
        val flags = IntArray(rechunkedSampleCount)
        var originalSampleIndex = 0
        var newSampleIndex = 0
        for (chunkIndex in chunkSampleCounts.indices) {
            var chunkSamplesRemaining = chunkSampleCounts[chunkIndex]
            var sampleOffset = chunkOffsets[chunkIndex]
            while (chunkSamplesRemaining > 0) {
                val bufferSampleCount = Math.min(maxSampleCount, chunkSamplesRemaining)
                offsets[newSampleIndex] = sampleOffset
                sizes[newSampleIndex] = fixedSampleSize * bufferSampleCount
                maximumSize = Math.max(maximumSize, sizes[newSampleIndex])
                timestamps[newSampleIndex] = timestampDeltaInTimeUnits * originalSampleIndex
                flags[newSampleIndex] = C.BUFFER_FLAG_KEY_FRAME
                sampleOffset += sizes[newSampleIndex]
                originalSampleIndex += bufferSampleCount
                chunkSamplesRemaining -= bufferSampleCount
                newSampleIndex++
            }
        }
        val duration = timestampDeltaInTimeUnits * originalSampleIndex
        return Results(offsets, sizes, maximumSize, timestamps, flags, duration)
    }

    /**
     * The result of a rechunking operation.
     */
    class Results internal constructor(
        val offsets: LongArray,
        val sizes: IntArray,
        val maximumSize: Int,
        val timestamps: LongArray,
        val flags: IntArray,
        val duration: Long
    )
}