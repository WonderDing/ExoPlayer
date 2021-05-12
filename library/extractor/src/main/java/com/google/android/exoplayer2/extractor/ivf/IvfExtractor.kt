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
import android.util.SparseArray
import androidx.annotation.IntDef
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.C.BufferFlags
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.ParserException
import com.google.android.exoplayer2.audio.Ac4Util
import com.google.android.exoplayer2.drm.DrmInitData
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData
import com.google.android.exoplayer2.extractor.*
import com.google.android.exoplayer2.extractor.SeekMap.Unseekable
import com.google.android.exoplayer2.extractor.TrackOutput.CryptoData
import com.google.android.exoplayer2.extractor.ivf.Atom.Companion.parseFullAtomFlags
import com.google.android.exoplayer2.extractor.ivf.Atom.Companion.parseFullAtomVersion
import com.google.android.exoplayer2.extractor.ivf.AtomParsers.parseTraks
import com.google.android.exoplayer2.extractor.ivf.IVFSniffer.sniffFragmented
import com.google.android.exoplayer2.metadata.emsg.EventMessage
import com.google.android.exoplayer2.metadata.emsg.EventMessageEncoder
import com.google.android.exoplayer2.util.*
import com.google.common.base.Function
import java.io.IOException
import java.util.*
import kotlin.experimental.and

/**
 * Extracts data from the FMP4 container format.
 */
