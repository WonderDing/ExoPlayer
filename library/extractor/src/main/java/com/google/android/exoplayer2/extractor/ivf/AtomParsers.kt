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

import android.util.Pair
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.C.PcmEncoding
import com.google.android.exoplayer2.C.StereoMode
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.ParserException
import com.google.android.exoplayer2.audio.AacUtil
import com.google.android.exoplayer2.audio.Ac3Util
import com.google.android.exoplayer2.audio.Ac4Util
import com.google.android.exoplayer2.audio.OpusUtil
import com.google.android.exoplayer2.drm.DrmInitData
import com.google.android.exoplayer2.extractor.GaplessInfoHolder
import com.google.android.exoplayer2.extractor.ivf.Atom.Companion.parseFullAtomVersion
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.mp4.SmtaMetadataEntry
import com.google.android.exoplayer2.util.*
import com.google.android.exoplayer2.video.AvcConfig
import com.google.android.exoplayer2.video.DolbyVisionConfig
import com.google.android.exoplayer2.video.HevcConfig
import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import java.util.*

/** Utility methods for parsing MP4 format atom payloads according to ISO/IEC 14496-12.  */
internal object AtomParsers {
    private const val TAG = "AtomParsers"
    private const val TYPE_vide = 0x76696465
    private const val TYPE_soun = 0x736f756e
    private const val TYPE_text = 0x74657874
    private const val TYPE_sbtl = 0x7362746c
    private const val TYPE_subt = 0x73756274
    private const val TYPE_clcp = 0x636c6370
    private const val TYPE_meta = 0x6d657461
    private const val TYPE_mdta = 0x6d647461

    /**
     * The threshold number of samples to trim from the start/end of an audio track when applying an
     * edit below which gapless info can be used (rather than removing samples from the sample table).
     */
    private const val MAX_GAPLESS_TRIM_SIZE_SAMPLES = 4

    /** The magic signature for an Opus Identification header, as defined in RFC-7845.  */
    private val opusMagic = Util.getUtf8Bytes("OpusHead")

    /**
     * Parse the trak atoms in a moov atom (defined in ISO/IEC 14496-12).
     *
     * @param moov Moov atom to decode.
     * @param gaplessInfoHolder Holder to populate with gapless playback information.
     * @param duration The duration in units of the timescale declared in the mvhd atom, or [     ][C.TIME_UNSET] if the duration should be parsed from the tkhd atom.
     * @param drmInitData [DrmInitData] to be included in the format, or `null`.
     * @param ignoreEditLists Whether to ignore any edit lists in the trak boxes.
     * @param isQuickTime True for QuickTime media. False otherwise.
     * @param modifyTrackFunction A function to apply to the [Tracks][Track] in the result.
     * @return A list of [TrackSampleTable] instances.
     * @throws ParserException Thrown if the trak atoms can't be parsed.
     */
    @JvmStatic
    @Throws(ParserException::class)
    fun parseTraks(
        moov: Atom.ContainerAtom,
        gaplessInfoHolder: GaplessInfoHolder,
        duration: Long,
        drmInitData: DrmInitData?,
        ignoreEditLists: Boolean,
        isQuickTime: Boolean,
        modifyTrackFunction: Function<Track?, Track?>
    ): List<TrackSampleTable> {
        val trackSampleTables: MutableList<TrackSampleTable> = ArrayList()
        for (i in moov.containerChildren.indices) {
            val atom = moov.containerChildren[i]
            if (atom.type != Atom.TYPE_trak) {
                continue
            }
            val track = modifyTrackFunction.apply(
                parseTrak(
                    atom,
                    Assertions.checkNotNull(moov.getLeafAtomOfType(Atom.TYPE_mvhd)),
                    duration,
                    drmInitData,
                    ignoreEditLists,
                    isQuickTime
                )
            ) ?: continue
            val stblAtom = Assertions.checkNotNull(
                Assertions.checkNotNull(
                    Assertions.checkNotNull(atom.getContainerAtomOfType(Atom.TYPE_mdia))
                        .getContainerAtomOfType(Atom.TYPE_minf)
                )
                    .getContainerAtomOfType(Atom.TYPE_stbl)
            )
            val trackSampleTable = parseStbl(track, stblAtom, gaplessInfoHolder)
            trackSampleTables.add(trackSampleTable)
        }
        return trackSampleTables
    }

    /**
     * Parses a udta atom.
     *
     * @param udtaAtom The udta (user data) atom to decode.
     * @return A [Pair] containing the metadata from the meta child atom as first value (if
     * any), and the metadata from the smta child atom as second value (if any).
     */
    fun parseUdta(
        udtaAtom: Atom.LeafAtom
    ): Pair<Metadata, Metadata> {
        val udtaData = udtaAtom.data
        udtaData.position = Atom.HEADER_SIZE
        var metaMetadata: Metadata? = null
        var smtaMetadata: Metadata? = null
        while (udtaData.bytesLeft() >= Atom.HEADER_SIZE) {
            val atomPosition = udtaData.position
            val atomSize = udtaData.readInt()
            val atomType = udtaData.readInt()
            if (atomType == Atom.TYPE_meta) {
                udtaData.position = atomPosition
                metaMetadata = parseUdtaMeta(udtaData, atomPosition + atomSize)
            } else if (atomType == Atom.TYPE_smta) {
                udtaData.position = atomPosition
                smtaMetadata = parseSmta(udtaData, atomPosition + atomSize)
            }
            udtaData.position = atomPosition + atomSize
        }
        return Pair.create(metaMetadata, smtaMetadata)
    }

    /**
     * Parses a metadata meta atom if it contains metadata with handler 'mdta'.
     *
     * @param meta The metadata atom to decode.
     * @return Parsed metadata, or null.
     */
    fun parseMdtaFromMeta(meta: Atom.ContainerAtom): Metadata? {
        val hdlrAtom = meta.getLeafAtomOfType(Atom.TYPE_hdlr)
        val keysAtom = meta.getLeafAtomOfType(Atom.TYPE_keys)
        val ilstAtom = meta.getLeafAtomOfType(Atom.TYPE_ilst)
        if (hdlrAtom == null || keysAtom == null || ilstAtom == null || parseHdlr(hdlrAtom.data) != TYPE_mdta) {
            // There isn't enough information to parse the metadata, or the handler type is unexpected.
            return null
        }

        // Parse metadata keys.
        val keys = keysAtom.data
        keys.position = Atom.FULL_HEADER_SIZE
        val entryCount = keys.readInt()
        val keyNames = arrayOfNulls<String>(entryCount)
        for (i in 0 until entryCount) {
            val entrySize = keys.readInt()
            keys.skipBytes(4) // keyNamespace
            val keySize = entrySize - 8
            keyNames[i] = keys.readString(keySize)
        }

        // Parse metadata items.
        val ilst = ilstAtom.data
        ilst.position = Atom.HEADER_SIZE
        val entries = ArrayList<Metadata.Entry>()
        while (ilst.bytesLeft() > Atom.HEADER_SIZE) {
            val atomPosition = ilst.position
            val atomSize = ilst.readInt()
            val keyIndex = ilst.readInt() - 1
            if (keyIndex >= 0 && keyIndex < keyNames.size) {
                val key = keyNames[keyIndex]
                val entry: Metadata.Entry? =
                    MetadataUtil.parseMdtaMetadataEntryFromIlst(ilst, atomPosition + atomSize, key!!)
                if (entry != null) {
                    entries.add(entry)
                }
            } else {
                Log.w(TAG, "Skipped metadata with unknown key index: $keyIndex")
            }
            ilst.position = atomPosition + atomSize
        }
        return if (entries.isEmpty()) null else Metadata(entries)
    }

    /**
     * Possibly skips the version and flags fields (1+3 byte) of a full meta atom.
     *
     *
     * Atoms of type [Atom.TYPE_meta] are defined to be full atoms which have four additional
     * bytes for a version and a flags field (see 4.2 'Object Structure' in ISO/IEC 14496-12:2005).
     * QuickTime do not have such a full box structure. Since some of these files are encoded wrongly,
     * we can't rely on the file type though. Instead we must check the 8 bytes after the common
     * header bytes ourselves.
     *
     * @param meta The 8 or more bytes following the meta atom size and type.
     */
    fun maybeSkipRemainingMetaAtomHeaderBytes(meta: ParsableByteArray) {
        var endPosition = meta.position
        // The next 8 bytes can be either:
        // (iso) [1 byte version + 3 bytes flags][4 byte size of next atom]
        // (qt)  [4 byte size of next atom      ][4 byte hdlr atom type   ]
        // In case of (iso) we need to skip the next 4 bytes.
        meta.skipBytes(4)
        if (meta.readInt() != Atom.TYPE_hdlr) {
            endPosition += 4
        }
        meta.position = endPosition
    }

