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

import androidx.annotation.IntDef
import com.google.android.exoplayer2.Format

/**
 * Encapsulates information describing an MP4 track.
 */
class Track(
    /**
     * The track identifier.
     */
    val id: Int,
    /**
     * One of [C.TRACK_TYPE_AUDIO], [C.TRACK_TYPE_VIDEO] and [C.TRACK_TYPE_TEXT].
     */
    val type: Int,
    /**
     * The track timescale, defined as the number of time units that pass in one second.
     */
    val timescale: Long,
    /**
     * The movie timescale.
     */
    val movieTimescale: Long,
    /**
     * The duration of the track in microseconds, or [C.TIME_UNSET] if unknown.
     */
    val durationUs: Long,
    /**
     * The format.
     */
    val format: Format,
    /**
     * One of `TRANSFORMATION_*`. Defines the transformation to apply before outputting each
     * sample.
     */
    @field:Transformation @param:Transformation val sampleTransformation: Int,
    private val sampleDescriptionEncryptionBoxes: Array<TrackEncryptionBox?>?,
    /**
     * For H264 video tracks, the length in bytes of the NALUnitLength field in each sample. 0 for
     * other track types.
     */
    val nalUnitLengthFieldLength: Int,
    /**
     * Durations of edit list segments in the movie timescale. Null if there is no edit list.
     */
    val editListDurations: LongArray?,
    /**
     * Media times for edit list segments in the track timescale. Null if there is no edit list.
     */
    val editListMediaTimes: LongArray?
) {
    /**
     * The transformation to apply to samples in the track, if any. One of [ ][.TRANSFORMATION_NONE] or [.TRANSFORMATION_CEA608_CDAT].
     */
    @MustBeDocumented
    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    @IntDef(TRANSFORMATION_NONE, TRANSFORMATION_CEA608_CDAT)
    annotation class Transformation

    /**
     * Returns the [TrackEncryptionBox] for the given sample description index.
     *
     * @param sampleDescriptionIndex The given sample description index
     * @return The [TrackEncryptionBox] for the given sample description index. Maybe null if no
     * such entry exists.
     */
    fun getSampleDescriptionEncryptionBox(sampleDescriptionIndex: Int): TrackEncryptionBox? {
        return sampleDescriptionEncryptionBoxes?.get(sampleDescriptionIndex)
    }

    fun copyWithFormat(format: Format): Track {
        return Track(
            id,
            type,
            timescale,
            movieTimescale,
            durationUs,
            format,
            sampleTransformation,
            sampleDescriptionEncryptionBoxes,
            nalUnitLengthFieldLength,
            editListDurations,
            editListMediaTimes
        )
    }

    companion object {
        /**
         * A no-op sample transformation.
         */
        const val TRANSFORMATION_NONE = 0

        /**
         * A transformation for caption samples in cdat atoms.
         */
        const val TRANSFORMATION_CEA608_CDAT = 1
    }
}