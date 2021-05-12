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
import com.google.android.exoplayer2.ParserException
import com.google.android.exoplayer2.extractor.ExtractorInput
import com.google.android.exoplayer2.extractor.ivf.bean.*
import com.google.android.exoplayer2.util.ParsableByteArray
import java.io.IOException
import java.util.*

/**
 * Provides methods that peek data from an [ExtractorInput] and return whether the input
 * appears to be in MP4 format.
 */
/* package */
internal object IVFSniffer {
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
    fun sniffFragmented(input: ExtractorInput): Boolean {
        return sniffInternal(input, fragmented = true, acceptHeic = false)
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
        acceptHeic: Boolean
    ): Boolean {
        val inputLength = input.length
        var bytesToSearch =
            (if (inputLength == C.LENGTH_UNSET.toLong() || inputLength > SEARCH_LENGTH) SEARCH_LENGTH else inputLength) as Int
        val buffer = ParsableByteArray(64)
        var bytesSearched = 0
        while (bytesSearched < bytesToSearch) {
            // Read an atom header.
            var headerSize = Atom.FULL_HEADER_SIZE
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
                    buffer.data, Atom.FULL_HEADER_SIZE, Atom.LONG_HEADER_SIZE - Atom.FULL_HEADER_SIZE
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
            if (atomType == Atom.TYPE_ivfh) {
                parseIVFH(input)
                return true
            }
        }
        return false
    }

    private fun parseIVFH(input: ExtractorInput) {
        enterReadingAtomHeaderState()
        while (true) {
            when (parserState) {
                STATE_READING_ATOM_HEADER -> if (!readAtomHeader(input)) {
                    break
                }
                STATE_READING_ATOM_PAYLOAD -> readAtomPayload(input)
            }
        }
    }

    // Parser states.
    private const val STATE_READING_ATOM_HEADER = 0
    private const val STATE_READING_ATOM_PAYLOAD = 1
    private const val STATE_READING_END = 2
    private var parserState = 0
    private var atomType = 0
    private var atomSize: Long = 0
    private var atomHeaderBytesRead = 0
    private var atomData: ParsableByteArray? = null
    private val atomHeader: ParsableByteArray = ParsableByteArray(Atom.LONG_HEADER_SIZE)
    private val containerAtoms: ArrayDeque<Atom.ContainerAtom> = ArrayDeque()