    /**
     * Parses a trak atom (defined in ISO/IEC 14496-12).
     *
     * @param trak Atom to decode.
     * @param mvhd Movie header atom, used to get the timescale.
     * @param duration The duration in units of the timescale declared in the mvhd atom, or [     ][C.TIME_UNSET] if the duration should be parsed from the tkhd atom.
     * @param drmInitData [DrmInitData] to be included in the format, or `null`.
     * @param ignoreEditLists Whether to ignore any edit lists in the trak box.
     * @param isQuickTime True for QuickTime media. False otherwise.
     * @return A [Track] instance, or `null` if the track's type isn't supported.
     * @throws ParserException Thrown if the trak atom can't be parsed.
     */
    @Throws(ParserException::class)
    private fun parseTrak(
        trak: Atom.ContainerAtom,
        mvhd: Atom.LeafAtom,
        duration: Long,
        drmInitData: DrmInitData?,
        ignoreEditLists: Boolean,
        isQuickTime: Boolean
    ): Track? {
        var duration = duration
        val mdia = Assertions.checkNotNull(trak.getContainerAtomOfType(Atom.TYPE_mdia))
        val trackType =
            getTrackTypeForHdlr(parseHdlr(Assertions.checkNotNull(mdia.getLeafAtomOfType(Atom.TYPE_hdlr)).data))
        if (trackType == C.TRACK_TYPE_UNKNOWN) {
            return null
        }
        val tkhdData = parseTkhd(Assertions.checkNotNull(trak.getLeafAtomOfType(Atom.TYPE_tkhd)).data)
        if (duration == C.TIME_UNSET) {
            duration = tkhdData.duration
        }
        val movieTimescale = parseMvhd(mvhd.data)
        val durationUs: Long
        durationUs = if (duration == C.TIME_UNSET) {
            C.TIME_UNSET
        } else {
            Util.scaleLargeTimestamp(
                duration,
                C.MICROS_PER_SECOND,
                movieTimescale
            )
        }
        val stbl = Assertions.checkNotNull(
            Assertions.checkNotNull(mdia.getContainerAtomOfType(Atom.TYPE_minf))
                .getContainerAtomOfType(Atom.TYPE_stbl)
        )
        val mdhdData = parseMdhd(Assertions.checkNotNull(mdia.getLeafAtomOfType(Atom.TYPE_mdhd)).data)
        val stsdData = parseStsd(
            Assertions.checkNotNull(stbl.getLeafAtomOfType(Atom.TYPE_stsd)).data,
            tkhdData.id,
            tkhdData.rotationDegrees,
            mdhdData.second,
            drmInitData,
            isQuickTime
        )
        var editListDurations: LongArray? = null
        var editListMediaTimes: LongArray? = null
        if (!ignoreEditLists) {
            val edtsAtom = trak.getContainerAtomOfType(Atom.TYPE_edts)
            if (edtsAtom != null) {
                val edtsData = parseEdts(edtsAtom)
                if (edtsData != null) {
                    editListDurations = edtsData.first
                    editListMediaTimes = edtsData.second
                }
            }
        }
        return if (stsdData.format == null) null else Track(
            tkhdData.id, trackType, mdhdData.first, movieTimescale, durationUs,
            stsdData.format ?: return null, stsdData.requiredSampleTransformation, stsdData.trackEncryptionBoxes,
            stsdData.nalUnitLengthFieldLength, editListDurations, editListMediaTimes
        )
    }