class IvfExtractor @JvmOverloads constructor(
    // Workarounds.
    @field:Flags @param:Flags private val flags: Int = 0,
    // Adjusts sample timestamps.
    private val timestampAdjuster: TimestampAdjuster? =  /* timestampAdjuster= */null,
    private val sideloadedTrack: Track? =  /* sideloadedTrack= */null,
    closedCaptionFormats: List<Format> = emptyList(),
    additionalEmsgTrackOutput: TrackOutput? =  /* additionalEmsgTrackOutput= */
        null
) : Extractor {
    /**
     * Flags controlling the behavior of the extractor. Possible flag values are [ ][.FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME], [.FLAG_WORKAROUND_IGNORE_TFDT_BOX],
     * [.FLAG_ENABLE_EMSG_TRACK] and [.FLAG_WORKAROUND_IGNORE_EDIT_LISTS].
     */
    @MustBeDocumented
    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    @IntDef(
        flag = true,
        value = [FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME, FLAG_WORKAROUND_IGNORE_TFDT_BOX, FLAG_ENABLE_EMSG_TRACK, FLAG_WORKAROUND_IGNORE_EDIT_LISTS]
    )
    annotation class Flags

    // Sideloaded data.
    private val closedCaptionFormats: MutableList<Format>

    // Track-linked data bundle, accessible as a whole through trackID.
    private val trackBundles: SparseArray<TrackBundle>

    // Temporary arrays.
    private val nalStartCode: ParsableByteArray
    private val nalPrefix: ParsableByteArray
    private val nalBuffer: ParsableByteArray
    private val scratchBytes: ByteArray
    private val scratch: ParsableByteArray
    private val eventMessageEncoder: EventMessageEncoder

    // Parser state.
    private val atomHeader: ParsableByteArray
    private val containerAtoms: ArrayDeque<Atom.ContainerAtom>
    private val pendingMetadataSampleInfos: ArrayDeque<MetadataSampleInfo>
    private val additionalEmsgTrackOutput: TrackOutput?
    private var parserState = 0
    private var atomType = 0
    private var atomSize: Long = 0
    private var atomHeaderBytesRead = 0
    private var atomData: ParsableByteArray? = null
    private var endOfMdatPosition: Long = 0
    private var pendingMetadataSampleBytes = 0
    private var pendingSeekTimeUs: Long
    private var durationUs: Long
    private var segmentIndexEarliestPresentationTimeUs: Long
    private var currentTrackBundle: TrackBundle? = null
    private var sampleSize = 0
    private var sampleBytesWritten = 0
    private var sampleCurrentNalBytesRemaining = 0
    private var processSeiNalUnitPayload = false

    // Outputs.
    private var extractorOutput: ExtractorOutput
    private var emsgTrackOutputs: Array<out TrackOutput?>//TODO: 处理array转换问题
    private var ceaTrackOutputs: Array<out TrackOutput?>

    // Whether extractorOutput.seekMap has been called.
    private var haveOutputSeekMap = false
    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        return sniffFragmented(input)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        enterReadingAtomHeaderState()
        initExtraTracks()
        if (sideloadedTrack != null) {
            val bundle = TrackBundle(
                output.track(0, sideloadedTrack.type),
                TrackSampleTable(
                    sideloadedTrack, LongArray(0), IntArray(0),  /* maximumSize= */
                    0, LongArray(0), IntArray(0),  /* durationUs= */
                    0
                ),
                DefaultSampleValues( /* sampleDescriptionIndex= */
                    0,  /* duration= */
                    0,  /* size= */
                    0,  /* flags= */
                    0
                )
            )
            trackBundles.put(0, bundle)
            extractorOutput.endTracks()
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        val trackCount = trackBundles.size()
        for (i in 0 until trackCount) {
            trackBundles.valueAt(i).resetFragmentInfo()
        }
        pendingMetadataSampleInfos.clear()
        pendingMetadataSampleBytes = 0
        pendingSeekTimeUs = timeUs
        containerAtoms.clear()
        enterReadingAtomHeaderState()
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        while (true) {
            when (parserState) {
                STATE_READING_ATOM_HEADER -> if (!readAtomHeader(input)) {
                    return Extractor.RESULT_END_OF_INPUT
                }
                STATE_READING_ATOM_PAYLOAD -> readAtomPayload(input)
                STATE_READING_ENCRYPTION_DATA -> readEncryptionData(input)
                else -> if (readSample(input)) {
                    return Extractor.RESULT_CONTINUE
                }
            }
        }
    }

    private fun enterReadingAtomHeaderState() {
        parserState = STATE_READING_ATOM_HEADER
        atomHeaderBytesRead = 0
    }

    @Throws(IOException::class)
    private fun readAtomHeader(input: ExtractorInput): Boolean {
        if (input.position >= input.length) {
            return false
        }
        if (atomHeaderBytesRead == 0) {
            // Read the standard length atom header.
            if (!input.readFully(atomHeader.data, 0, Atom.HEADER_SIZE, true)) {
                return false
            }
            atomHeaderBytesRead = Atom.HEADER_SIZE
            atomHeader.position = 0
            atomSize = atomHeader.readUnsignedInt()
            atomType = atomHeader.readInt()
        }
        if (atomSize == Atom.DEFINES_LARGE_SIZE.toLong()) {
            // Read the large size.
            val headerBytesRemaining = Atom.LONG_HEADER_SIZE - Atom.HEADER_SIZE
            input.readFully(atomHeader.data, Atom.HEADER_SIZE, headerBytesRemaining)
            atomHeaderBytesRead += headerBytesRemaining
            atomSize = atomHeader.readUnsignedLongToLong()
        } else if (atomSize == Atom.EXTENDS_TO_END_SIZE.toLong()) {
            // The atom extends to the end of the file. Note that if the atom is within a container we can
            // work out its size even if the input length is unknown.
            var endPosition = input.length
            if (endPosition == C.LENGTH_UNSET.toLong() && !containerAtoms.isEmpty()) {
                endPosition = containerAtoms.peek()?.endPosition ?: 0
            }
            if (endPosition != C.LENGTH_UNSET.toLong()) {
                atomSize = endPosition - input.position + atomHeaderBytesRead
            }
        }
        if (atomSize < atomHeaderBytesRead) {
            throw ParserException("Atom size less than header length (unsupported).")
        }

        val atomPosition = input.position - atomHeaderBytesRead
        if (atomType == Atom.TYPE_moof || atomType == Atom.TYPE_mdat) {
            if (!haveOutputSeekMap) {
                // This must be the first moof or mdat in the stream.
                extractorOutput.seekMap(Unseekable(durationUs, atomPosition))
                haveOutputSeekMap = true
            }
        }
        if (atomType == Atom.TYPE_moof) {
            // The data positions may be updated when parsing the tfhd/trun.
            val trackCount = trackBundles.size()
            for (i in 0 until trackCount) {
                val fragment = trackBundles.valueAt(i).fragment
                fragment.atomPosition = atomPosition
                fragment.auxiliaryDataPosition = atomPosition
                fragment.dataPosition = atomPosition
            }
        }
        if (atomType == Atom.TYPE_mdat) {
            currentTrackBundle = null
            endOfMdatPosition = atomPosition + atomSize
            parserState = STATE_READING_ENCRYPTION_DATA
            return true
        }
        if (shouldParseContainerAtom(atomType)) {
            val endPosition = input.position + atomSize - Atom.HEADER_SIZE
            containerAtoms.push(Atom.ContainerAtom(atomType, endPosition))
            if (atomSize == atomHeaderBytesRead.toLong()) {
                processAtomEnded(endPosition)
            } else {
                // Start reading the first child atom.
                enterReadingAtomHeaderState()
            }
        } else if (shouldParseLeafAtom(atomType)) {
            if (atomHeaderBytesRead != Atom.HEADER_SIZE) {
                throw ParserException("Leaf atom defines extended atom size (unsupported).")
            }
            if (atomSize > Int.MAX_VALUE) {
                throw ParserException("Leaf atom with length > 2147483647 (unsupported).")
            }
            val atomData = ParsableByteArray(atomSize.toInt())
            System.arraycopy(atomHeader.data, 0, atomData.data, 0, Atom.HEADER_SIZE)
            this.atomData = atomData
            parserState = STATE_READING_ATOM_PAYLOAD
        } else {
            if (atomSize > Int.MAX_VALUE) {
                throw ParserException("Skipping atom with length > 2147483647 (unsupported).")
            }
            atomData = null
            parserState = STATE_READING_ATOM_PAYLOAD
        }
        return true
    }

    @Throws(IOException::class)
    private fun readAtomPayload(input: ExtractorInput) {
        val atomPayloadSize = atomSize.toInt() - atomHeaderBytesRead
        val atomData = atomData
        if (atomData != null) {
            input.readFully(atomData.data, Atom.HEADER_SIZE, atomPayloadSize)
            onLeafAtomRead(Atom.LeafAtom(atomType, atomData), input.position)
        } else {
            input.skipFully(atomPayloadSize)
        }
        processAtomEnded(input.position)
    }

    @Throws(ParserException::class)
    private fun processAtomEnded(atomEndPosition: Long) {
        while (!containerAtoms.isEmpty() && containerAtoms.peek()?.endPosition == atomEndPosition) {
            onContainerAtomRead(containerAtoms.pop())
        }
        enterReadingAtomHeaderState()
    }

    @Throws(ParserException::class)
    private fun onLeafAtomRead(leaf: Atom.LeafAtom, inputPosition: Long) {
        if (!containerAtoms.isEmpty()) {
            containerAtoms.peek()?.add(leaf)
        } else if (leaf.type == Atom.TYPE_sidx) {
            val result = parseSidx(leaf.data, inputPosition)
            segmentIndexEarliestPresentationTimeUs = result.first
            extractorOutput.seekMap(result.second)
            haveOutputSeekMap = true
        } else if (leaf.type == Atom.TYPE_emsg) {
            onEmsgLeafAtomRead(leaf.data)
        }
    }

    @Throws(ParserException::class)
    private fun onContainerAtomRead(container: Atom.ContainerAtom) {
        if (container.type == Atom.TYPE_moov) {
            onMoovContainerAtomRead(container)
        } else if (container.type == Atom.TYPE_moof) {
            onMoofContainerAtomRead(container)
        } else if (!containerAtoms.isEmpty()) {
            containerAtoms.peek()?.add(container)
        }
    }

    @Throws(ParserException::class)
    private fun onMoovContainerAtomRead(moov: Atom.ContainerAtom) {
        Assertions.checkState(sideloadedTrack == null, "Unexpected moov box.")
        val drmInitData = getDrmInitDataFromAtoms(moov.leafChildren)

        // Read declaration of track fragments in the moov box.
        val mvex = Assertions.checkNotNull(moov.getContainerAtomOfType(Atom.TYPE_mvex))
        val defaultSampleValuesArray = SparseArray<DefaultSampleValues>()
        var duration = C.TIME_UNSET
        val mvexChildrenSize = mvex.leafChildren.size
        for (i in 0 until mvexChildrenSize) {
            val atom = mvex.leafChildren[i]
            if (atom.type == Atom.TYPE_trex) {
                val trexData = parseTrex(atom.data)
                defaultSampleValuesArray.put(trexData.first, trexData.second)
            } else if (atom.type == Atom.TYPE_mehd) {
                duration = parseMehd(atom.data)
            }
        }

        // Construction of tracks and sample tables.
        val sampleTables = parseTraks(
            moov,
            GaplessInfoHolder(),
            duration,
            drmInitData,  /* ignoreEditLists= */
            flags and FLAG_WORKAROUND_IGNORE_EDIT_LISTS != 0,  /* isQuickTime= */
            false, Function { track: Track? -> modifyTrack(track) })
        val trackCount = sampleTables.size
        if (trackBundles.size() == 0) {
            // We need to create the track bundles.
            for (i in 0 until trackCount) {
                val sampleTable = sampleTables[i]
                val track = sampleTable.track
                val trackBundle = TrackBundle(
                    extractorOutput.track(i, track.type),
                    sampleTable,
                    getDefaultSampleValues(defaultSampleValuesArray, track.id)
                )
                trackBundles.put(track.id, trackBundle)
                durationUs = Math.max(durationUs, track.durationUs)
            }
            extractorOutput.endTracks()
        } else {
            Assertions.checkState(trackBundles.size() == trackCount)
            for (i in 0 until trackCount) {
                val sampleTable = sampleTables[i]
                val track = sampleTable.track
                trackBundles[track.id]
                    .reset(sampleTable, getDefaultSampleValues(defaultSampleValuesArray, track.id))
            }
        }
    }

    protected fun modifyTrack(track: Track?): Track? {
        return track
    }

    private fun getDefaultSampleValues(
        defaultSampleValuesArray: SparseArray<DefaultSampleValues>, trackId: Int
    ): DefaultSampleValues {
        return if (defaultSampleValuesArray.size() == 1) {
            // Ignore track id if there is only one track to cope with non-matching track indices.
            // See https://github.com/google/ExoPlayer/issues/4477.
            defaultSampleValuesArray.valueAt( /* index= */0)
        } else Assertions.checkNotNull(defaultSampleValuesArray[trackId])
    }

    @Throws(ParserException::class)
    private fun onMoofContainerAtomRead(moof: Atom.ContainerAtom) {
        parseMoof(moof, trackBundles, flags, scratchBytes)
        val drmInitData = getDrmInitDataFromAtoms(moof.leafChildren)
        if (drmInitData != null) {
            val trackCount = trackBundles.size()
            for (i in 0 until trackCount) {
                trackBundles.valueAt(i).updateDrmInitData(drmInitData)
            }
        }
        // If we have a pending seek, advance tracks to their preceding sync frames.
        if (pendingSeekTimeUs != C.TIME_UNSET) {
            val trackCount = trackBundles.size()
            for (i in 0 until trackCount) {
                trackBundles.valueAt(i).seek(pendingSeekTimeUs)
            }
            pendingSeekTimeUs = C.TIME_UNSET
        }
    }

    private fun initExtraTracks() {
        var nextExtraTrackId = EXTRA_TRACKS_BASE_ID
        emsgTrackOutputs = arrayOfNulls(2)
        var emsgTrackOutputCount = 0
        if (additionalEmsgTrackOutput != null) {
            (emsgTrackOutputs as Array<TrackOutput?>)[emsgTrackOutputCount++] = additionalEmsgTrackOutput
        }
        if (flags and FLAG_ENABLE_EMSG_TRACK != 0) {
            (emsgTrackOutputs as Array<TrackOutput?>)[emsgTrackOutputCount++] =
                extractorOutput.track(nextExtraTrackId++, C.TRACK_TYPE_METADATA)
        }
        emsgTrackOutputs = Util.nullSafeArrayCopy(emsgTrackOutputs as Array<out TrackOutput>, emsgTrackOutputCount)
        for (eventMessageTrackOutput in emsgTrackOutputs) {
            eventMessageTrackOutput?.format(EMSG_FORMAT)
        }
        ceaTrackOutputs = arrayOfNulls(closedCaptionFormats.size)
        for (i in ceaTrackOutputs.indices) {
            val output = extractorOutput.track(nextExtraTrackId++, C.TRACK_TYPE_TEXT)
            output.format(closedCaptionFormats[i])
            (ceaTrackOutputs as Array<TrackOutput?>)[i] = output
        }
    }

    /**
     * Handles an emsg atom (defined in 23009-1).
     */
    private fun onEmsgLeafAtomRead(atom: ParsableByteArray) {
        if (emsgTrackOutputs.size == 0) {
            return
        }
        atom.position = Atom.HEADER_SIZE
        val fullAtom = atom.readInt()
        val version = parseFullAtomVersion(fullAtom)
        val schemeIdUri: String
        val value: String
        val timescale: Long
        var presentationTimeDeltaUs = C.TIME_UNSET // Only set if version == 0
        var sampleTimeUs = C.TIME_UNSET
        val durationMs: Long
        val id: Long
        when (version) {
            0 -> {
                schemeIdUri = Assertions.checkNotNull(atom.readNullTerminatedString())
                value = Assertions.checkNotNull(atom.readNullTerminatedString())
                timescale = atom.readUnsignedInt()
                presentationTimeDeltaUs =
                    Util.scaleLargeTimestamp(atom.readUnsignedInt(), C.MICROS_PER_SECOND, timescale)
                if (segmentIndexEarliestPresentationTimeUs != C.TIME_UNSET) {
                    sampleTimeUs = segmentIndexEarliestPresentationTimeUs + presentationTimeDeltaUs
                }
                durationMs = Util.scaleLargeTimestamp(atom.readUnsignedInt(), C.MILLIS_PER_SECOND, timescale)
                id = atom.readUnsignedInt()
            }
            1 -> {
                timescale = atom.readUnsignedInt()
                sampleTimeUs = Util.scaleLargeTimestamp(atom.readUnsignedLongToLong(), C.MICROS_PER_SECOND, timescale)
                durationMs = Util.scaleLargeTimestamp(atom.readUnsignedInt(), C.MILLIS_PER_SECOND, timescale)
                id = atom.readUnsignedInt()
                schemeIdUri = Assertions.checkNotNull(atom.readNullTerminatedString())
                value = Assertions.checkNotNull(atom.readNullTerminatedString())
            }
            else -> {
                Log.w(TAG, "Skipping unsupported emsg version: $version")
                return
            }
        }
        val messageData = ByteArray(atom.bytesLeft())
        atom.readBytes(messageData,  /*offset=*/0, atom.bytesLeft())
        val eventMessage = EventMessage(schemeIdUri, value, durationMs, id, messageData)
        val encodedEventMessage = ParsableByteArray(eventMessageEncoder.encode(eventMessage))
        val sampleSize = encodedEventMessage.bytesLeft()

        // Output the sample data.
        for (emsgTrackOutput in emsgTrackOutputs) {
            encodedEventMessage.position = 0
            emsgTrackOutput!!.sampleData(encodedEventMessage, sampleSize)
        }

        // Output the sample metadata. This is made a little complicated because emsg-v0 atoms
        // have presentation time *delta* while v1 atoms have absolute presentation time.
        if (sampleTimeUs == C.TIME_UNSET) {
            // We need the first sample timestamp in the segment before we can output the metadata.
            pendingMetadataSampleInfos.addLast(
                MetadataSampleInfo(presentationTimeDeltaUs, sampleSize)
            )
            pendingMetadataSampleBytes += sampleSize
        } else {
            if (timestampAdjuster != null) {
                sampleTimeUs = timestampAdjuster.adjustSampleTimestamp(sampleTimeUs)
            }
            for (emsgTrackOutput in emsgTrackOutputs) {
                emsgTrackOutput!!.sampleMetadata(
                    sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize,  /* offset= */0, null
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun readEncryptionData(input: ExtractorInput) {
        var nextTrackBundle: TrackBundle? = null
        var nextDataOffset = Long.MAX_VALUE
        val trackBundlesSize = trackBundles.size()
        for (i in 0 until trackBundlesSize) {
            val trackFragment = trackBundles.valueAt(i).fragment
            if (trackFragment.sampleEncryptionDataNeedsFill
                && trackFragment.auxiliaryDataPosition < nextDataOffset
            ) {
                nextDataOffset = trackFragment.auxiliaryDataPosition
                nextTrackBundle = trackBundles.valueAt(i)
            }
        }
        if (nextTrackBundle == null) {
            parserState = STATE_READING_SAMPLE_START
            return
        }
        val bytesToSkip = (nextDataOffset - input.position).toInt()
        if (bytesToSkip < 0) {
            throw ParserException("Offset to encryption data was negative.")
        }
        input.skipFully(bytesToSkip)
        nextTrackBundle.fragment.fillEncryptionData(input)
    }

    /**
     * Attempts to read the next sample in the current mdat atom. The read sample may be output or
     * skipped.
     *
     *
     * If there are no more samples in the current mdat atom then the parser state is transitioned
     * to [.STATE_READING_ATOM_HEADER] and `false` is returned.
     *
     *
     * It is possible for a sample to be partially read in the case that an exception is thrown. In
     * this case the method can be called again to read the remainder of the sample.
     *
     * @param input The [ExtractorInput] from which to read data.
     * @return Whether a sample was read. The read sample may have been output or skipped. False
     * indicates that there are no samples left to read in the current mdat.
     * @throws IOException If an error occurs reading from the input.
     */
    @Throws(IOException::class)
    private fun readSample(input: ExtractorInput): Boolean {
        if (input.position == input.length) {
            enterReadingAtomHeaderState()
            return false
        }
        var trackBundle = currentTrackBundle
        if (trackBundle == null) {
            trackBundle = getNextTrackBundle(trackBundles)
            if (trackBundle == null) {
                // We've run out of samples in the current mdat. Discard any trailing data and prepare to
                // read the header of the next atom.
                val bytesToSkip = (endOfMdatPosition - input.position).toInt()
                if (bytesToSkip < 0) {
                    throw ParserException("Offset to end of mdat was negative.")
                }
                input.skipFully(bytesToSkip)
                enterReadingAtomHeaderState()
                return false
            }
            val nextDataPosition = trackBundle.currentSampleOffset
            // We skip bytes preceding the next sample to read.
            var bytesToSkip = (nextDataPosition - input.position).toInt()
            if (bytesToSkip < 0) {
                // Assume the sample data must be contiguous in the mdat with no preceding data.
                Log.w(TAG, "Ignoring negative offset to sample data.")
                bytesToSkip = 0
            }
            input.skipFully(bytesToSkip)
            currentTrackBundle = trackBundle
        }
        if (parserState == STATE_READING_SAMPLE_START) {
            sampleSize = trackBundle.currentSampleSize
            if (trackBundle.currentSampleIndex < trackBundle.firstSampleToOutputIndex) {
                input.skipFully(sampleSize)
                trackBundle.skipSampleEncryptionData()
                if (!trackBundle.next()) {
                    currentTrackBundle = null
                }
                parserState = STATE_READING_SAMPLE_START
                return true
            }
            if (trackBundle.moovSampleTable.track.sampleTransformation
                == Track.TRANSFORMATION_CEA608_CDAT
            ) {
                sampleSize -= Atom.HEADER_SIZE
                input.skipFully(Atom.HEADER_SIZE)
            }
            if (MimeTypes.AUDIO_AC4 == trackBundle.moovSampleTable.track.format.sampleMimeType) {
                // AC4 samples need to be prefixed with a clear sample header.
                sampleBytesWritten = trackBundle.outputSampleEncryptionData(sampleSize, Ac4Util.SAMPLE_HEADER_SIZE)
                Ac4Util.getAc4SampleHeader(sampleSize, scratch)
                trackBundle.output.sampleData(scratch, Ac4Util.SAMPLE_HEADER_SIZE)
                sampleBytesWritten += Ac4Util.SAMPLE_HEADER_SIZE
            } else {
                sampleBytesWritten = trackBundle.outputSampleEncryptionData(sampleSize,  /* clearHeaderSize= */0)
            }
            sampleSize += sampleBytesWritten
            parserState = STATE_READING_SAMPLE_CONTINUE
            sampleCurrentNalBytesRemaining = 0
        }
        val track = trackBundle.moovSampleTable.track
        val output = trackBundle.output
        var sampleTimeUs = trackBundle.currentSamplePresentationTimeUs
        if (timestampAdjuster != null) {
            sampleTimeUs = timestampAdjuster.adjustSampleTimestamp(sampleTimeUs)
        }
        if (track.nalUnitLengthFieldLength != 0) {
            // Zero the top three bytes of the array that we'll use to decode nal unit lengths, in case
            // they're only 1 or 2 bytes long.
            val nalPrefixData = nalPrefix.data
            nalPrefixData[0] = 0
            nalPrefixData[1] = 0
            nalPrefixData[2] = 0
            val nalUnitPrefixLength = track.nalUnitLengthFieldLength + 1
            val nalUnitLengthFieldLengthDiff = 4 - track.nalUnitLengthFieldLength
            // NAL units are length delimited, but the decoder requires start code delimited units.
            // Loop until we've written the sample to the track output, replacing length delimiters with
            // start codes as we encounter them.
            while (sampleBytesWritten < sampleSize) {
                if (sampleCurrentNalBytesRemaining == 0) {
                    // Read the NAL length so that we know where we find the next one, and its type.
                    input.readFully(nalPrefixData, nalUnitLengthFieldLengthDiff, nalUnitPrefixLength)
                    nalPrefix.position = 0
                    val nalLengthInt = nalPrefix.readInt()
                    if (nalLengthInt < 1) {
                        throw ParserException("Invalid NAL length")
                    }
                    sampleCurrentNalBytesRemaining = nalLengthInt - 1
                    // Write a start code for the current NAL unit.
                    nalStartCode.position = 0
                    output.sampleData(nalStartCode, 4)
                    // Write the NAL unit type byte.
                    output.sampleData(nalPrefix, 1)
                    processSeiNalUnitPayload = (ceaTrackOutputs.size > 0
                            && NalUnitUtil.isNalUnitSei(track.format.sampleMimeType, nalPrefixData[4]))
                    sampleBytesWritten += 5
                    sampleSize += nalUnitLengthFieldLengthDiff
                } else {
                    var writtenBytes: Int
                    if (processSeiNalUnitPayload) {
                        // Read and write the payload of the SEI NAL unit.
                        nalBuffer.reset(sampleCurrentNalBytesRemaining)
                        input.readFully(nalBuffer.data, 0, sampleCurrentNalBytesRemaining)
                        output.sampleData(nalBuffer, sampleCurrentNalBytesRemaining)
                        writtenBytes = sampleCurrentNalBytesRemaining
                        // Unescape and process the SEI NAL unit.
                        val unescapedLength = NalUnitUtil.unescapeStream(nalBuffer.data, nalBuffer.limit())
                        // If the format is H.265/HEVC the NAL unit header has two bytes so skip one more byte.
                        nalBuffer.position = if (MimeTypes.VIDEO_H265 == track.format.sampleMimeType) 1 else 0
                        nalBuffer.setLimit(unescapedLength)
                        CeaUtil.consume(
                            sampleTimeUs,
                            nalBuffer,
                            ceaTrackOutputs as? Array<out TrackOutput> ?: return false
                        )
                    } else {
                        // Write the payload of the NAL unit.
                        if (input.position == input.length) {
                            enterReadingAtomHeaderState()
                            return false
                        }
                        writtenBytes = output.sampleData(input, sampleCurrentNalBytesRemaining, true)
                    }
                    sampleBytesWritten += writtenBytes
                    sampleCurrentNalBytesRemaining -= writtenBytes
                }
            }
        } else {
            while (sampleBytesWritten < sampleSize) {
                if (input.position == input.length) {
                    enterReadingAtomHeaderState()
                    return false
                }
                val writtenBytes = output.sampleData(input, sampleSize - sampleBytesWritten, true)
                sampleBytesWritten += writtenBytes
            }
        }
        @BufferFlags val sampleFlags = trackBundle.currentSampleFlags

        // Encryption data.
        var cryptoData: CryptoData? = null
        val encryptionBox = trackBundle.encryptionBoxIfEncrypted
        if (encryptionBox != null) {
            cryptoData = encryptionBox.cryptoData
        }
        output.sampleMetadata(sampleTimeUs, sampleFlags, sampleSize, 0, cryptoData)

        // After we have the sampleTimeUs, we can commit all the pending metadata samples
        outputPendingMetadataSamples(sampleTimeUs)
        if (!trackBundle.next()) {
            currentTrackBundle = null
        }
        parserState = STATE_READING_SAMPLE_START
        return true
    }

    private fun outputPendingMetadataSamples(sampleTimeUs: Long) {
        while (!pendingMetadataSampleInfos.isEmpty()) {
            val sampleInfo = pendingMetadataSampleInfos.removeFirst()
            pendingMetadataSampleBytes -= sampleInfo.size
            var metadataTimeUs = sampleTimeUs + sampleInfo.presentationTimeDeltaUs
            if (timestampAdjuster != null) {
                metadataTimeUs = timestampAdjuster.adjustSampleTimestamp(metadataTimeUs)
            }
            for (emsgTrackOutput in emsgTrackOutputs) {
                emsgTrackOutput!!.sampleMetadata(
                    metadataTimeUs,
                    C.BUFFER_FLAG_KEY_FRAME,
                    sampleInfo.size,
                    pendingMetadataSampleBytes,
                    null
                )
            }
        }
    }

    /**
     * Holds data corresponding to a metadata sample.
     */
    private class MetadataSampleInfo(val presentationTimeDeltaUs: Long, val size: Int)

    /**
     * Holds data corresponding to a single track.
     */
    private class TrackBundle(
        val output: TrackOutput,
        var moovSampleTable: TrackSampleTable,
        var defaultSampleValues: DefaultSampleValues
    ) {
        val fragment: TrackFragment
        val scratch: ParsableByteArray
        var currentSampleIndex = 0
        var currentSampleInTrackRun = 0
        var currentTrackRunIndex = 0
        var firstSampleToOutputIndex = 0
        private val encryptionSignalByte: ParsableByteArray
        private val defaultInitializationVector: ParsableByteArray
        var currentlyInFragment = false
        fun reset(moovSampleTable: TrackSampleTable, defaultSampleValues: DefaultSampleValues) {
            this.moovSampleTable = moovSampleTable
            this.defaultSampleValues = defaultSampleValues
            output.format(moovSampleTable.track.format)
            resetFragmentInfo()
        }

        fun updateDrmInitData(drmInitData: DrmInitData) {
            val encryptionBox = moovSampleTable.track.getSampleDescriptionEncryptionBox(
                Util.castNonNull(fragment.header).sampleDescriptionIndex
            )
            val schemeType = encryptionBox?.schemeType
            val updatedDrmInitData = drmInitData.copyWithSchemeType(schemeType)
            val format = moovSampleTable.track.format.buildUpon().setDrmInitData(updatedDrmInitData).build()
            output.format(format)
        }

        /**
         * Resets the current fragment, sample indices and [.currentlyInFragment] boolean.
         */
        fun resetFragmentInfo() {
            fragment.reset()
            currentSampleIndex = 0
            currentTrackRunIndex = 0
            currentSampleInTrackRun = 0
            firstSampleToOutputIndex = 0
            currentlyInFragment = false
        }

        /**
         * Advances [.firstSampleToOutputIndex] to point to the sync sample before the specified
         * seek time in the current fragment.
         *
         * @param timeUs The seek time, in microseconds.
         */
        fun seek(timeUs: Long) {
            var searchIndex = currentSampleIndex
            while (searchIndex < fragment.sampleCount
                && fragment.getSamplePresentationTimeUs(searchIndex) < timeUs
            ) {
                if (fragment.sampleIsSyncFrameTable[searchIndex]) {
                    firstSampleToOutputIndex = searchIndex
                }
                searchIndex++
            }
        }

        /**
         * Returns the presentation time of the current sample in microseconds.
         */
        val currentSamplePresentationTimeUs: Long
            get() = if (!currentlyInFragment) moovSampleTable.timestampsUs[currentSampleIndex] else fragment.getSamplePresentationTimeUs(
                currentSampleIndex
            )

        /**
         * Returns the byte offset of the current sample.
         */
        val currentSampleOffset: Long
            get() = if (!currentlyInFragment) moovSampleTable.offsets[currentSampleIndex] else fragment.trunDataPosition[currentTrackRunIndex]

        /**
         * Returns the size of the current sample in bytes.
         */
        val currentSampleSize: Int
            get() = if (!currentlyInFragment) moovSampleTable.sizes[currentSampleIndex] else fragment.sampleSizeTable[currentSampleIndex]

        /**
         * Returns the [C.BufferFlags] corresponding to the current sample.
         */
        @get:BufferFlags
        val currentSampleFlags: Int
            get() {
                var flags =
                    if (!currentlyInFragment) moovSampleTable.flags[currentSampleIndex] else if (fragment.sampleIsSyncFrameTable[currentSampleIndex]) C.BUFFER_FLAG_KEY_FRAME else 0
                if (encryptionBoxIfEncrypted != null) {
                    flags = flags or C.BUFFER_FLAG_ENCRYPTED
                }
                return flags
            }

        /**
         * Advances the indices in the bundle to point to the next sample in the sample table (if it has
         * not reached the fragments yet) or in the current fragment.
         *
         *
         * If the current sample is the last one in the sample table, then the advanced state will be
         * `currentSampleIndex == moovSampleTable.sampleCount`. If the current sample is the last
         * one in the current fragment, then the advanced state will be `currentSampleIndex ==
         * fragment.sampleCount`, `currentTrackRunIndex == fragment.trunCount` and `#currentSampleInTrackRun == 0`.
         *
         * @return Whether this [TrackBundle] can be used to read the next sample without
         * recomputing the next [TrackBundle].
         */
        operator fun next(): Boolean {
            currentSampleIndex++
            if (!currentlyInFragment) {
                return false
            }
            currentSampleInTrackRun++
            if (currentSampleInTrackRun == fragment.trunLength[currentTrackRunIndex]) {
                currentTrackRunIndex++
                currentSampleInTrackRun = 0
                return false
            }
            return true
        }

        /**
         * Outputs the encryption data for the current sample.
         *
         *
         * This is not supported yet for samples specified in the sample table.
         *
         * @param sampleSize      The size of the current sample in bytes, excluding any additional clear
         * header that will be prefixed to the sample by the extractor.
         * @param clearHeaderSize The size of a clear header that will be prefixed to the sample by the
         * extractor, or 0.
         * @return The number of written bytes.
         */
        fun outputSampleEncryptionData(sampleSize: Int, clearHeaderSize: Int): Int {
            val encryptionBox = encryptionBoxIfEncrypted ?: return 0
            val initializationVectorData: ParsableByteArray
            val vectorSize: Int
            if (encryptionBox.perSampleIvSize != 0) {
                initializationVectorData = fragment.sampleEncryptionData
                vectorSize = encryptionBox.perSampleIvSize
            } else {
                // The default initialization vector should be used.
                val initVectorData = Util.castNonNull(encryptionBox.defaultInitializationVector)
                defaultInitializationVector.reset(initVectorData, initVectorData.size)
                initializationVectorData = defaultInitializationVector
                vectorSize = initVectorData.size
            }
            val haveSubsampleEncryptionTable = fragment.sampleHasSubsampleEncryptionTable(currentSampleIndex)
            val writeSubsampleEncryptionData = haveSubsampleEncryptionTable || clearHeaderSize != 0

            // Write the signal byte, containing the vector size and the subsample encryption flag.
            encryptionSignalByte.data[0] = (vectorSize or if (writeSubsampleEncryptionData) 0x80 else 0).toByte()
            encryptionSignalByte.position = 0
            output.sampleData(encryptionSignalByte, 1, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION)
            // Write the vector.
            output.sampleData(
                initializationVectorData, vectorSize, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION
            )
            if (!writeSubsampleEncryptionData) {
                return 1 + vectorSize
            }
            if (!haveSubsampleEncryptionTable) {
                // The sample is fully encrypted, except for the additional clear header that the extractor
                // is going to prefix. We need to synthesize subsample encryption data that takes the header
                // into account.
                scratch.reset(SINGLE_SUBSAMPLE_ENCRYPTION_DATA_LENGTH)
                // subsampleCount = 1 (unsigned short)
                val data = scratch.data
                data[0] = 0.toByte()
                data[1] = 1.toByte()
                // clearDataSize = clearHeaderSize (unsigned short)
                data[2] = (clearHeaderSize shr 8 and 0xFF).toByte()
                data[3] = (clearHeaderSize and 0xFF).toByte()
                // encryptedDataSize = sampleSize (unsigned int)
                data[4] = (sampleSize shr 24 and 0xFF).toByte()
                data[5] = (sampleSize shr 16 and 0xFF).toByte()
                data[6] = (sampleSize shr 8 and 0xFF).toByte()
                data[7] = (sampleSize and 0xFF).toByte()
                output.sampleData(
                    scratch,
                    SINGLE_SUBSAMPLE_ENCRYPTION_DATA_LENGTH,
                    TrackOutput.SAMPLE_DATA_PART_ENCRYPTION
                )
                return 1 + vectorSize + SINGLE_SUBSAMPLE_ENCRYPTION_DATA_LENGTH
            }
            var subsampleEncryptionData = fragment.sampleEncryptionData
            val subsampleCount = subsampleEncryptionData.readUnsignedShort()
            subsampleEncryptionData.skipBytes(-2)
            val subsampleDataLength = 2 + 6 * subsampleCount
            if (clearHeaderSize != 0) {
                // We need to account for the additional clear header by adding clearHeaderSize to
                // clearDataSize for the first subsample specified in the subsample encryption data.
                scratch.reset(subsampleDataLength)
                val scratchData = scratch.data
                subsampleEncryptionData.readBytes(scratchData,  /* offset= */0, subsampleDataLength)
                val clearDataSize: Int =
                    (scratchData[2] and 0xFF.toByte()).toInt() shl 8 or (scratchData[3] and 0xFF.toByte()).toInt()
                val adjustedClearDataSize = clearDataSize + clearHeaderSize
                scratchData[2] = (adjustedClearDataSize shr 8 and 0xFF).toByte()
                scratchData[3] = (adjustedClearDataSize and 0xFF).toByte()
                subsampleEncryptionData = scratch
            }
            output.sampleData(
                subsampleEncryptionData, subsampleDataLength, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION
            )
            return 1 + vectorSize + subsampleDataLength
        }

        /**
         * Skips the encryption data for the current sample.
         *
         *
         * This is not supported yet for samples specified in the sample table.
         */
        fun skipSampleEncryptionData() {
            val encryptionBox = encryptionBoxIfEncrypted ?: return
            val sampleEncryptionData = fragment.sampleEncryptionData
            if (encryptionBox.perSampleIvSize != 0) {
                sampleEncryptionData.skipBytes(encryptionBox.perSampleIvSize)
            }
            if (fragment.sampleHasSubsampleEncryptionTable(currentSampleIndex)) {
                sampleEncryptionData.skipBytes(6 * sampleEncryptionData.readUnsignedShort())
            }
        }

        // Encryption is not supported yet for samples specified in the sample table.
        val encryptionBoxIfEncrypted: TrackEncryptionBox?
            get() {
                if (!currentlyInFragment) {
                    // Encryption is not supported yet for samples specified in the sample table.
                    return null
                }
                val sampleDescriptionIndex = Util.castNonNull(fragment.header).sampleDescriptionIndex
                val encryptionBox =
                    if (fragment.trackEncryptionBox != null) fragment.trackEncryptionBox else moovSampleTable.track.getSampleDescriptionEncryptionBox(
                        sampleDescriptionIndex
                    )
                return if (encryptionBox != null && encryptionBox.isEncrypted) encryptionBox else null
            }

        companion object {
            private const val SINGLE_SUBSAMPLE_ENCRYPTION_DATA_LENGTH = 8
        }

        init {
            fragment = TrackFragment()
            scratch = ParsableByteArray()
            encryptionSignalByte = ParsableByteArray(1)
            defaultInitializationVector = ParsableByteArray()
            reset(moovSampleTable, defaultSampleValues)
        }
    }

    companion object {
        /**
         * Factory for [IvfExtractor] instances.
         */
        val FACTORY = ExtractorsFactory { arrayOf<Extractor>(IvfExtractor()) }

        /**
         * Flag to work around an issue in some video streams where every frame is marked as a sync frame.
         * The workaround overrides the sync frame flags in the stream, forcing them to false except for
         * the first sample in each segment.
         *
         *
         * This flag does nothing if the stream is not a video stream.
         */
        const val FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME = 1

        /**
         * Flag to ignore any tfdt boxes in the stream.
         */
        const val FLAG_WORKAROUND_IGNORE_TFDT_BOX = 1 shl 1 // 2

        /**
         * Flag to indicate that the extractor should output an event message metadata track. Any event
         * messages in the stream will be delivered as samples to this track.
         */
        const val FLAG_ENABLE_EMSG_TRACK = 1 shl 2 // 4

        /**
         * Flag to ignore any edit lists in the stream.
         */
        const val FLAG_WORKAROUND_IGNORE_EDIT_LISTS = 1 shl 4 // 16
        private const val TAG = "FragmentedMp4Extractor"
        private const val SAMPLE_GROUP_TYPE_seig = 0x73656967
        private val PIFF_SAMPLE_ENCRYPTION_BOX_EXTENDED_TYPE =
            byteArrayOf(-94, 57, 79, 82, 90, -101, 79, 20, -94, 68, 108, 66, 124, 100, -115, -12)

        // Extra tracks constants.
        private val EMSG_FORMAT = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build()
        private const val EXTRA_TRACKS_BASE_ID = 100

        // Parser states.
        private const val STATE_READING_ATOM_HEADER = 0
        private const val STATE_READING_ATOM_PAYLOAD = 1
        private const val STATE_READING_ENCRYPTION_DATA = 2
        private const val STATE_READING_SAMPLE_START = 3
        private const val STATE_READING_SAMPLE_CONTINUE = 4

        /**
         * Parses a trex atom (defined in 14496-12).
         */
        private fun parseTrex(trex: ParsableByteArray): Pair<Int, DefaultSampleValues> {
            trex.position = Atom.FULL_HEADER_SIZE
            val trackId = trex.readInt()
            val defaultSampleDescriptionIndex = trex.readInt() - 1
            val defaultSampleDuration = trex.readInt()
            val defaultSampleSize = trex.readInt()
            val defaultSampleFlags = trex.readInt()
            return Pair.create(
                trackId, DefaultSampleValues(
                    defaultSampleDescriptionIndex,
                    defaultSampleDuration, defaultSampleSize, defaultSampleFlags
                )
            )
        }

        /**
         * Parses an mehd atom (defined in 14496-12).
         */
        private fun parseMehd(mehd: ParsableByteArray): Long {
            mehd.position = Atom.HEADER_SIZE
            val fullAtom = mehd.readInt()
            val version = parseFullAtomVersion(fullAtom)
            return if (version == 0) mehd.readUnsignedInt() else mehd.readUnsignedLongToLong()
        }

        @Throws(ParserException::class)
        private fun parseMoof(
            moof: Atom.ContainerAtom, trackBundleArray: SparseArray<TrackBundle>,
            @Flags flags: Int, extendedTypeScratch: ByteArray
        ) {
            val moofContainerChildrenSize = moof.containerChildren.size
            for (i in 0 until moofContainerChildrenSize) {
                val child = moof.containerChildren[i]
                // TODO: Support multiple traf boxes per track in a single moof.
                if (child.type == Atom.TYPE_traf) {
                    parseTraf(child, trackBundleArray, flags, extendedTypeScratch)
                }
            }
        }

        /**
         * Parses a traf atom (defined in 14496-12).
         */
        @Throws(ParserException::class)
        private fun parseTraf(
            traf: Atom.ContainerAtom, trackBundleArray: SparseArray<TrackBundle>,
            @Flags flags: Int, extendedTypeScratch: ByteArray
        ) {
            val tfhd = Assertions.checkNotNull(traf.getLeafAtomOfType(Atom.TYPE_tfhd))
            val trackBundle = parseTfhd(tfhd.data, trackBundleArray) ?: return
            val fragment = trackBundle.fragment
            val fragmentDecodeTime = fragment.nextFragmentDecodeTime
            val fragmentDecodeTimeIncludesMoov = fragment.nextFragmentDecodeTimeIncludesMoov
            trackBundle.resetFragmentInfo()
            trackBundle.currentlyInFragment = true
            val tfdtAtom = traf.getLeafAtomOfType(Atom.TYPE_tfdt)
            if (tfdtAtom != null && flags and FLAG_WORKAROUND_IGNORE_TFDT_BOX == 0) {
                fragment.nextFragmentDecodeTime = parseTfdt(tfdtAtom.data)
                fragment.nextFragmentDecodeTimeIncludesMoov = true
            } else {
                fragment.nextFragmentDecodeTime = fragmentDecodeTime
                fragment.nextFragmentDecodeTimeIncludesMoov = fragmentDecodeTimeIncludesMoov
            }
            parseTruns(traf, trackBundle, flags)
            val encryptionBox = trackBundle.moovSampleTable.track.getSampleDescriptionEncryptionBox(
                Assertions.checkNotNull(fragment.header).sampleDescriptionIndex
            )
            val saiz = traf.getLeafAtomOfType(Atom.TYPE_saiz)
            if (saiz != null) {
                parseSaiz(Assertions.checkNotNull(encryptionBox), saiz.data, fragment)
            }
            val saio = traf.getLeafAtomOfType(Atom.TYPE_saio)
            if (saio != null) {
                parseSaio(saio.data, fragment)
            }
            val senc = traf.getLeafAtomOfType(Atom.TYPE_senc)
            if (senc != null) {
                parseSenc(senc.data, fragment)
            }
            parseSampleGroups(traf, encryptionBox?.schemeType, fragment)
            val leafChildrenSize = traf.leafChildren.size
            for (i in 0 until leafChildrenSize) {
                val atom = traf.leafChildren[i]
                if (atom.type == Atom.TYPE_uuid) {
                    parseUuid(atom.data, fragment, extendedTypeScratch)
                }
            }
        }

        @Throws(ParserException::class)
        private fun parseTruns(traf: Atom.ContainerAtom, trackBundle: TrackBundle, @Flags flags: Int) {
            var trunCount = 0
            var totalSampleCount = 0
            val leafChildren: List<Atom.LeafAtom> = traf.leafChildren
            val leafChildrenSize = leafChildren.size
            for (i in 0 until leafChildrenSize) {
                val atom = leafChildren[i]
                if (atom.type == Atom.TYPE_trun) {
                    val trunData = atom.data
                    trunData.position = Atom.FULL_HEADER_SIZE
                    val trunSampleCount = trunData.readUnsignedIntToInt()
                    if (trunSampleCount > 0) {
                        totalSampleCount += trunSampleCount
                        trunCount++
                    }
                }
            }
            trackBundle.currentTrackRunIndex = 0
            trackBundle.currentSampleInTrackRun = 0
            trackBundle.currentSampleIndex = 0
            trackBundle.fragment.initTables(trunCount, totalSampleCount)
            var trunIndex = 0
            var trunStartPosition = 0
            for (i in 0 until leafChildrenSize) {
                val trun = leafChildren[i]
                if (trun.type == Atom.TYPE_trun) {
                    trunStartPosition = parseTrun(trackBundle, trunIndex++, flags, trun.data, trunStartPosition)
                }
            }
        }

        @Throws(ParserException::class)
        private fun parseSaiz(
            encryptionBox: TrackEncryptionBox, saiz: ParsableByteArray,
            out: TrackFragment
        ) {
            val vectorSize = encryptionBox.perSampleIvSize
            saiz.position = Atom.HEADER_SIZE
            val fullAtom = saiz.readInt()
            val flags = parseFullAtomFlags(fullAtom)
            if (flags and 0x01 == 1) {
                saiz.skipBytes(8)
            }
            val defaultSampleInfoSize = saiz.readUnsignedByte()
            val sampleCount = saiz.readUnsignedIntToInt()
            if (sampleCount > out.sampleCount) {
                throw ParserException(
                    "Saiz sample count "
                            + sampleCount
                            + " is greater than fragment sample count"
                            + out.sampleCount
                )
            }
            var totalSize = 0
            if (defaultSampleInfoSize == 0) {
                val sampleHasSubsampleEncryptionTable = out.sampleHasSubsampleEncryptionTable
                for (i in 0 until sampleCount) {
                    val sampleInfoSize = saiz.readUnsignedByte()
                    totalSize += sampleInfoSize
                    sampleHasSubsampleEncryptionTable[i] = sampleInfoSize > vectorSize
                }
            } else {
                val subsampleEncryption = defaultSampleInfoSize > vectorSize
                totalSize += defaultSampleInfoSize * sampleCount
                Arrays.fill(out.sampleHasSubsampleEncryptionTable, 0, sampleCount, subsampleEncryption)
            }
            Arrays.fill(out.sampleHasSubsampleEncryptionTable, sampleCount, out.sampleCount, false)
            if (totalSize > 0) {
                out.initEncryptionData(totalSize)
            }
        }

        /**
         * Parses a saio atom (defined in 14496-12).
         *
         * @param saio The saio atom to decode.
         * @param out  The [TrackFragment] to populate with data from the saio atom.
         */
        @Throws(ParserException::class)
        private fun parseSaio(saio: ParsableByteArray, out: TrackFragment) {
            saio.position = Atom.HEADER_SIZE
            val fullAtom = saio.readInt()
            val flags = parseFullAtomFlags(fullAtom)
            if (flags and 0x01 == 1) {
                saio.skipBytes(8)
            }
            val entryCount = saio.readUnsignedIntToInt()
            if (entryCount != 1) {
                // We only support one trun element currently, so always expect one entry.
                throw ParserException("Unexpected saio entry count: $entryCount")
            }
            val version = parseFullAtomVersion(fullAtom)
            out.auxiliaryDataPosition += if (version == 0) saio.readUnsignedInt() else saio.readUnsignedLongToLong()
        }

        /**
         * Parses a tfhd atom (defined in 14496-12), updates the corresponding [TrackFragment] and
         * returns the [TrackBundle] of the corresponding [Track]. If the tfhd does not refer
         * to any [TrackBundle], `null` is returned and no changes are made.
         *
         * @param tfhd         The tfhd atom to decode.
         * @param trackBundles The track bundles, one of which corresponds to the tfhd atom being parsed.
         * @return The [TrackBundle] to which the [TrackFragment] belongs, or null if the tfhd
         * does not refer to any [TrackBundle].
         */
        private fun parseTfhd(
            tfhd: ParsableByteArray, trackBundles: SparseArray<TrackBundle>
        ): TrackBundle? {
            tfhd.position = Atom.HEADER_SIZE
            val fullAtom = tfhd.readInt()
            val atomFlags = parseFullAtomFlags(fullAtom)
            val trackId = tfhd.readInt()
            val trackBundle = getTrackBundle(trackBundles, trackId) ?: return null
            if (atomFlags and 0x01 /* base_data_offset_present */ != 0) {
                val baseDataPosition = tfhd.readUnsignedLongToLong()
                trackBundle.fragment.dataPosition = baseDataPosition
                trackBundle.fragment.auxiliaryDataPosition = baseDataPosition
            }
            val defaultSampleValues = trackBundle.defaultSampleValues
            val defaultSampleDescriptionIndex =
                if (atomFlags and 0x02 /* default_sample_description_index_present */ != 0) tfhd.readInt() - 1 else defaultSampleValues.sampleDescriptionIndex
            val defaultSampleDuration =
                if (atomFlags and 0x08 /* default_sample_duration_present */ != 0) tfhd.readInt() else defaultSampleValues.duration
            val defaultSampleSize =
                if (atomFlags and 0x10 /* default_sample_size_present */ != 0) tfhd.readInt() else defaultSampleValues.size
            val defaultSampleFlags =
                if (atomFlags and 0x20 /* default_sample_flags_present */ != 0) tfhd.readInt() else defaultSampleValues.flags
            trackBundle.fragment.header = DefaultSampleValues(
                defaultSampleDescriptionIndex,
                defaultSampleDuration, defaultSampleSize, defaultSampleFlags
            )
            return trackBundle
        }

        private fun getTrackBundle(
            trackBundles: SparseArray<TrackBundle>, trackId: Int
        ): TrackBundle? {
            return if (trackBundles.size() == 1) {
                // Ignore track id if there is only one track. This is either because we have a side-loaded
                // track or to cope with non-matching track indices (see
                // https://github.com/google/ExoPlayer/issues/4083).
                trackBundles.valueAt( /* index= */0)
            } else trackBundles[trackId]
        }

        /**
         * Parses a tfdt atom (defined in 14496-12).
         *
         * @return baseMediaDecodeTime The sum of the decode durations of all earlier samples in the
         * media, expressed in the media's timescale.
         */
        private fun parseTfdt(tfdt: ParsableByteArray): Long {
            tfdt.position = Atom.HEADER_SIZE
            val fullAtom = tfdt.readInt()
            val version = parseFullAtomVersion(fullAtom)
            return if (version == 1) tfdt.readUnsignedLongToLong() else tfdt.readUnsignedInt()
        }

        /**
         * Parses a trun atom (defined in 14496-12).
         *
         * @param trackBundle The [TrackBundle] that contains the [TrackFragment] into which
         * parsed data should be placed.
         * @param index       Index of the track run in the fragment.
         * @param flags       Flags to allow any required workaround to be executed.
         * @param trun        The trun atom to decode.
         * @return The starting position of samples for the next run.
         */
        @Throws(ParserException::class)
        private fun parseTrun(
            trackBundle: TrackBundle,
            index: Int,
            @Flags flags: Int,
            trun: ParsableByteArray,
            trackRunStart: Int
        ): Int {
            trun.position = Atom.HEADER_SIZE
            val fullAtom = trun.readInt()
            val atomFlags = parseFullAtomFlags(fullAtom)
            val track = trackBundle.moovSampleTable.track
            val fragment = trackBundle.fragment
            val defaultSampleValues = Util.castNonNull(fragment.header)
            fragment.trunLength[index] = trun.readUnsignedIntToInt()
            fragment.trunDataPosition[index] = fragment.dataPosition
            if (atomFlags and 0x01 /* data_offset_present */ != 0) {
                fragment.trunDataPosition[index] += trun.readInt().toLong()
            }
            val firstSampleFlagsPresent = atomFlags and 0x04 /* first_sample_flags_present */ != 0
            var firstSampleFlags = defaultSampleValues.flags
            if (firstSampleFlagsPresent) {
                firstSampleFlags = trun.readInt()
            }
            val sampleDurationsPresent = atomFlags and 0x100 /* sample_duration_present */ != 0
            val sampleSizesPresent = atomFlags and 0x200 /* sample_size_present */ != 0
            val sampleFlagsPresent = atomFlags and 0x400 /* sample_flags_present */ != 0
            val sampleCompositionTimeOffsetsPresent =
                atomFlags and 0x800 /* sample_composition_time_offsets_present */ != 0

            // Offset to the entire video timeline. In the presence of B-frames this is usually used to
            // ensure that the first frame's presentation timestamp is zero.
            var edtsOffsetUs: Long = 0

            // Currently we only support a single edit that moves the entire media timeline (indicated by
            // duration == 0). Other uses of edit lists are uncommon and unsupported.
            if (track.editListDurations != null && track.editListDurations.size == 1 && track.editListDurations[0] == 0L) {
                edtsOffsetUs = Util.scaleLargeTimestamp(
                    Util.castNonNull(track.editListMediaTimes)[0], C.MICROS_PER_SECOND, track.timescale
                )
            }
            val sampleSizeTable = fragment.sampleSizeTable
            val sampleCompositionTimeOffsetUsTable = fragment.sampleCompositionTimeOffsetUsTable
            val sampleDecodingTimeUsTable = fragment.sampleDecodingTimeUsTable
            val sampleIsSyncFrameTable = fragment.sampleIsSyncFrameTable
            val workaroundEveryVideoFrameIsSyncFrame = (track.type == C.TRACK_TYPE_VIDEO
                    && flags and FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME != 0)
            val trackRunEnd = trackRunStart + fragment.trunLength[index]
            val timescale = track.timescale
            var cumulativeTime = fragment.nextFragmentDecodeTime
            for (i in trackRunStart until trackRunEnd) {
                // Use trun values if present, otherwise tfhd, otherwise trex.
                val sampleDuration =
                    checkNonNegative(if (sampleDurationsPresent) trun.readInt() else defaultSampleValues.duration)
                val sampleSize = checkNonNegative(if (sampleSizesPresent) trun.readInt() else defaultSampleValues.size)
                val sampleFlags =
                    if (sampleFlagsPresent) trun.readInt() else if (i == 0 && firstSampleFlagsPresent) firstSampleFlags else defaultSampleValues.flags
                if (sampleCompositionTimeOffsetsPresent) {
                    // The BMFF spec (ISO 14496-12) states that sample offsets should be unsigned integers in
                    // version 0 trun boxes, however a significant number of streams violate the spec and use
                    // signed integers instead. It's safe to always decode sample offsets as signed integers
                    // here, because unsigned integers will still be parsed correctly (unless their top bit is
                    // set, which is never true in practice because sample offsets are always small).
                    val sampleOffset = trun.readInt()
                    sampleCompositionTimeOffsetUsTable[i] = (sampleOffset * C.MICROS_PER_SECOND / timescale).toInt()
                } else {
                    sampleCompositionTimeOffsetUsTable[i] = 0
                }
                sampleDecodingTimeUsTable[i] =
                    Util.scaleLargeTimestamp(cumulativeTime, C.MICROS_PER_SECOND, timescale) - edtsOffsetUs
                if (!fragment.nextFragmentDecodeTimeIncludesMoov) {
                    sampleDecodingTimeUsTable[i] += trackBundle.moovSampleTable.durationUs
                }
                sampleSizeTable[i] = sampleSize
                sampleIsSyncFrameTable[i] = (sampleFlags shr 16 and 0x1 == 0
                        && (!workaroundEveryVideoFrameIsSyncFrame || i == 0))
                cumulativeTime += sampleDuration.toLong()
            }
            fragment.nextFragmentDecodeTime = cumulativeTime
            return trackRunEnd
        }

        @Throws(ParserException::class)
        private fun checkNonNegative(value: Int): Int {
            if (value < 0) {
                throw ParserException("Unexpected negative value: $value")
            }
            return value
        }

        @Throws(ParserException::class)
        private fun parseUuid(
            uuid: ParsableByteArray, out: TrackFragment,
            extendedTypeScratch: ByteArray
        ) {
            uuid.position = Atom.HEADER_SIZE
            uuid.readBytes(extendedTypeScratch, 0, 16)

            // Currently this parser only supports Microsoft's PIFF SampleEncryptionBox.
            if (!Arrays.equals(extendedTypeScratch, PIFF_SAMPLE_ENCRYPTION_BOX_EXTENDED_TYPE)) {
                return
            }

            // Except for the extended type, this box is identical to a SENC box. See "Portable encoding of
            // audio-video objects: The Protected Interoperable File Format (PIFF), John A. Bocharov et al,
            // Section 5.3.2.1."
            parseSenc(uuid, 16, out)
        }

        @Throws(ParserException::class)
        private fun parseSenc(senc: ParsableByteArray, out: TrackFragment) {
            parseSenc(senc, 0, out)
        }

        @Throws(ParserException::class)
        private fun parseSenc(senc: ParsableByteArray, offset: Int, out: TrackFragment) {
            senc.position = Atom.HEADER_SIZE + offset
            val fullAtom = senc.readInt()
            val flags = parseFullAtomFlags(fullAtom)
            if (flags and 0x01 /* override_track_encryption_box_parameters */ != 0) {
                // TODO: Implement this.
                throw ParserException("Overriding TrackEncryptionBox parameters is unsupported.")
            }
            val subsampleEncryption = flags and 0x02 /* use_subsample_encryption */ != 0
            val sampleCount = senc.readUnsignedIntToInt()
            if (sampleCount == 0) {
                // Samples are unencrypted.
                Arrays.fill(out.sampleHasSubsampleEncryptionTable, 0, out.sampleCount, false)
                return
            } else if (sampleCount != out.sampleCount) {
                throw ParserException(
                    "Senc sample count "
                            + sampleCount
                            + " is different from fragment sample count"
                            + out.sampleCount
                )
            }
            Arrays.fill(out.sampleHasSubsampleEncryptionTable, 0, sampleCount, subsampleEncryption)
            out.initEncryptionData(senc.bytesLeft())
            out.fillEncryptionData(senc)
        }

        @Throws(ParserException::class)
        private fun parseSampleGroups(
            traf: Atom.ContainerAtom, schemeType: String?, out: TrackFragment
        ) {
            // Find sbgp and sgpd boxes with grouping_type == seig.
            var sbgp: ParsableByteArray? = null
            var sgpd: ParsableByteArray? = null
            for (i in traf.leafChildren.indices) {
                val leafAtom = traf.leafChildren[i]
                val leafAtomData = leafAtom.data
                if (leafAtom.type == Atom.TYPE_sbgp) {
                    leafAtomData.position = Atom.FULL_HEADER_SIZE
                    if (leafAtomData.readInt() == SAMPLE_GROUP_TYPE_seig) {
                        sbgp = leafAtomData
                    }
                } else if (leafAtom.type == Atom.TYPE_sgpd) {
                    leafAtomData.position = Atom.FULL_HEADER_SIZE
                    if (leafAtomData.readInt() == SAMPLE_GROUP_TYPE_seig) {
                        sgpd = leafAtomData
                    }
                }
            }
            if (sbgp == null || sgpd == null) {
                return
            }
            sbgp.position = Atom.HEADER_SIZE
            val sbgpVersion = parseFullAtomVersion(sbgp.readInt())
            sbgp.skipBytes(4) // grouping_type == seig.
            if (sbgpVersion == 1) {
                sbgp.skipBytes(4) // grouping_type_parameter.
            }
            if (sbgp.readInt() != 1) { // entry_count.
                throw ParserException("Entry count in sbgp != 1 (unsupported).")
            }
            sgpd.position = Atom.HEADER_SIZE
            val sgpdVersion = parseFullAtomVersion(sgpd.readInt())
            sgpd.skipBytes(4) // grouping_type == seig.
            if (sgpdVersion == 1) {
                if (sgpd.readUnsignedInt() == 0L) {
                    throw ParserException("Variable length description in sgpd found (unsupported)")
                }
            } else if (sgpdVersion >= 2) {
                sgpd.skipBytes(4) // default_sample_description_index.
            }
            if (sgpd.readUnsignedInt() != 1L) { // entry_count.
                throw ParserException("Entry count in sgpd != 1 (unsupported).")
            }

            // CencSampleEncryptionInformationGroupEntry
            sgpd.skipBytes(1) // reserved = 0.
            val patternByte = sgpd.readUnsignedByte()
            val cryptByteBlock = patternByte and 0xF0 shr 4
            val skipByteBlock = patternByte and 0x0F
            val isProtected = sgpd.readUnsignedByte() == 1
            if (!isProtected) {
                return
            }
            val perSampleIvSize = sgpd.readUnsignedByte()
            val keyId = ByteArray(16)
            sgpd.readBytes(keyId, 0, keyId.size)
            var constantIv: ByteArray? = null
            if (perSampleIvSize == 0) {
                val constantIvSize = sgpd.readUnsignedByte()
                constantIv = ByteArray(constantIvSize)
                sgpd.readBytes(constantIv, 0, constantIvSize)
            }
            out.definesEncryptionData = true
            out.trackEncryptionBox = TrackEncryptionBox(
                isProtected, schemeType, perSampleIvSize, keyId,
                cryptByteBlock, skipByteBlock, constantIv
            )
        }

        /**
         * Parses a sidx atom (defined in 14496-12).
         *
         * @param atom          The atom data.
         * @param inputPosition The input position of the first byte after the atom.
         * @return A pair consisting of the earliest presentation time in microseconds, and the parsed
         * [ChunkIndex].
         */
        @Throws(ParserException::class)
        private fun parseSidx(atom: ParsableByteArray, inputPosition: Long): Pair<Long, ChunkIndex> {
            atom.position = Atom.HEADER_SIZE
            val fullAtom = atom.readInt()
            val version = parseFullAtomVersion(fullAtom)
            atom.skipBytes(4)
            val timescale = atom.readUnsignedInt()
            val earliestPresentationTime: Long
            var offset = inputPosition
            if (version == 0) {
                earliestPresentationTime = atom.readUnsignedInt()
                offset += atom.readUnsignedInt()
            } else {
                earliestPresentationTime = atom.readUnsignedLongToLong()
                offset += atom.readUnsignedLongToLong()
            }
            val earliestPresentationTimeUs = Util.scaleLargeTimestamp(
                earliestPresentationTime,
                C.MICROS_PER_SECOND, timescale
            )
            atom.skipBytes(2)
            val referenceCount = atom.readUnsignedShort()
            val sizes = IntArray(referenceCount)
            val offsets = LongArray(referenceCount)
            val durationsUs = LongArray(referenceCount)
            val timesUs = LongArray(referenceCount)
            var time = earliestPresentationTime
            var timeUs = earliestPresentationTimeUs
            for (i in 0 until referenceCount) {
                val firstInt = atom.readInt()
                val type = -0x80000000 and firstInt
                if (type != 0) {
                    throw ParserException("Unhandled indirect reference")
                }
                val referenceDuration = atom.readUnsignedInt()
                sizes[i] = 0x7FFFFFFF and firstInt
                offsets[i] = offset

                // Calculate time and duration values such that any rounding errors are consistent. i.e. That
                // timesUs[i] + durationsUs[i] == timesUs[i + 1].
                timesUs[i] = timeUs
                time += referenceDuration
                timeUs = Util.scaleLargeTimestamp(time, C.MICROS_PER_SECOND, timescale)
                durationsUs[i] = timeUs - timesUs[i]
                atom.skipBytes(4)
                offset += sizes[i]
            }
            return Pair.create(
                earliestPresentationTimeUs,
                ChunkIndex(sizes, offsets, durationsUs, timesUs)
            )
        }

        /**
         * Returns the [TrackBundle] whose sample has the earliest file position out of those yet to
         * be consumed, or null if all have been consumed.
         */
        private fun getNextTrackBundle(trackBundles: SparseArray<TrackBundle>): TrackBundle? {
            var nextTrackBundle: TrackBundle? = null
            var nextSampleOffset = Long.MAX_VALUE
            val trackBundlesSize = trackBundles.size()
            for (i in 0 until trackBundlesSize) {
                val trackBundle = trackBundles.valueAt(i)
                if ((!trackBundle.currentlyInFragment
                            && trackBundle.currentSampleIndex == trackBundle.moovSampleTable.sampleCount)
                    || (trackBundle.currentlyInFragment
                            && trackBundle.currentTrackRunIndex == trackBundle.fragment.trunCount)
                ) {
                    // This track sample table or fragment contains no more runs in the next mdat box.
                } else {
                    val sampleOffset = trackBundle.currentSampleOffset
                    if (sampleOffset < nextSampleOffset) {
                        nextTrackBundle = trackBundle
                        nextSampleOffset = sampleOffset
                    }
                }
            }
            return nextTrackBundle
        }

        /**
         * Returns DrmInitData from leaf atoms.
         */
        private fun getDrmInitDataFromAtoms(leafChildren: List<Atom.LeafAtom>): DrmInitData? {
            var schemeDatas: ArrayList<SchemeData>? = null
            val leafChildrenSize = leafChildren.size
            for (i in 0 until leafChildrenSize) {
                val child = leafChildren[i]
                if (child.type == Atom.TYPE_pssh) {
                    if (schemeDatas == null) {
                        schemeDatas = ArrayList()
                    }
                    val psshData = child.data.data
                    val uuid = PsshAtomUtil.parseUuid(psshData)
                    if (uuid == null) {
                        Log.w(TAG, "Skipped pssh atom (failed to extract uuid)")
                    } else {
                        schemeDatas.add(SchemeData(uuid, MimeTypes.VIDEO_MP4, psshData))
                    }
                }
            }
            return if (schemeDatas == null) null else DrmInitData(schemeDatas)
        }

        /**
         * Returns whether the extractor should decode a leaf atom with type `atom`.
         */
        private fun shouldParseLeafAtom(atom: Int): Boolean {
            return atom == Atom.TYPE_hdlr || atom == Atom.TYPE_mdhd || atom == Atom.TYPE_mvhd || atom == Atom.TYPE_sidx || atom == Atom.TYPE_stsd || atom == Atom.TYPE_stts || atom == Atom.TYPE_ctts || atom == Atom.TYPE_stsc || atom == Atom.TYPE_stsz || atom == Atom.TYPE_stz2 || atom == Atom.TYPE_stco || atom == Atom.TYPE_co64 || atom == Atom.TYPE_stss || atom == Atom.TYPE_tfdt || atom == Atom.TYPE_tfhd || atom == Atom.TYPE_tkhd || atom == Atom.TYPE_trex || atom == Atom.TYPE_trun || atom == Atom.TYPE_pssh || atom == Atom.TYPE_saiz || atom == Atom.TYPE_saio || atom == Atom.TYPE_senc || atom == Atom.TYPE_uuid || atom == Atom.TYPE_sbgp || atom == Atom.TYPE_sgpd || atom == Atom.TYPE_elst || atom == Atom.TYPE_mehd || atom == Atom.TYPE_emsg
        }

        /**
         * Returns whether the extractor should decode a container atom with type `atom`.
         */
        private fun shouldParseContainerAtom(atom: Int): Boolean {
            return atom == Atom.TYPE_moov || atom == Atom.TYPE_trak || atom == Atom.TYPE_mdia || atom == Atom.TYPE_minf || atom == Atom.TYPE_stbl || atom == Atom.TYPE_moof || atom == Atom.TYPE_traf || atom == Atom.TYPE_mvex || atom == Atom.TYPE_edts
        }
    }
    /**
     * @param flags                     Flags that control the extractor's behavior.
     * @param timestampAdjuster         Adjusts sample timestamps. May be null if no adjustment is needed.
     * @param sideloadedTrack           Sideloaded track information, in the case that the extractor will not
     * receive a moov box in the input data. Null if a moov box is expected.
     * @param closedCaptionFormats      For tracks that contain SEI messages, the formats of the closed
     * caption channels to expose.
     * @param additionalEmsgTrackOutput An extra track output that will receive all emsg messages
     * targeting the player, even if [.FLAG_ENABLE_EMSG_TRACK] is not set. Null if special
     * handling of emsg messages for players is not required.
     */
    /**
     * @param flags                Flags that control the extractor's behavior.
     * @param timestampAdjuster    Adjusts sample timestamps. May be null if no adjustment is needed.
     * @param sideloadedTrack      Sideloaded track information, in the case that the extractor will not
     * receive a moov box in the input data. Null if a moov box is expected.
     * @param closedCaptionFormats For tracks that contain SEI messages, the formats of the closed
     * caption channels to expose.
     */
    /**
     * @param flags             Flags that control the extractor's behavior.
     * @param timestampAdjuster Adjusts sample timestamps. May be null if no adjustment is needed.
     */
    /**
     * @param flags Flags that control the extractor's behavior.
     */
    /**
     * @param flags             Flags that control the extractor's behavior.
     * @param timestampAdjuster Adjusts sample timestamps. May be null if no adjustment is needed.
     * @param sideloadedTrack   Sideloaded track information, in the case that the extractor will not
     * receive a moov box in the input data. Null if a moov box is expected.
     */
    init {
        this.closedCaptionFormats = Collections.unmodifiableList(closedCaptionFormats)
        this.additionalEmsgTrackOutput = additionalEmsgTrackOutput
        eventMessageEncoder = EventMessageEncoder()
        atomHeader = ParsableByteArray(Atom.LONG_HEADER_SIZE)
        nalStartCode = ParsableByteArray(NalUnitUtil.NAL_START_CODE)
        nalPrefix = ParsableByteArray(5)
        nalBuffer = ParsableByteArray()
        scratchBytes = ByteArray(16)
        scratch = ParsableByteArray(scratchBytes)
        containerAtoms = ArrayDeque()
        pendingMetadataSampleInfos = ArrayDeque()
        trackBundles = SparseArray()
        durationUs = C.TIME_UNSET
        pendingSeekTimeUs = C.TIME_UNSET
        segmentIndexEarliestPresentationTimeUs = C.TIME_UNSET
        extractorOutput = ExtractorOutput.PLACEHOLDER
        emsgTrackOutputs = emptyArray()
        ceaTrackOutputs = emptyArray()
    }
}