    @Throws(IOException::class)
    private fun readAtomHeader(input: ExtractorInput): Boolean {
        if (input.position >= input.length) {
            return false
        }
        if (atomHeaderBytesRead == 0) {
            // Read the standard length atom header.
            if (!input.readFully(atomHeader.data, 0, Atom.FULL_HEADER_SIZE, true)) {
                return false
            }
            atomHeaderBytesRead = Atom.FULL_HEADER_SIZE
            atomHeader.position = 0
            atomSize = atomHeader.readUnsignedInt()
            atomType = atomHeader.readInt()
        }
        if (atomSize == Atom.DEFINES_LARGE_SIZE.toLong()) {
            // Read the large size.
            val headerBytesRemaining = Atom.LONG_HEADER_SIZE - Atom.FULL_HEADER_SIZE
            input.readFully(atomHeader.data, Atom.FULL_HEADER_SIZE, headerBytesRemaining)
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

        if (atomType == Atom.TYPE_ivfh) {
            val endPosition = input.position + atomSize - Atom.FULL_HEADER_SIZE
            containerAtoms.push(Atom.ContainerAtom(atomType, endPosition))
            if (atomSize == atomHeaderBytesRead.toLong()) {
                processAtomEnded(endPosition)
            } else {
                // Start reading the first child atom.
                enterReadingAtomHeaderState()
            }
        } else if (shouldParseLeafAtom(atomType)) {
            if (atomHeaderBytesRead != Atom.FULL_HEADER_SIZE) {
                throw ParserException("Leaf atom defines extended atom size (unsupported).")
            }
            if (atomSize > Int.MAX_VALUE) {
                throw ParserException("Leaf atom with length > 2147483647 (unsupported).")
            }
            val atomData = ParsableByteArray(atomSize.toInt())
            System.arraycopy(atomHeader.data, 0, atomData.data, 0, Atom.FULL_HEADER_SIZE)
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
        }
    }

    private var iflt: IVFFileLookupTableBox? = null
    private var ivfi: IVFInitBox? = null
    private var ivfm: IVFMetaBox? = null
    private val idfiList: ArrayList<IVFDataFileIndexBox> = ArrayList()
    private val imfiList: ArrayList<IVFMediaFileIndexBox> = ArrayList()

    @Throws(ParserException::class)
    private fun onContainerAtomRead(container: Atom.ContainerAtom) {
        if (container.type == Atom.TYPE_ivfh) {
            imfiList.clear()
            idfiList.clear()
            ivfi = null
            iflt = null
            ivfm = null
            container.leafChildren.forEach {
                when (it.type) {
                    Atom.TYPE_iflt -> {
                        iflt = parseIFLT(it)
                    }
                    Atom.TYPE_idfi -> {
                        idfiList.add(parseIDFI(it))
                    }
                    Atom.TYPE_imfi -> {
                        imfiList.add(parseIMFI(it))
                    }
                    Atom.TYPE_ivfi -> {
                        ivfi = parseIVFI(it)
                    }
                    Atom.TYPE_ivfm -> {
                        ivfm = parseIVFM(it)
                    }
                }
            }
        }
        if (!containerAtoms.isEmpty()) {
            containerAtoms.peek()?.add(container)
        }
    }

    private fun parseIVFM(atom: Atom.LeafAtom): IVFMetaBox {
        val data = atom.data
        data.position = Atom.FULL_HEADER_SIZE
        val ivfMetaBox = IVFMetaBox(atom.type)
        ivfMetaBox.metaCount = data.readInt()
        for (index in 0 until ivfMetaBox.metaCount) {
            val key = data.readNullTerminatedString() ?: ""
            val value = data.readNullTerminatedString() ?: ""
            ivfMetaBox.entries[key] = value
        }
        return ivfMetaBox
    }

    /**
     * 解析imfi  IVF Media File Index
     */
    private fun parseIMFI(atom: Atom.LeafAtom): IVFMediaFileIndexBox {
        val data = atom.data
        data.position = Atom.HEADER_SIZE
        val version = Atom.parseFullAtomVersion(data.readInt())
        val ivfMediaFileIndexBox = IVFMediaFileIndexBox(atom.type)
        ivfMediaFileIndexBox.fileName = data.readNullTerminatedString() ?: ""
        ivfMediaFileIndexBox.mime = data.readNullTerminatedString() ?: ""
        ivfMediaFileIndexBox.headerIndex = data.readInt()
        ivfMediaFileIndexBox.timeScale = data.readInt()
        ivfMediaFileIndexBox.indexCount = data.readInt()
        for (i in 0 until ivfMediaFileIndexBox.indexCount) {
            val index = data.readInt()
            val duration = if (version == 0) data.readInt().toLong() else data.readLong()
            ivfMediaFileIndexBox.entries[index] = duration
        }
        return ivfMediaFileIndexBox

    }

    /**
     * 解析idfi IVF Data File Index box，文件名与 IFLT 索引映射关系
     */
    private fun parseIDFI(atom: Atom.LeafAtom): IVFDataFileIndexBox {
        val data = atom.data
        data.position = Atom.FULL_HEADER_SIZE
        val ivfDataFileIndexBox = IVFDataFileIndexBox(atom.type)
        ivfDataFileIndexBox.fileName = data.readNullTerminatedString() ?: ""
        ivfDataFileIndexBox.mime = data.readNullTerminatedString() ?: ""
        ivfDataFileIndexBox.indexCount = data.readInt()
        for (index in 0 until ivfDataFileIndexBox.indexCount) {
            ivfDataFileIndexBox.entries.append(index, data.readInt())
        }
        return ivfDataFileIndexBox
    }

    /**
     * 解析ivfi IVF Init Box
     */
    private fun parseIVFI(atom: Atom.LeafAtom): IVFInitBox {
        val data = atom.data
        data.position = Atom.FULL_HEADER_SIZE
        val ivfInitBox = IVFInitBox(atom.type)
        ivfInitBox.initScriptFileName = data.readNullTerminatedString() ?: ""
        ivfInitBox.configFileName = data.readNullTerminatedString() ?: ""
        return ivfInitBox
    }

    /**
     * 解析iflt  IVF File Look-up Table box
     */
    private fun parseIFLT(atom: Atom.LeafAtom): IVFFileLookupTableBox {
        val data = atom.data
        data.position = Atom.HEADER_SIZE
        val version = Atom.parseFullAtomVersion(data.readInt())
        val ivfFileLookupTableBox = IVFFileLookupTableBox(atom.type)
        ivfFileLookupTableBox.entryCount = data.readInt()
        for (index in 0 until ivfFileLookupTableBox.entryCount) {
            val offset = if (version == 0) data.readInt().toLong() else data.readLong()
            val size = if (version == 0) data.readInt().toLong() else data.readLong()
            ivfFileLookupTableBox.entries[offset] = size
        }
        return ivfFileLookupTableBox
    }

    @Throws(IOException::class)
    private fun readAtomPayload(input: ExtractorInput) {
        val atomPayloadSize = atomSize.toInt() - atomHeaderBytesRead
        val atomData = atomData
        if (atomData != null) {
            input.readFully(atomData.data, Atom.FULL_HEADER_SIZE, atomPayloadSize)
            onLeafAtomRead(Atom.LeafAtom(atomType, atomData), input.position)
        } else {
            input.skipFully(atomPayloadSize)
        }
        processAtomEnded(input.position)
    }

    private fun enterReadingAtomHeaderState() {
        parserState = STATE_READING_ATOM_HEADER
        atomHeaderBytesRead = 0
    }

    private fun shouldParseLeafAtom(atomType: Int): Boolean {
        return atomType == Atom.TYPE_ivfi || atomType == Atom.TYPE_ivfm || atomType == Atom.TYPE_iflt || atomType == Atom.TYPE_idfi || atomType == Atom.TYPE_imfi
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