    /**
     * Parses an stbl atom (defined in ISO/IEC 14496-12).
     *
     * @param track Track to which this sample table corresponds.
     * @param stblAtom stbl (sample table) atom to decode.
     * @param gaplessInfoHolder Holder to populate with gapless playback information.
     * @return Sample table described by the stbl atom.
     * @throws ParserException Thrown if the stbl atom can't be parsed.
     */
    @Throws(ParserException::class)
    private fun parseStbl(
        track: Track, stblAtom: Atom.ContainerAtom, gaplessInfoHolder: GaplessInfoHolder
    ): TrackSampleTable {
        val sampleSizeBox: SampleSizeBox
        val stszAtom = stblAtom.getLeafAtomOfType(Atom.TYPE_stsz)
        sampleSizeBox = if (stszAtom != null) {
            StszSampleSizeBox(stszAtom, track.format)
        } else {
            val stz2Atom = stblAtom.getLeafAtomOfType(Atom.TYPE_stz2)
                ?: throw ParserException("Track has no sample table size information")
            Stz2SampleSizeBox(stz2Atom)
        }
        var sampleCount = sampleSizeBox.sampleCount
        if (sampleCount == 0) {
            return TrackSampleTable(
                track, LongArray(0), IntArray(0),  /* maximumSize= */
                0, LongArray(0), IntArray(0),  /* durationUs= */
                0
            )
        }

        // Entries are byte offsets of chunks.
        var chunkOffsetsAreLongs = false
        var chunkOffsetsAtom = stblAtom.getLeafAtomOfType(Atom.TYPE_stco)
        if (chunkOffsetsAtom == null) {
            chunkOffsetsAreLongs = true
            chunkOffsetsAtom = Assertions.checkNotNull(stblAtom.getLeafAtomOfType(Atom.TYPE_co64))
        }
        val chunkOffsets = chunkOffsetsAtom.data
        // Entries are (chunk number, number of samples per chunk, sample description index).
        val stsc = Assertions.checkNotNull(stblAtom.getLeafAtomOfType(Atom.TYPE_stsc)).data
        // Entries are (number of samples, timestamp delta between those samples).
        val stts = Assertions.checkNotNull(stblAtom.getLeafAtomOfType(Atom.TYPE_stts)).data
        // Entries are the indices of samples that are synchronization samples.
        val stssAtom = stblAtom.getLeafAtomOfType(Atom.TYPE_stss)
        var stss = stssAtom?.data
        // Entries are (number of samples, timestamp offset).
        val cttsAtom = stblAtom.getLeafAtomOfType(Atom.TYPE_ctts)
        val ctts = cttsAtom?.data

        // Prepare to read chunk information.
        val chunkIterator = ChunkIterator(stsc, chunkOffsets, chunkOffsetsAreLongs)

        // Prepare to read sample timestamps.
        stts.position = Atom.FULL_HEADER_SIZE
        var remainingTimestampDeltaChanges = stts.readUnsignedIntToInt() - 1
        var remainingSamplesAtTimestampDelta = stts.readUnsignedIntToInt()
        var timestampDeltaInTimeUnits = stts.readUnsignedIntToInt()

        // Prepare to read sample timestamp offsets, if ctts is present.
        var remainingSamplesAtTimestampOffset = 0
        var remainingTimestampOffsetChanges = 0
        var timestampOffset = 0
        if (ctts != null) {
            ctts.position = Atom.FULL_HEADER_SIZE
            remainingTimestampOffsetChanges = ctts.readUnsignedIntToInt()
        }
        var nextSynchronizationSampleIndex = C.INDEX_UNSET
        var remainingSynchronizationSamples = 0
        if (stss != null) {
            stss.position = Atom.FULL_HEADER_SIZE
            remainingSynchronizationSamples = stss.readUnsignedIntToInt()
            if (remainingSynchronizationSamples > 0) {
                nextSynchronizationSampleIndex = stss.readUnsignedIntToInt() - 1
            } else {
                // Ignore empty stss boxes, which causes all samples to be treated as sync samples.
                stss = null
            }
        }

        // Fixed sample size raw audio may need to be rechunked.
        val fixedSampleSize = sampleSizeBox.fixedSampleSize
        val sampleMimeType = track.format?.sampleMimeType
        val rechunkFixedSizeSamples =
            (fixedSampleSize != C.LENGTH_UNSET && (MimeTypes.AUDIO_RAW == sampleMimeType || MimeTypes.AUDIO_MLAW == sampleMimeType || MimeTypes.AUDIO_ALAW == sampleMimeType)
                    && remainingTimestampDeltaChanges == 0 && remainingTimestampOffsetChanges == 0 && remainingSynchronizationSamples == 0)
        var offsets: LongArray
        var sizes: IntArray
        var maximumSize = 0
        var timestamps: LongArray
        var flags: IntArray
        var timestampTimeUnits: Long = 0
        val duration: Long
        if (rechunkFixedSizeSamples) {
            val chunkOffsetsBytes = LongArray(chunkIterator.length)
            val chunkSampleCounts = IntArray(chunkIterator.length)
            while (chunkIterator.moveNext()) {
                chunkOffsetsBytes[chunkIterator.index] = chunkIterator.offset
                chunkSampleCounts[chunkIterator.index] = chunkIterator.numSamples
            }
            val rechunkedResults = FixedSampleSizeRechunker.rechunk(
                fixedSampleSize, chunkOffsetsBytes, chunkSampleCounts, timestampDeltaInTimeUnits.toLong()
            )
            offsets = rechunkedResults.offsets
            sizes = rechunkedResults.sizes
            maximumSize = rechunkedResults.maximumSize
            timestamps = rechunkedResults.timestamps
            flags = rechunkedResults.flags
            duration = rechunkedResults.duration
        } else {
            offsets = LongArray(sampleCount)
            sizes = IntArray(sampleCount)
            timestamps = LongArray(sampleCount)
            flags = IntArray(sampleCount)
            var offset: Long = 0
            var remainingSamplesInChunk = 0
            for (i in 0 until sampleCount) {
                // Advance to the next chunk if necessary.
                var chunkDataComplete = true
                while (remainingSamplesInChunk == 0 && chunkIterator.moveNext().also { chunkDataComplete = it }) {
                    offset = chunkIterator.offset
                    remainingSamplesInChunk = chunkIterator.numSamples
                }
                if (!chunkDataComplete) {
                    Log.w(TAG, "Unexpected end of chunk data")
                    sampleCount = i
                    offsets = Arrays.copyOf(offsets, sampleCount)
                    sizes = Arrays.copyOf(sizes, sampleCount)
                    timestamps = Arrays.copyOf(timestamps, sampleCount)
                    flags = Arrays.copyOf(flags, sampleCount)
                    break
                }

                // Add on the timestamp offset if ctts is present.
                if (ctts != null) {
                    while (remainingSamplesAtTimestampOffset == 0 && remainingTimestampOffsetChanges > 0) {
                        remainingSamplesAtTimestampOffset = ctts.readUnsignedIntToInt()
                        // The BMFF spec (ISO/IEC 14496-12) states that sample offsets should be unsigned
                        // integers in version 0 ctts boxes, however some streams violate the spec and use
                        // signed integers instead. It's safe to always decode sample offsets as signed integers
                        // here, because unsigned integers will still be parsed correctly (unless their top bit
                        // is set, which is never true in practice because sample offsets are always small).
                        timestampOffset = ctts.readInt()
                        remainingTimestampOffsetChanges--
                    }
                    remainingSamplesAtTimestampOffset--
                }
                offsets[i] = offset
                sizes[i] = sampleSizeBox.readNextSampleSize()
                if (sizes[i] > maximumSize) {
                    maximumSize = sizes[i]
                }
                timestamps[i] = timestampTimeUnits + timestampOffset

                // All samples are synchronization samples if the stss is not present.
                flags[i] = if (stss == null) C.BUFFER_FLAG_KEY_FRAME else 0
                if (i == nextSynchronizationSampleIndex) {
                    flags[i] = C.BUFFER_FLAG_KEY_FRAME
                    remainingSynchronizationSamples--
                    if (remainingSynchronizationSamples > 0) {
                        nextSynchronizationSampleIndex = Assertions.checkNotNull(stss).readUnsignedIntToInt() - 1
                    }
                }

                // Add on the duration of this sample.
                timestampTimeUnits += timestampDeltaInTimeUnits.toLong()
                remainingSamplesAtTimestampDelta--
                if (remainingSamplesAtTimestampDelta == 0 && remainingTimestampDeltaChanges > 0) {
                    remainingSamplesAtTimestampDelta = stts.readUnsignedIntToInt()
                    // The BMFF spec (ISO/IEC 14496-12) states that sample deltas should be unsigned integers
                    // in stts boxes, however some streams violate the spec and use signed integers instead.
                    // See https://github.com/google/ExoPlayer/issues/3384. It's safe to always decode sample
                    // deltas as signed integers here, because unsigned integers will still be parsed
                    // correctly (unless their top bit is set, which is never true in practice because sample
                    // deltas are always small).
                    timestampDeltaInTimeUnits = stts.readInt()
                    remainingTimestampDeltaChanges--
                }
                offset += sizes[i]
                remainingSamplesInChunk--
            }
            duration = timestampTimeUnits + timestampOffset

            // If the stbl's child boxes are not consistent the container is malformed, but the stream may
            // still be playable.
            var isCttsValid = true
            if (ctts != null) {
                while (remainingTimestampOffsetChanges > 0) {
                    if (ctts.readUnsignedIntToInt() != 0) {
                        isCttsValid = false
                        break
                    }
                    ctts.readInt() // Ignore offset.
                    remainingTimestampOffsetChanges--
                }
            }
            if (remainingSynchronizationSamples != 0 || remainingSamplesAtTimestampDelta != 0 || remainingSamplesInChunk != 0 || remainingTimestampDeltaChanges != 0 || remainingSamplesAtTimestampOffset != 0 || !isCttsValid) {
                Log.w(
                    TAG,
                    "Inconsistent stbl box for track "
                            + track.id
                            + ": remainingSynchronizationSamples "
                            + remainingSynchronizationSamples
                            + ", remainingSamplesAtTimestampDelta "
                            + remainingSamplesAtTimestampDelta
                            + ", remainingSamplesInChunk "
                            + remainingSamplesInChunk
                            + ", remainingTimestampDeltaChanges "
                            + remainingTimestampDeltaChanges
                            + ", remainingSamplesAtTimestampOffset "
                            + remainingSamplesAtTimestampOffset
                            + if (!isCttsValid) ", ctts invalid" else ""
                )
            }
        }
        var durationUs = Util.scaleLargeTimestamp(duration, C.MICROS_PER_SECOND, track.timescale)
        if (track.editListDurations == null) {
            Util.scaleLargeTimestampsInPlace(timestamps, C.MICROS_PER_SECOND, track.timescale)
            return TrackSampleTable(
                track, offsets, sizes, maximumSize, timestamps, flags, durationUs
            )
        }

        // See the BMFF spec (ISO/IEC 14496-12) subsection 8.6.6. Edit lists that require prerolling
        // from a sync sample after reordering are not supported. Partial audio sample truncation is
        // only supported in edit lists with one edit that removes less than
        // MAX_GAPLESS_TRIM_SIZE_SAMPLES samples from the start/end of the track. This implementation
        // handles simple discarding/delaying of samples. The extractor may place further restrictions
        // on what edited streams are playable.
        if (track.editListDurations.size == 1 && track.type == C.TRACK_TYPE_AUDIO && timestamps.size >= 2) {
            val editStartTime = Assertions.checkNotNull(track.editListMediaTimes)[0]
            val editEndTime = editStartTime + Util.scaleLargeTimestamp(
                track.editListDurations[0],
                track.timescale, track.movieTimescale
            )
            if (canApplyEditWithGaplessInfo(timestamps, duration, editStartTime, editEndTime)) {
                val paddingTimeUnits = duration - editEndTime
                val encoderDelay = Util.scaleLargeTimestamp(
                    editStartTime - timestamps[0],
                    track.format.sampleRate.toLong(), track.timescale
                )
                val encoderPadding = Util.scaleLargeTimestamp(
                    paddingTimeUnits,
                    track.format.sampleRate.toLong(), track.timescale
                )
                if ((encoderDelay != 0L || encoderPadding != 0L) && encoderDelay <= Int.MAX_VALUE && encoderPadding <= Int.MAX_VALUE) {
                    gaplessInfoHolder.encoderDelay = encoderDelay.toInt()
                    gaplessInfoHolder.encoderPadding = encoderPadding.toInt()
                    Util.scaleLargeTimestampsInPlace(timestamps, C.MICROS_PER_SECOND, track.timescale)
                    val editedDurationUs = Util.scaleLargeTimestamp(
                        track.editListDurations[0], C.MICROS_PER_SECOND, track.movieTimescale
                    )
                    return TrackSampleTable(
                        track, offsets, sizes, maximumSize, timestamps, flags, editedDurationUs
                    )
                }
            }
        }
        if (track.editListDurations.size == 1 && track.editListDurations[0] == 0L) {
            // The current version of the spec leaves handling of an edit with zero segment_duration in
            // unfragmented files open to interpretation. We handle this as a special case and include all
            // samples in the edit.
            val editStartTime = Assertions.checkNotNull(track.editListMediaTimes)[0]
            for (i in timestamps.indices) {
                timestamps[i] = Util.scaleLargeTimestamp(
                    timestamps[i] - editStartTime, C.MICROS_PER_SECOND, track.timescale
                )
            }
            durationUs = Util.scaleLargeTimestamp(duration - editStartTime, C.MICROS_PER_SECOND, track.timescale)
            return TrackSampleTable(
                track, offsets, sizes, maximumSize, timestamps, flags, durationUs
            )
        }

        // Omit any sample at the end point of an edit for audio tracks.
        val omitClippedSample = track.type == C.TRACK_TYPE_AUDIO

        // Count the number of samples after applying edits.
        var editedSampleCount = 0
        var nextSampleIndex = 0
        var copyMetadata = false
        val startIndices = IntArray(track.editListDurations.size)
        val endIndices = IntArray(track.editListDurations.size)
        val editListMediaTimes = Assertions.checkNotNull(track.editListMediaTimes)
        for (i in track.editListDurations.indices) {
            val editMediaTime = editListMediaTimes[i]
            if (editMediaTime != -1L) {
                val editDuration = Util.scaleLargeTimestamp(
                    track.editListDurations[i], track.timescale, track.movieTimescale
                )
                startIndices[i] = Util.binarySearchFloor(
                    timestamps, editMediaTime,  /* inclusive= */true,  /* stayInBounds= */true
                )
                endIndices[i] = Util.binarySearchCeil(
                    timestamps,
                    editMediaTime + editDuration,  /* inclusive= */
                    omitClippedSample,  /* stayInBounds= */
                    false
                )
                while (startIndices[i] < endIndices[i]
                    && flags[startIndices[i]] and C.BUFFER_FLAG_KEY_FRAME == 0
                ) {
                    // Applying the edit correctly would require prerolling from the previous sync sample. In
                    // the current implementation we advance to the next sync sample instead. Only other
                    // tracks (i.e. audio) will be rendered until the time of the first sync sample.
                    // See https://github.com/google/ExoPlayer/issues/1659.
                    startIndices[i]++
                }
                editedSampleCount += endIndices[i] - startIndices[i]
                copyMetadata = copyMetadata or (nextSampleIndex != startIndices[i])
                nextSampleIndex = endIndices[i]
            }
        }
        copyMetadata = copyMetadata or (editedSampleCount != sampleCount)

        // Calculate edited sample timestamps and update the corresponding metadata arrays.
        val editedOffsets = if (copyMetadata) LongArray(editedSampleCount) else offsets
        val editedSizes = if (copyMetadata) IntArray(editedSampleCount) else sizes
        var editedMaximumSize = if (copyMetadata) 0 else maximumSize
        val editedFlags = if (copyMetadata) IntArray(editedSampleCount) else flags
        val editedTimestamps = LongArray(editedSampleCount)
        var pts: Long = 0
        var sampleIndex = 0
        for (i in track.editListDurations.indices) {
            val editMediaTime = track.editListMediaTimes!![i]
            val startIndex = startIndices[i]
            val endIndex = endIndices[i]
            if (copyMetadata) {
                val count = endIndex - startIndex
                System.arraycopy(offsets, startIndex, editedOffsets, sampleIndex, count)
                System.arraycopy(sizes, startIndex, editedSizes, sampleIndex, count)
                System.arraycopy(flags, startIndex, editedFlags, sampleIndex, count)
            }
            for (j in startIndex until endIndex) {
                val ptsUs = Util.scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale)
                val timeInSegmentUs = Util.scaleLargeTimestamp(
                    Math.max(0, timestamps[j] - editMediaTime), C.MICROS_PER_SECOND, track.timescale
                )
                editedTimestamps[sampleIndex] = ptsUs + timeInSegmentUs
                if (copyMetadata && editedSizes[sampleIndex] > editedMaximumSize) {
                    editedMaximumSize = sizes[j]
                }
                sampleIndex++
            }
            pts += track.editListDurations[i]
        }
        val editedDurationUs = Util.scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale)
        return TrackSampleTable(
            track,
            editedOffsets,
            editedSizes,
            editedMaximumSize,
            editedTimestamps,
            editedFlags,
            editedDurationUs
        )
    }

    private fun parseUdtaMeta(meta: ParsableByteArray, limit: Int): Metadata? {
        meta.skipBytes(Atom.HEADER_SIZE)
        maybeSkipRemainingMetaAtomHeaderBytes(meta)
        while (meta.position < limit) {
            val atomPosition = meta.position
            val atomSize = meta.readInt()
            val atomType = meta.readInt()
            if (atomType == Atom.TYPE_ilst) {
                meta.position = atomPosition
                return parseIlst(meta, atomPosition + atomSize)
            }
            meta.position = atomPosition + atomSize
        }
        return null
    }

    private fun parseIlst(ilst: ParsableByteArray, limit: Int): Metadata? {
        ilst.skipBytes(Atom.HEADER_SIZE)
        val entries = ArrayList<Metadata.Entry>()
        while (ilst.position < limit) {
            val entry = MetadataUtil.parseIlstElement(ilst)
            if (entry != null) {
                entries.add(entry)
            }
        }
        return if (entries.isEmpty()) null else Metadata(entries)
    }

    /**
     * Parses metadata from a Samsung smta atom.
     *
     *
     * See [Internal: b/150138465#comment76].
     */
    private fun parseSmta(smta: ParsableByteArray, limit: Int): Metadata? {
        smta.skipBytes(Atom.FULL_HEADER_SIZE)
        while (smta.position < limit) {
            val atomPosition = smta.position
            val atomSize = smta.readInt()
            val atomType = smta.readInt()
            if (atomType == Atom.TYPE_saut) {
                if (atomSize < 14) {
                    return null
                }
                smta.skipBytes(5) // author (4), reserved = 0 (1).
                val recordingMode = smta.readUnsignedByte()
                if (recordingMode != 12 && recordingMode != 13) {
                    return null
                }
                val captureFrameRate: Float = if (recordingMode == 12) 240F else 120.toFloat()
                smta.skipBytes(1) // reserved = 1 (1).
                val svcTemporalLayerCount = smta.readUnsignedByte()
                return Metadata(SmtaMetadataEntry(captureFrameRate, svcTemporalLayerCount))
            }
            smta.position = atomPosition + atomSize
        }
        return null
    }

    /**
     * Parses a mvhd atom (defined in ISO/IEC 14496-12), returning the timescale for the movie.
     *
     * @param mvhd Contents of the mvhd atom to be parsed.
     * @return Timescale for the movie.
     */
    private fun parseMvhd(mvhd: ParsableByteArray): Long {
        mvhd.position = Atom.HEADER_SIZE
        val fullAtom = mvhd.readInt()
        val version = parseFullAtomVersion(fullAtom)
        mvhd.skipBytes(if (version == 0) 8 else 16)
        return mvhd.readUnsignedInt()
    }

    /**
     * Parses a tkhd atom (defined in ISO/IEC 14496-12).
     *
     * @return An object containing the parsed data.
     */
    private fun parseTkhd(tkhd: ParsableByteArray): TkhdData {
        tkhd.position = Atom.HEADER_SIZE
        val fullAtom = tkhd.readInt()
        val version = parseFullAtomVersion(fullAtom)
        tkhd.skipBytes(if (version == 0) 8 else 16)
        val trackId = tkhd.readInt()
        tkhd.skipBytes(4)
        var durationUnknown = true
        val durationPosition = tkhd.position
        val durationByteCount = if (version == 0) 4 else 8
        for (i in 0 until durationByteCount) {
            if (tkhd.data[durationPosition + i] != (-1).toByte()) {
                durationUnknown = false
                break
            }
        }
        var duration: Long
        if (durationUnknown) {
            tkhd.skipBytes(durationByteCount)
            duration = C.TIME_UNSET
        } else {
            duration = if (version == 0) tkhd.readUnsignedInt() else tkhd.readUnsignedLongToLong()
            if (duration == 0L) {
                // 0 duration normally indicates that the file is fully fragmented (i.e. all of the media
                // samples are in fragments). Treat as unknown.
                duration = C.TIME_UNSET
            }
        }
        tkhd.skipBytes(16)
        val a00 = tkhd.readInt()
        val a01 = tkhd.readInt()
        tkhd.skipBytes(4)
        val a10 = tkhd.readInt()
        val a11 = tkhd.readInt()
        val rotationDegrees: Int
        val fixedOne = 65536
        rotationDegrees = if (a00 == 0 && a01 == fixedOne && a10 == -fixedOne && a11 == 0) {
            90
        } else if (a00 == 0 && a01 == -fixedOne && a10 == fixedOne && a11 == 0) {
            270
        } else if (a00 == -fixedOne && a01 == 0 && a10 == 0 && a11 == -fixedOne) {
            180
        } else {
            // Only 0, 90, 180 and 270 are supported. Treat anything else as 0.
            0
        }
        return TkhdData(trackId, duration, rotationDegrees)
    }

    /**
     * Parses an hdlr atom.
     *
     * @param hdlr The hdlr atom to decode.
     * @return The handler value.
     */
    private fun parseHdlr(hdlr: ParsableByteArray): Int {
        hdlr.position = Atom.FULL_HEADER_SIZE + 4
        return hdlr.readInt()
    }

    /** Returns the track type for a given handler value.  */
    private fun getTrackTypeForHdlr(hdlr: Int): Int {
        return if (hdlr == TYPE_soun) {
            C.TRACK_TYPE_AUDIO
        } else if (hdlr == TYPE_vide) {
            C.TRACK_TYPE_VIDEO
        } else if (hdlr == TYPE_text || hdlr == TYPE_sbtl || hdlr == TYPE_subt || hdlr == TYPE_clcp) {
            C.TRACK_TYPE_TEXT
        } else if (hdlr == TYPE_meta) {
            C.TRACK_TYPE_METADATA
        } else {
            C.TRACK_TYPE_UNKNOWN
        }
    }

    /**
     * Parses an mdhd atom (defined in ISO/IEC 14496-12).
     *
     * @param mdhd The mdhd atom to decode.
     * @return A pair consisting of the media timescale defined as the number of time units that pass
     * in one second, and the language code.
     */
    private fun parseMdhd(mdhd: ParsableByteArray): Pair<Long, String> {
        mdhd.position = Atom.HEADER_SIZE
        val fullAtom = mdhd.readInt()
        val version = parseFullAtomVersion(fullAtom)
        mdhd.skipBytes(if (version == 0) 8 else 16)
        val timescale = mdhd.readUnsignedInt()
        mdhd.skipBytes(if (version == 0) 4 else 8)
        val languageCode = mdhd.readUnsignedShort()
        val language = (""
                + ((languageCode shr 10 and 0x1F) + 0x60).toChar()
                + ((languageCode shr 5 and 0x1F) + 0x60).toChar()
                + ((languageCode and 0x1F) + 0x60).toChar())
        return Pair.create(timescale, language)
    }

    /**
     * Parses a stsd atom (defined in ISO/IEC 14496-12).
     *
     * @param stsd The stsd atom to decode.
     * @param trackId The track's identifier in its container.
     * @param rotationDegrees The rotation of the track in degrees.
     * @param language The language of the track.
     * @param drmInitData [DrmInitData] to be included in the format, or `null`.
     * @param isQuickTime True for QuickTime media. False otherwise.
     * @return An object containing the parsed data.
     */
    @Throws(ParserException::class)
    private fun parseStsd(
        stsd: ParsableByteArray,
        trackId: Int,
        rotationDegrees: Int,
        language: String,
        drmInitData: DrmInitData?,
        isQuickTime: Boolean
    ): StsdData {
        stsd.position = Atom.FULL_HEADER_SIZE
        val numberOfEntries = stsd.readInt()
        val out = StsdData(numberOfEntries)
        for (i in 0 until numberOfEntries) {
            val childStartPosition = stsd.position
            val childAtomSize = stsd.readInt()
            Assertions.checkState(childAtomSize > 0, "childAtomSize should be positive")
            val childAtomType = stsd.readInt()
            if (childAtomType == Atom.TYPE_avc1 || childAtomType == Atom.TYPE_avc3 || childAtomType == Atom.TYPE_encv || childAtomType == Atom.TYPE_m1v_ || childAtomType == Atom.TYPE_mp4v || childAtomType == Atom.TYPE_hvc1 || childAtomType == Atom.TYPE_hev1 || childAtomType == Atom.TYPE_s263 || childAtomType == Atom.TYPE_vp08 || childAtomType == Atom.TYPE_vp09 || childAtomType == Atom.TYPE_av01 || childAtomType == Atom.TYPE_dvav || childAtomType == Atom.TYPE_dva1 || childAtomType == Atom.TYPE_dvhe || childAtomType == Atom.TYPE_dvh1
            ) {
                parseVideoSampleEntry(
                    stsd, childAtomType, childStartPosition, childAtomSize, trackId,
                    rotationDegrees, drmInitData, out, i
                )
            } else if (childAtomType == Atom.TYPE_mp4a || childAtomType == Atom.TYPE_enca || childAtomType == Atom.TYPE_ac_3 || childAtomType == Atom.TYPE_ec_3 || childAtomType == Atom.TYPE_ac_4 || childAtomType == Atom.TYPE_dtsc || childAtomType == Atom.TYPE_dtse || childAtomType == Atom.TYPE_dtsh || childAtomType == Atom.TYPE_dtsl || childAtomType == Atom.TYPE_samr || childAtomType == Atom.TYPE_sawb || childAtomType == Atom.TYPE_lpcm || childAtomType == Atom.TYPE_sowt || childAtomType == Atom.TYPE_twos || childAtomType == Atom.TYPE__mp2 || childAtomType == Atom.TYPE__mp3 || childAtomType == Atom.TYPE_alac || childAtomType == Atom.TYPE_alaw || childAtomType == Atom.TYPE_ulaw || childAtomType == Atom.TYPE_Opus || childAtomType == Atom.TYPE_fLaC
            ) {
                parseAudioSampleEntry(
                    stsd, childAtomType, childStartPosition, childAtomSize, trackId,
                    language, isQuickTime, drmInitData, out, i
                )
            } else if (childAtomType == Atom.TYPE_TTML || childAtomType == Atom.TYPE_tx3g || childAtomType == Atom.TYPE_wvtt || childAtomType == Atom.TYPE_stpp || childAtomType == Atom.TYPE_c608) {
                parseTextSampleEntry(
                    stsd, childAtomType, childStartPosition, childAtomSize, trackId,
                    language, out
                )
            } else if (childAtomType == Atom.TYPE_mett) {
                parseMetaDataSampleEntry(stsd, childAtomType, childStartPosition, trackId, out)
            } else if (childAtomType == Atom.TYPE_camm) {
                out.format = Format.Builder()
                    .setId(trackId)
                    .setSampleMimeType(MimeTypes.APPLICATION_CAMERA_MOTION)
                    .build()
            }
            stsd.position = childStartPosition + childAtomSize
        }
        return out
    }

    private fun parseTextSampleEntry(
        parent: ParsableByteArray,
        atomType: Int,
        position: Int,
        atomSize: Int,
        trackId: Int,
        language: String,
        out: StsdData
    ) {
        parent.position =
            position + Atom.HEADER_SIZE + StsdData.STSD_HEADER_SIZE

        // Default values.
        var initializationData: ImmutableList<ByteArray>? = null
        var subsampleOffsetUs = Format.OFFSET_SAMPLE_RELATIVE
        val mimeType: String
        if (atomType == Atom.TYPE_TTML) {
            mimeType = MimeTypes.APPLICATION_TTML
        } else if (atomType == Atom.TYPE_tx3g) {
            mimeType = MimeTypes.APPLICATION_TX3G
            val sampleDescriptionLength = atomSize - Atom.HEADER_SIZE - 8
            val sampleDescriptionData = ByteArray(sampleDescriptionLength)
            parent.readBytes(sampleDescriptionData, 0, sampleDescriptionLength)
            initializationData = ImmutableList.of(sampleDescriptionData)
        } else if (atomType == Atom.TYPE_wvtt) {
            mimeType = MimeTypes.APPLICATION_MP4VTT
        } else if (atomType == Atom.TYPE_stpp) {
            mimeType = MimeTypes.APPLICATION_TTML
            subsampleOffsetUs = 0 // Subsample timing is absolute.
        } else if (atomType == Atom.TYPE_c608) {
            // Defined by the QuickTime File Format specification.
            mimeType = MimeTypes.APPLICATION_MP4CEA608
            out.requiredSampleTransformation = Track.TRANSFORMATION_CEA608_CDAT
        } else {
            // Never happens.
            throw IllegalStateException()
        }
        out.format = Format.Builder()
            .setId(trackId)
            .setSampleMimeType(mimeType)
            .setLanguage(language)
            .setSubsampleOffsetUs(subsampleOffsetUs)
            .setInitializationData(initializationData)
            .build()
    }

    @Throws(ParserException::class)
    private fun parseVideoSampleEntry(
        parent: ParsableByteArray,
        atomType: Int,
        position: Int,
        size: Int,
        trackId: Int,
        rotationDegrees: Int,
        drmInitData: DrmInitData?,
        out: StsdData,
        entryIndex: Int
    ) {
        var atomType = atomType
        var drmInitData = drmInitData
        parent.position =
            position + Atom.HEADER_SIZE + StsdData.STSD_HEADER_SIZE
        parent.skipBytes(16)
        val width = parent.readUnsignedShort()
        val height = parent.readUnsignedShort()
        var pixelWidthHeightRatioFromPasp = false
        var pixelWidthHeightRatio = 1f
        parent.skipBytes(50)
        var childPosition = parent.position
        if (atomType == Atom.TYPE_encv) {
            val sampleEntryEncryptionData = parseSampleEntryEncryptionData(parent, position, size)
            if (sampleEntryEncryptionData != null) {
                atomType = sampleEntryEncryptionData.first
                drmInitData = drmInitData?.copyWithSchemeType(sampleEntryEncryptionData.second.schemeType)
                out.trackEncryptionBoxes[entryIndex] = sampleEntryEncryptionData.second
            }
            parent.position = childPosition
        }
        // TODO: Uncomment when [Internal: b/63092960] is fixed.
        // else {
        //   drmInitData = null;
        // }
        var mimeType: String? = null
        if (atomType == Atom.TYPE_m1v_) {
            mimeType = MimeTypes.VIDEO_MPEG
        }
        var initializationData: List<ByteArray>? = null
        var codecs: String? = null
        var projectionData: ByteArray? = null
        @StereoMode var stereoMode = Format.NO_VALUE
        while (childPosition - position < size) {
            parent.position = childPosition
            val childStartPosition = parent.position
            val childAtomSize = parent.readInt()
            if (childAtomSize == 0 && parent.position - position == size) {
                // Handle optional terminating four zero bytes in MOV files.
                break
            }
            Assertions.checkState(childAtomSize > 0, "childAtomSize should be positive")
            val childAtomType = parent.readInt()
            if (childAtomType == Atom.TYPE_avcC) {
                Assertions.checkState(mimeType == null)
                mimeType = MimeTypes.VIDEO_H264
                parent.position = childStartPosition + Atom.HEADER_SIZE
                val avcConfig = AvcConfig.parse(parent)
                initializationData = avcConfig.initializationData
                out.nalUnitLengthFieldLength = avcConfig.nalUnitLengthFieldLength
                if (!pixelWidthHeightRatioFromPasp) {
                    pixelWidthHeightRatio = avcConfig.pixelWidthAspectRatio
                }
                codecs = avcConfig.codecs
            } else if (childAtomType == Atom.TYPE_hvcC) {
                Assertions.checkState(mimeType == null)
                mimeType = MimeTypes.VIDEO_H265
                parent.position = childStartPosition + Atom.HEADER_SIZE
                val hevcConfig = HevcConfig.parse(parent)
                initializationData = hevcConfig.initializationData
                out.nalUnitLengthFieldLength = hevcConfig.nalUnitLengthFieldLength
                codecs = hevcConfig.codecs
            } else if (childAtomType == Atom.TYPE_dvcC || childAtomType == Atom.TYPE_dvvC) {
                val dolbyVisionConfig = DolbyVisionConfig.parse(parent)
                if (dolbyVisionConfig != null) {
                    codecs = dolbyVisionConfig.codecs
                    mimeType = MimeTypes.VIDEO_DOLBY_VISION
                }
            } else if (childAtomType == Atom.TYPE_vpcC) {
                Assertions.checkState(mimeType == null)
                mimeType = if (atomType == Atom.TYPE_vp08) MimeTypes.VIDEO_VP8 else MimeTypes.VIDEO_VP9
            } else if (childAtomType == Atom.TYPE_av1C) {
                Assertions.checkState(mimeType == null)
                mimeType = MimeTypes.VIDEO_AV1
            } else if (childAtomType == Atom.TYPE_d263) {
                Assertions.checkState(mimeType == null)
                mimeType = MimeTypes.VIDEO_H263
            } else if (childAtomType == Atom.TYPE_esds) {
                Assertions.checkState(mimeType == null)
                val mimeTypeAndInitializationDataBytes = parseEsdsFromParent(parent, childStartPosition)
                mimeType = mimeTypeAndInitializationDataBytes.first
                val initializationDataBytes = mimeTypeAndInitializationDataBytes.second
                if (initializationDataBytes != null) {
                    initializationData = ImmutableList.of(initializationDataBytes)
                }
            } else if (childAtomType == Atom.TYPE_pasp) {
                pixelWidthHeightRatio = parsePaspFromParent(parent, childStartPosition)
                pixelWidthHeightRatioFromPasp = true
            } else if (childAtomType == Atom.TYPE_sv3d) {
                projectionData = parseProjFromParent(parent, childStartPosition, childAtomSize)
            } else if (childAtomType == Atom.TYPE_st3d) {
                val version = parent.readUnsignedByte()
                parent.skipBytes(3) // Flags.
                if (version == 0) {
                    val layout = parent.readUnsignedByte()
                    when (layout) {
                        0 -> stereoMode = C.STEREO_MODE_MONO
                        1 -> stereoMode = C.STEREO_MODE_TOP_BOTTOM
                        2 -> stereoMode = C.STEREO_MODE_LEFT_RIGHT
                        3 -> stereoMode = C.STEREO_MODE_STEREO_MESH
                        else -> {
                        }
                    }
                }
            }
            childPosition += childAtomSize
        }

        // If the media type was not recognized, ignore the track.
        if (mimeType == null) {
            return
        }
        out.format = Format.Builder()
            .setId(trackId)
            .setSampleMimeType(mimeType)
            .setCodecs(codecs)
            .setWidth(width)
            .setHeight(height)
            .setPixelWidthHeightRatio(pixelWidthHeightRatio)
            .setRotationDegrees(rotationDegrees)
            .setProjectionData(projectionData)
            .setStereoMode(stereoMode)
            .setInitializationData(initializationData)
            .setDrmInitData(drmInitData)
            .build()
    }

    private fun parseMetaDataSampleEntry(
        parent: ParsableByteArray, atomType: Int, position: Int, trackId: Int, out: StsdData
    ) {
        parent.position =
            position + Atom.HEADER_SIZE + StsdData.STSD_HEADER_SIZE
        if (atomType == Atom.TYPE_mett) {
            parent.readNullTerminatedString() // Skip optional content_encoding
            val mimeType = parent.readNullTerminatedString()
            if (mimeType != null) {
                out.format = Format.Builder().setId(trackId).setSampleMimeType(mimeType).build()
            }
        }
    }

    /**
     * Parses the edts atom (defined in ISO/IEC 14496-12 subsection 8.6.5).
     *
     * @param edtsAtom edts (edit box) atom to decode.
     * @return Pair of edit list durations and edit list media times, or `null` if they are not
     * present.
     */
    private fun parseEdts(edtsAtom: Atom.ContainerAtom): Pair<LongArray, LongArray>? {
        val elstAtom = edtsAtom.getLeafAtomOfType(Atom.TYPE_elst) ?: return null
        val elstData = elstAtom.data
        elstData.position = Atom.HEADER_SIZE
        val fullAtom = elstData.readInt()
        val version = parseFullAtomVersion(fullAtom)
        val entryCount = elstData.readUnsignedIntToInt()
        val editListDurations = LongArray(entryCount)
        val editListMediaTimes = LongArray(entryCount)
        for (i in 0 until entryCount) {
            editListDurations[i] = if (version == 1) elstData.readUnsignedLongToLong() else elstData.readUnsignedInt()
            editListMediaTimes[i] = if (version == 1) elstData.readLong() else elstData.readInt().toLong()
            val mediaRateInteger = elstData.readShort().toInt()
            require(mediaRateInteger == 1) {
                // The extractor does not handle dwell edits (mediaRateInteger == 0).
                "Unsupported media rate."
            }
            elstData.skipBytes(2)
        }
        return Pair.create(editListDurations, editListMediaTimes)
    }

    private fun parsePaspFromParent(parent: ParsableByteArray, position: Int): Float {
        parent.position = position + Atom.HEADER_SIZE
        val hSpacing = parent.readUnsignedIntToInt()
        val vSpacing = parent.readUnsignedIntToInt()
        return hSpacing.toFloat() / vSpacing
    }

    @Throws(ParserException::class)
    private fun parseAudioSampleEntry(
        parent: ParsableByteArray,
        atomType: Int,
        position: Int,
        size: Int,
        trackId: Int,
        language: String,
        isQuickTime: Boolean,
        drmInitData: DrmInitData?,
        out: StsdData,
        entryIndex: Int
    ) {
        var atomType = atomType
        var drmInitData = drmInitData
        parent.position =
            position + Atom.HEADER_SIZE + StsdData.STSD_HEADER_SIZE
        var quickTimeSoundDescriptionVersion = 0
        if (isQuickTime) {
            quickTimeSoundDescriptionVersion = parent.readUnsignedShort()
            parent.skipBytes(6)
        } else {
            parent.skipBytes(8)
        }
        var channelCount: Int
        var sampleRate: Int
        @PcmEncoding var pcmEncoding = Format.NO_VALUE
        var codecs: String? = null
        if (quickTimeSoundDescriptionVersion == 0 || quickTimeSoundDescriptionVersion == 1) {
            channelCount = parent.readUnsignedShort()
            parent.skipBytes(6) // sampleSize, compressionId, packetSize.
            sampleRate = parent.readUnsignedFixedPoint1616()
            if (quickTimeSoundDescriptionVersion == 1) {
                parent.skipBytes(16)
            }
        } else if (quickTimeSoundDescriptionVersion == 2) {
            parent.skipBytes(16) // always[3,16,Minus2,0,65536], sizeOfStructOnly
            sampleRate = Math.round(parent.readDouble()).toInt()
            channelCount = parent.readUnsignedIntToInt()

            // Skip always7F000000, sampleSize, formatSpecificFlags, constBytesPerAudioPacket,
            // constLPCMFramesPerAudioPacket.
            parent.skipBytes(20)
        } else {
            // Unsupported version.
            return
        }
        var childPosition = parent.position
        if (atomType == Atom.TYPE_enca) {
            val sampleEntryEncryptionData = parseSampleEntryEncryptionData(parent, position, size)
            if (sampleEntryEncryptionData != null) {
                atomType = sampleEntryEncryptionData.first
                drmInitData = drmInitData?.copyWithSchemeType(sampleEntryEncryptionData.second.schemeType)
                out.trackEncryptionBoxes[entryIndex] = sampleEntryEncryptionData.second
            }
            parent.position = childPosition
        }
        // TODO: Uncomment when [Internal: b/63092960] is fixed.
        // else {
        //   drmInitData = null;
        // }

        // If the atom type determines a MIME type, set it immediately.
        var mimeType: String? = null
        if (atomType == Atom.TYPE_ac_3) {
            mimeType = MimeTypes.AUDIO_AC3
        } else if (atomType == Atom.TYPE_ec_3) {
            mimeType = MimeTypes.AUDIO_E_AC3
        } else if (atomType == Atom.TYPE_ac_4) {
            mimeType = MimeTypes.AUDIO_AC4
        } else if (atomType == Atom.TYPE_dtsc) {
            mimeType = MimeTypes.AUDIO_DTS
        } else if (atomType == Atom.TYPE_dtsh || atomType == Atom.TYPE_dtsl) {
            mimeType = MimeTypes.AUDIO_DTS_HD
        } else if (atomType == Atom.TYPE_dtse) {
            mimeType = MimeTypes.AUDIO_DTS_EXPRESS
        } else if (atomType == Atom.TYPE_samr) {
            mimeType = MimeTypes.AUDIO_AMR_NB
        } else if (atomType == Atom.TYPE_sawb) {
            mimeType = MimeTypes.AUDIO_AMR_WB
        } else if (atomType == Atom.TYPE_lpcm || atomType == Atom.TYPE_sowt) {
            mimeType = MimeTypes.AUDIO_RAW
            pcmEncoding = C.ENCODING_PCM_16BIT
        } else if (atomType == Atom.TYPE_twos) {
            mimeType = MimeTypes.AUDIO_RAW
            pcmEncoding = C.ENCODING_PCM_16BIT_BIG_ENDIAN
        } else if (atomType == Atom.TYPE__mp2 || atomType == Atom.TYPE__mp3) {
            mimeType = MimeTypes.AUDIO_MPEG
        } else if (atomType == Atom.TYPE_alac) {
            mimeType = MimeTypes.AUDIO_ALAC
        } else if (atomType == Atom.TYPE_alaw) {
            mimeType = MimeTypes.AUDIO_ALAW
        } else if (atomType == Atom.TYPE_ulaw) {
            mimeType = MimeTypes.AUDIO_MLAW
        } else if (atomType == Atom.TYPE_Opus) {
            mimeType = MimeTypes.AUDIO_OPUS
        } else if (atomType == Atom.TYPE_fLaC) {
            mimeType = MimeTypes.AUDIO_FLAC
        }
        var initializationData: List<ByteArray>? = null
        while (childPosition - position < size) {
            parent.position = childPosition
            val childAtomSize = parent.readInt()
            Assertions.checkState(childAtomSize > 0, "childAtomSize should be positive")
            val childAtomType = parent.readInt()
            if (childAtomType == Atom.TYPE_esds || isQuickTime && childAtomType == Atom.TYPE_wave) {
                val esdsAtomPosition = if (childAtomType == Atom.TYPE_esds) childPosition else findEsdsPosition(
                    parent,
                    childPosition,
                    childAtomSize
                )
                if (esdsAtomPosition != C.POSITION_UNSET) {
                    val mimeTypeAndInitializationData = parseEsdsFromParent(parent, esdsAtomPosition)
                    mimeType = mimeTypeAndInitializationData.first
                    val initializationDataBytes = mimeTypeAndInitializationData.second
                    if (initializationDataBytes != null) {
                        if (MimeTypes.AUDIO_AAC == mimeType) {
                            // Update sampleRate and channelCount from the AudioSpecificConfig initialization
                            // data, which is more reliable. See [Internal: b/10903778].
                            val aacConfig = AacUtil.parseAudioSpecificConfig(initializationDataBytes)
                            sampleRate = aacConfig.sampleRateHz
                            channelCount = aacConfig.channelCount
                            codecs = aacConfig.codecs
                        }
                        initializationData = ImmutableList.of(initializationDataBytes)
                    }
                }
            } else if (childAtomType == Atom.TYPE_dac3) {
                parent.position = Atom.HEADER_SIZE + childPosition
                out.format = Ac3Util.parseAc3AnnexFFormat(
                    parent, Integer.toString(trackId), language,
                    drmInitData
                )
            } else if (childAtomType == Atom.TYPE_dec3) {
                parent.position = Atom.HEADER_SIZE + childPosition
                out.format = Ac3Util.parseEAc3AnnexFFormat(
                    parent, Integer.toString(trackId), language,
                    drmInitData
                )
            } else if (childAtomType == Atom.TYPE_dac4) {
                parent.position = Atom.HEADER_SIZE + childPosition
                out.format = Ac4Util.parseAc4AnnexEFormat(parent, Integer.toString(trackId), language, drmInitData)
            } else if (childAtomType == Atom.TYPE_ddts) {
                out.format = Format.Builder()
                    .setId(trackId)
                    .setSampleMimeType(mimeType)
                    .setChannelCount(channelCount)
                    .setSampleRate(sampleRate)
                    .setDrmInitData(drmInitData)
                    .setLanguage(language)
                    .build()
            } else if (childAtomType == Atom.TYPE_dOps) {
                // Build an Opus Identification Header (defined in RFC-7845) by concatenating the Opus Magic
                // Signature and the body of the dOps atom.
                val childAtomBodySize = childAtomSize - Atom.HEADER_SIZE
                val headerBytes = Arrays.copyOf(opusMagic, opusMagic.size + childAtomBodySize)
                parent.position = childPosition + Atom.HEADER_SIZE
                parent.readBytes(headerBytes, opusMagic.size, childAtomBodySize)
                initializationData = OpusUtil.buildInitializationData(headerBytes)
            } else if (childAtomType == Atom.TYPE_dfLa) {
                val childAtomBodySize = childAtomSize - Atom.FULL_HEADER_SIZE
                val initializationDataBytes = ByteArray(4 + childAtomBodySize)
                initializationDataBytes[0] = 0x66 // f
                initializationDataBytes[1] = 0x4C // L
                initializationDataBytes[2] = 0x61 // a
                initializationDataBytes[3] = 0x43 // C
                parent.position = childPosition + Atom.FULL_HEADER_SIZE
                parent.readBytes(initializationDataBytes,  /* offset= */4, childAtomBodySize)
                initializationData = ImmutableList.of(initializationDataBytes)
            } else if (childAtomType == Atom.TYPE_alac) {
                val childAtomBodySize = childAtomSize - Atom.FULL_HEADER_SIZE
                val initializationDataBytes = ByteArray(childAtomBodySize)
                parent.position = childPosition + Atom.FULL_HEADER_SIZE
                parent.readBytes(initializationDataBytes,  /* offset= */0, childAtomBodySize)
                // Update sampleRate and channelCount from the AudioSpecificConfig initialization data,
                // which is more reliable. See https://github.com/google/ExoPlayer/pull/6629.
                val audioSpecificConfig = CodecSpecificDataUtil.parseAlacAudioSpecificConfig(initializationDataBytes)
                sampleRate = audioSpecificConfig.first
                channelCount = audioSpecificConfig.second
                initializationData = ImmutableList.of(initializationDataBytes)
            }
            childPosition += childAtomSize
        }
        if (out.format == null && mimeType != null) {
            out.format = Format.Builder()
                .setId(trackId)
                .setSampleMimeType(mimeType)
                .setCodecs(codecs)
                .setChannelCount(channelCount)
                .setSampleRate(sampleRate)
                .setPcmEncoding(pcmEncoding)
                .setInitializationData(initializationData)
                .setDrmInitData(drmInitData)
                .setLanguage(language)
                .build()
        }
    }

    /**
     * Returns the position of the esds box within a parent, or [C.POSITION_UNSET] if no esds
     * box is found
     */
    private fun findEsdsPosition(parent: ParsableByteArray, position: Int, size: Int): Int {
        var childAtomPosition = parent.position
        while (childAtomPosition - position < size) {
            parent.position = childAtomPosition
            val childAtomSize = parent.readInt()
            Assertions.checkState(childAtomSize > 0, "childAtomSize should be positive")
            val childType = parent.readInt()
            if (childType == Atom.TYPE_esds) {
                return childAtomPosition
            }
            childAtomPosition += childAtomSize
        }
        return C.POSITION_UNSET
    }

    /** Returns codec-specific initialization data contained in an esds box.  */
    private fun parseEsdsFromParent(
        parent: ParsableByteArray, position: Int
    ): Pair<String?, ByteArray?> {
        parent.position = position + Atom.HEADER_SIZE + 4
        // Start of the ES_Descriptor (defined in ISO/IEC 14496-1)
        parent.skipBytes(1) // ES_Descriptor tag
        parseExpandableClassSize(parent)
        parent.skipBytes(2) // ES_ID
        val flags = parent.readUnsignedByte()
        if (flags and 0x80 /* streamDependenceFlag */ != 0) {
            parent.skipBytes(2)
        }
        if (flags and 0x40 /* URL_Flag */ != 0) {
            parent.skipBytes(parent.readUnsignedShort())
        }
        if (flags and 0x20 /* OCRstreamFlag */ != 0) {
            parent.skipBytes(2)
        }

        // Start of the DecoderConfigDescriptor (defined in ISO/IEC 14496-1)
        parent.skipBytes(1) // DecoderConfigDescriptor tag
        parseExpandableClassSize(parent)

        // Set the MIME type based on the object type indication (ISO/IEC 14496-1 table 5).
        val objectTypeIndication = parent.readUnsignedByte()
        val mimeType = MimeTypes.getMimeTypeFromMp4ObjectType(objectTypeIndication)
        if (MimeTypes.AUDIO_MPEG == mimeType || MimeTypes.AUDIO_DTS == mimeType || MimeTypes.AUDIO_DTS_HD == mimeType) {
            return Pair.create(mimeType, null)
        }
        parent.skipBytes(12)

        // Start of the DecoderSpecificInfo.
        parent.skipBytes(1) // DecoderSpecificInfo tag
        val initializationDataSize = parseExpandableClassSize(parent)
        val initializationData = ByteArray(initializationDataSize)
        parent.readBytes(initializationData, 0, initializationDataSize)
        return Pair.create(mimeType, initializationData)
    }

    /**
     * Parses encryption data from an audio/video sample entry, returning a pair consisting of the
     * unencrypted atom type and a [TrackEncryptionBox]. Null is returned if no common
     * encryption sinf atom was present.
     */
    private fun parseSampleEntryEncryptionData(
        parent: ParsableByteArray, position: Int, size: Int
    ): Pair<Int, TrackEncryptionBox>? {
        var childPosition = parent.position
        while (childPosition - position < size) {
            parent.position = childPosition
            val childAtomSize = parent.readInt()
            Assertions.checkState(childAtomSize > 0, "childAtomSize should be positive")
            val childAtomType = parent.readInt()
            if (childAtomType == Atom.TYPE_sinf) {
                val result = parseCommonEncryptionSinfFromParent(parent, childPosition, childAtomSize)
                if (result != null) {
                    return result
                }
            }
            childPosition += childAtomSize
        }
        return null
    }

    fun parseCommonEncryptionSinfFromParent(
        parent: ParsableByteArray, position: Int, size: Int
    ): Pair<Int, TrackEncryptionBox>? {
        var childPosition = position + Atom.HEADER_SIZE
        var schemeInformationBoxPosition = C.POSITION_UNSET
        var schemeInformationBoxSize = 0
        var schemeType: String? = null
        var dataFormat: Int? = null
        while (childPosition - position < size) {
            parent.position = childPosition
            val childAtomSize = parent.readInt()
            val childAtomType = parent.readInt()
            if (childAtomType == Atom.TYPE_frma) {
                dataFormat = parent.readInt()
            } else if (childAtomType == Atom.TYPE_schm) {
                parent.skipBytes(4)
                // Common encryption scheme_type values are defined in ISO/IEC 23001-7:2016, section 4.1.
                schemeType = parent.readString(4)
            } else if (childAtomType == Atom.TYPE_schi) {
                schemeInformationBoxPosition = childPosition
                schemeInformationBoxSize = childAtomSize
            }
            childPosition += childAtomSize
        }
        return if (C.CENC_TYPE_cenc == schemeType || C.CENC_TYPE_cbc1 == schemeType || C.CENC_TYPE_cens == schemeType || C.CENC_TYPE_cbcs == schemeType) {
            Assertions.checkStateNotNull(dataFormat, "frma atom is mandatory")
            Assertions.checkState(
                schemeInformationBoxPosition != C.POSITION_UNSET, "schi atom is mandatory"
            )
            val encryptionBox = Assertions.checkStateNotNull(
                parseSchiFromParent(
                    parent, schemeInformationBoxPosition, schemeInformationBoxSize, schemeType
                ),
                "tenc atom is mandatory"
            )
            Pair.create(dataFormat, encryptionBox)
        } else {
            null
        }
    }

    private fun parseSchiFromParent(
        parent: ParsableByteArray, position: Int, size: Int, schemeType: String
    ): TrackEncryptionBox? {
        var childPosition = position + Atom.HEADER_SIZE
        while (childPosition - position < size) {
            parent.position = childPosition
            val childAtomSize = parent.readInt()
            val childAtomType = parent.readInt()
            if (childAtomType == Atom.TYPE_tenc) {
                val fullAtom = parent.readInt()
                val version = parseFullAtomVersion(fullAtom)
                parent.skipBytes(1) // reserved = 0.
                var defaultCryptByteBlock = 0
                var defaultSkipByteBlock = 0
                if (version == 0) {
                    parent.skipBytes(1) // reserved = 0.
                } else  /* version 1 or greater */ {
                    val patternByte = parent.readUnsignedByte()
                    defaultCryptByteBlock = patternByte and 0xF0 shr 4
                    defaultSkipByteBlock = patternByte and 0x0F
                }
                val defaultIsProtected = parent.readUnsignedByte() == 1
                val defaultPerSampleIvSize = parent.readUnsignedByte()
                val defaultKeyId = ByteArray(16)
                parent.readBytes(defaultKeyId, 0, defaultKeyId.size)
                var constantIv: ByteArray? = null
                if (defaultIsProtected && defaultPerSampleIvSize == 0) {
                    val constantIvSize = parent.readUnsignedByte()
                    constantIv = ByteArray(constantIvSize)
                    parent.readBytes(constantIv, 0, constantIvSize)
                }
                return TrackEncryptionBox(
                    defaultIsProtected, schemeType, defaultPerSampleIvSize,
                    defaultKeyId, defaultCryptByteBlock, defaultSkipByteBlock, constantIv
                )
            }
            childPosition += childAtomSize
        }
        return null
    }

    /** Parses the proj box from sv3d box, as specified by https://github.com/google/spatial-media.  */
    private fun parseProjFromParent(parent: ParsableByteArray, position: Int, size: Int): ByteArray? {
        var childPosition = position + Atom.HEADER_SIZE
        while (childPosition - position < size) {
            parent.position = childPosition
            val childAtomSize = parent.readInt()
            val childAtomType = parent.readInt()
            if (childAtomType == Atom.TYPE_proj) {
                return Arrays.copyOfRange(parent.data, childPosition, childPosition + childAtomSize)
            }
            childPosition += childAtomSize
        }
        return null
    }

    /** Parses the size of an expandable class, as specified by ISO/IEC 14496-1 subsection 8.3.3.  */
    private fun parseExpandableClassSize(data: ParsableByteArray): Int {
        var currentByte = data.readUnsignedByte()
        var size = currentByte and 0x7F
        while (currentByte and 0x80 == 0x80) {
            currentByte = data.readUnsignedByte()
            size = size shl 7 or (currentByte and 0x7F)
        }
        return size
    }

    /** Returns whether it's possible to apply the specified edit using gapless playback info.  */
    private fun canApplyEditWithGaplessInfo(
        timestamps: LongArray, duration: Long, editStartTime: Long, editEndTime: Long
    ): Boolean {
        val lastIndex = timestamps.size - 1
        val latestDelayIndex = Util.constrainValue(MAX_GAPLESS_TRIM_SIZE_SAMPLES, 0, lastIndex)
        val earliestPaddingIndex = Util.constrainValue(timestamps.size - MAX_GAPLESS_TRIM_SIZE_SAMPLES, 0, lastIndex)
        return timestamps[0] <= editStartTime && editStartTime < timestamps[latestDelayIndex] && timestamps[earliestPaddingIndex] < editEndTime && editEndTime <= duration
    }

    private class ChunkIterator(
        private val stsc: ParsableByteArray, private val chunkOffsets: ParsableByteArray,
        private val chunkOffsetsAreLongs: Boolean
    ) {
        val length: Int
        var index: Int
        var numSamples = 0
        var offset: Long = 0
        private var nextSamplesPerChunkChangeIndex = 0
        private var remainingSamplesPerChunkChanges: Int
        fun moveNext(): Boolean {
            if (++index == length) {
                return false
            }
            offset = if (chunkOffsetsAreLongs) chunkOffsets.readUnsignedLongToLong() else chunkOffsets.readUnsignedInt()
            if (index == nextSamplesPerChunkChangeIndex) {
                numSamples = stsc.readUnsignedIntToInt()
                stsc.skipBytes(4) // Skip sample_description_index
                nextSamplesPerChunkChangeIndex =
                    if (--remainingSamplesPerChunkChanges > 0) stsc.readUnsignedIntToInt() - 1 else C.INDEX_UNSET
            }
            return true
        }

        init {
            chunkOffsets.position = Atom.FULL_HEADER_SIZE
            length = chunkOffsets.readUnsignedIntToInt()
            stsc.position = Atom.FULL_HEADER_SIZE
            remainingSamplesPerChunkChanges = stsc.readUnsignedIntToInt()
            Assertions.checkState(stsc.readInt() == 1, "first_chunk must be 1")
            index = -1
        }
    }

    /**
     * Holds data parsed from a tkhd atom.
     */
    private class TkhdData(val id: Int, val duration: Long, val rotationDegrees: Int)

    /**
     * Holds data parsed from an stsd atom and its children.
     */
    private class StsdData(numberOfEntries: Int) {
        val trackEncryptionBoxes: Array<TrackEncryptionBox?>
        var format: Format? = null
        var nalUnitLengthFieldLength = 0

        @Track.Transformation
        var requiredSampleTransformation: Int

        companion object {
            const val STSD_HEADER_SIZE = 8
        }

        init {
            trackEncryptionBoxes = arrayOfNulls(numberOfEntries)
            requiredSampleTransformation = Track.TRANSFORMATION_NONE
        }
    }

    /**
     * A box containing sample sizes (e.g. stsz, stz2).
     */
    private interface SampleSizeBox {
        /**
         * Returns the number of samples.
         */
        val sampleCount: Int

        /** Returns the size of each sample if fixed, or [C.LENGTH_UNSET] otherwise.  */
        val fixedSampleSize: Int

        /** Returns the size for the next sample.  */
        fun readNextSampleSize(): Int
    }

    /**
     * An stsz sample size box.
     */
    /* package */
    internal class StszSampleSizeBox(stszAtom: Atom.LeafAtom, trackFormat: Format) : SampleSizeBox {
        override val fixedSampleSize: Int
        override val sampleCount: Int
        private val data: ParsableByteArray
        override fun readNextSampleSize(): Int {
            return if (fixedSampleSize == C.LENGTH_UNSET) data.readUnsignedIntToInt() else fixedSampleSize
        }

        init {
            data = stszAtom.data
            data.position = Atom.FULL_HEADER_SIZE
            var fixedSampleSize = data.readUnsignedIntToInt()
            if (MimeTypes.AUDIO_RAW == trackFormat.sampleMimeType) {
                val pcmFrameSize = Util.getPcmFrameSize(trackFormat.pcmEncoding, trackFormat.channelCount)
                if (fixedSampleSize == 0 || fixedSampleSize % pcmFrameSize != 0) {
                    // The sample size from the stsz box is inconsistent with the PCM encoding and channel
                    // count derived from the stsd box. Choose stsd box as source of truth
                    // [Internal ref: b/171627904].
                    Log.w(
                        TAG,
                        "Audio sample size mismatch. stsd sample size: "
                                + pcmFrameSize
                                + ", stsz sample size: "
                                + fixedSampleSize
                    )
                    fixedSampleSize = pcmFrameSize
                }
            }
            this.fixedSampleSize = if (fixedSampleSize == 0) C.LENGTH_UNSET else fixedSampleSize
            sampleCount = data.readUnsignedIntToInt()
        }
    }

    /**
     * An stz2 sample size box.
     */
    /* package */
    internal class Stz2SampleSizeBox(stz2Atom: Atom.LeafAtom) : SampleSizeBox {
        private val data: ParsableByteArray
        override val sampleCount: Int
        private val fieldSize // Can be 4, 8, or 16.
                : Int

        // Used only if fieldSize == 4.
        private var sampleIndex = 0
        private var currentByte = 0
        override val fixedSampleSize: Int
            get() = C.LENGTH_UNSET

        override fun readNextSampleSize(): Int {
            return if (fieldSize == 8) {
                data.readUnsignedByte()
            } else if (fieldSize == 16) {
                data.readUnsignedShort()
            } else {
                // fieldSize == 4.
                if (sampleIndex++ % 2 == 0) {
                    // Read the next byte into our cached byte when we are reading the upper bits.
                    currentByte = data.readUnsignedByte()
                    // Read the upper bits from the byte and shift them to the lower 4 bits.
                    currentByte and 0xF0 shr 4
                } else {
                    // Mask out the upper 4 bits of the last byte we read.
                    currentByte and 0x0F
                }
            }
        }

        init {
            data = stz2Atom.data
            data.position = Atom.FULL_HEADER_SIZE
            fieldSize = data.readUnsignedIntToInt() and 0x000000FF
            sampleCount = data.readUnsignedIntToInt()
        }
    }
}