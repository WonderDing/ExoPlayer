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

import com.google.android.exoplayer2.util.ParsableByteArray
import java.util.*

/* package */ abstract class Atom(val type: Int) {
    override fun toString(): String {
        return getAtomTypeString(type)
    }

    /**
     * An MP4 atom that is a leaf.
     */
    /* package */
    internal class LeafAtom
    /**
     * @param type The type of the atom.
     * @param data The atom data.
     */(
        type: Int,
        /**
         * The atom data.
         */
        val data: ParsableByteArray
    ) : Atom(type)

    /**
     * An MP4 atom that has child atoms.
     */
    /* package */
    internal class ContainerAtom(type: Int, val endPosition: Long) : Atom(type) {
        @JvmField
        val leafChildren: MutableList<LeafAtom>

        @JvmField
        val containerChildren: MutableList<ContainerAtom>

        /**
         * Adds a child leaf to this container.
         *
         * @param atom The child to add.
         */
        fun add(atom: LeafAtom) {
            leafChildren.add(atom)
        }

        /**
         * Adds a child container to this container.
         *
         * @param atom The child to add.
         */
        fun add(atom: ContainerAtom) {
            containerChildren.add(atom)
        }

        /**
         * Returns the child leaf of the given type.
         *
         *
         * If no child exists with the given type then null is returned. If multiple children exist
         * with the given type then the first one to have been added is returned.
         *
         * @param type The leaf type.
         * @return The child leaf of the given type, or null if no such child exists.
         */
        fun getLeafAtomOfType(type: Int): LeafAtom? {
            val childrenSize = leafChildren.size
            for (i in 0 until childrenSize) {
                val atom = leafChildren[i]
                if (atom.type == type) {
                    return atom
                }
            }
            return null
        }

        /**
         * Returns the child container of the given type.
         *
         *
         * If no child exists with the given type then null is returned. If multiple children exist
         * with the given type then the first one to have been added is returned.
         *
         * @param type The container type.
         * @return The child container of the given type, or null if no such child exists.
         */
        fun getContainerAtomOfType(type: Int): ContainerAtom? {
            val childrenSize = containerChildren.size
            for (i in 0 until childrenSize) {
                val atom = containerChildren[i]
                if (atom.type == type) {
                    return atom
                }
            }
            return null
        }

        /**
         * Returns the total number of leaf/container children of this atom with the given type.
         *
         * @param type The type of child atoms to count.
         * @return The total number of leaf/container children of this atom with the given type.
         */
        fun getChildAtomOfTypeCount(type: Int): Int {
            var count = 0
            var size = leafChildren.size
            for (i in 0 until size) {
                val atom = leafChildren[i]
                if (atom.type == type) {
                    count++
                }
            }
            size = containerChildren.size
            for (i in 0 until size) {
                val atom = containerChildren[i]
                if (atom.type == type) {
                    count++
                }
            }
            return count
        }

        override fun toString(): String {
            return (getAtomTypeString(type)
                    + " leaves: " + Arrays.toString(leafChildren.toTypedArray())
                    + " containers: " + Arrays.toString(containerChildren.toTypedArray()))
        }

        /**
         * @param type The type of the atom.
         * @param endPosition The position of the first byte after the end of the atom.
         */
        init {
            leafChildren = ArrayList()
            containerChildren = ArrayList()
        }
    }

    companion object {
        /**
         * Size of an atom header, in bytes.
         */
        const val HEADER_SIZE = 8

        /**
         * Size of a full atom header, in bytes.
         */
        const val FULL_HEADER_SIZE = 12

        /**
         * Size of a long atom header, in bytes.
         */
        const val LONG_HEADER_SIZE = 16

        /**
         * Value for the size field in an atom that defines its size in the largesize field.
         */
        const val DEFINES_LARGE_SIZE = 1

        /**
         * Value for the size field in an atom that extends to the end of the file.
         */
        const val EXTENDS_TO_END_SIZE = 0
        const val TYPE_ftyp = 0x66747970
        const val TYPE_avc1 = 0x61766331
        const val TYPE_avc3 = 0x61766333
        const val TYPE_avcC = 0x61766343
        const val TYPE_hvc1 = 0x68766331
        const val TYPE_hev1 = 0x68657631
        const val TYPE_hvcC = 0x68766343
        const val TYPE_vp08 = 0x76703038
        const val TYPE_vp09 = 0x76703039
        const val TYPE_vpcC = 0x76706343
        const val TYPE_av01 = 0x61763031
        const val TYPE_av1C = 0x61763143
        const val TYPE_dvav = 0x64766176
        const val TYPE_dva1 = 0x64766131
        const val TYPE_dvhe = 0x64766865
        const val TYPE_dvh1 = 0x64766831
        const val TYPE_dvcC = 0x64766343
        const val TYPE_dvvC = 0x64767643
        const val TYPE_s263 = 0x73323633
        const val TYPE_d263 = 0x64323633
        const val TYPE_mdat = 0x6d646174
        const val TYPE_mp4a = 0x6d703461
        const val TYPE__mp2 = 0x2e6d7032
        const val TYPE__mp3 = 0x2e6d7033
        const val TYPE_wave = 0x77617665
        const val TYPE_lpcm = 0x6c70636d
        const val TYPE_sowt = 0x736f7774
        const val TYPE_ac_3 = 0x61632d33
        const val TYPE_dac3 = 0x64616333
        const val TYPE_ec_3 = 0x65632d33
        const val TYPE_dec3 = 0x64656333
        const val TYPE_ac_4 = 0x61632d34
        const val TYPE_dac4 = 0x64616334
        const val TYPE_dtsc = 0x64747363
        const val TYPE_dtsh = 0x64747368
        const val TYPE_dtsl = 0x6474736c
        const val TYPE_dtse = 0x64747365
        const val TYPE_ddts = 0x64647473
        const val TYPE_tfdt = 0x74666474
        const val TYPE_tfhd = 0x74666864
        const val TYPE_trex = 0x74726578
        const val TYPE_trun = 0x7472756e
        const val TYPE_sidx = 0x73696478
        const val TYPE_moov = 0x6d6f6f76
        const val TYPE_mpvd = 0x6d707664
        const val TYPE_mvhd = 0x6d766864
        const val TYPE_trak = 0x7472616b
        const val TYPE_mdia = 0x6d646961
        const val TYPE_minf = 0x6d696e66
        const val TYPE_stbl = 0x7374626c
        const val TYPE_esds = 0x65736473
        const val TYPE_moof = 0x6d6f6f66
        const val TYPE_traf = 0x74726166
        const val TYPE_mvex = 0x6d766578
        const val TYPE_mehd = 0x6d656864
        const val TYPE_tkhd = 0x746b6864
        const val TYPE_edts = 0x65647473
        const val TYPE_elst = 0x656c7374
        const val TYPE_mdhd = 0x6d646864
        const val TYPE_hdlr = 0x68646c72
        const val TYPE_stsd = 0x73747364
        const val TYPE_pssh = 0x70737368
        const val TYPE_sinf = 0x73696e66
        const val TYPE_schm = 0x7363686d
        const val TYPE_schi = 0x73636869
        const val TYPE_tenc = 0x74656e63
        const val TYPE_encv = 0x656e6376
        const val TYPE_enca = 0x656e6361
        const val TYPE_frma = 0x66726d61
        const val TYPE_saiz = 0x7361697a
        const val TYPE_saio = 0x7361696f
        const val TYPE_sbgp = 0x73626770
        const val TYPE_sgpd = 0x73677064
        const val TYPE_uuid = 0x75756964
        const val TYPE_senc = 0x73656e63
        const val TYPE_pasp = 0x70617370
        const val TYPE_TTML = 0x54544d4c
        const val TYPE_m1v_ = 0x6d317620
        const val TYPE_mp4v = 0x6d703476
        const val TYPE_stts = 0x73747473
        const val TYPE_stss = 0x73747373
        const val TYPE_ctts = 0x63747473
        const val TYPE_stsc = 0x73747363
        const val TYPE_stsz = 0x7374737a
        const val TYPE_stz2 = 0x73747a32
        const val TYPE_stco = 0x7374636f
        const val TYPE_co64 = 0x636f3634
        const val TYPE_tx3g = 0x74783367
        const val TYPE_wvtt = 0x77767474
        const val TYPE_stpp = 0x73747070
        const val TYPE_c608 = 0x63363038
        const val TYPE_samr = 0x73616d72
        const val TYPE_sawb = 0x73617762
        const val TYPE_udta = 0x75647461
        const val TYPE_meta = 0x6d657461
        const val TYPE_smta = 0x736d7461
        const val TYPE_saut = 0x73617574
        const val TYPE_keys = 0x6b657973
        const val TYPE_ilst = 0x696c7374
        const val TYPE_mean = 0x6d65616e
        const val TYPE_name = 0x6e616d65
        const val TYPE_data = 0x64617461
        const val TYPE_emsg = 0x656d7367
        const val TYPE_st3d = 0x73743364
        const val TYPE_sv3d = 0x73763364
        const val TYPE_proj = 0x70726f6a
        const val TYPE_camm = 0x63616d6d
        const val TYPE_mett = 0x6d657474
        const val TYPE_alac = 0x616c6163
        const val TYPE_alaw = 0x616c6177
        const val TYPE_ulaw = 0x756c6177
        const val TYPE_Opus = 0x4f707573
        const val TYPE_dOps = 0x644f7073
        const val TYPE_fLaC = 0x664c6143
        const val TYPE_dfLa = 0x64664c61
        const val TYPE_twos = 0x74776f73

        // MP4IVFHeaderBox 表示 IVF MP4 格式里面的 IVFHeader box。
        const val TYPE_ivfh = 0x69766668
        // MP4IVFFileLookupTableBox 表示 IVF MP4 格式里面的 IVFFileLookupTable box。
        const val TYPE_iflt = 0x69666c74
        // MP4IVFDataFileIndexBox 表示 IVF MP4 格式里面的 IVFDataFileIndex box。
        const val TYPE_idfi = 0x69646669
        // MP4IVFMediaFileIndexBox 表示 IVF MP4 格式里面的 IVFMediaFileIndex box。
        const val TYPE_imfi = 0x696d6669
        //MP4IVFInitBox 表示 IVF MP4 格式里面的 IVFInit box。
        const val TYPE_ivfi = 0x69766669
        // MP4IVFInitBox 表示 IVF MP4 格式里面的 IVFInit box。
        const val TYPE_ivfm = 0x6976666d

        /**
         * Parses the version number out of the additional integer component of a full atom.
         */
        @JvmStatic
        fun parseFullAtomVersion(fullAtomInt: Int): Int {
            return 0x000000FF and (fullAtomInt shr 24)
        }

        /**
         * Parses the atom flags out of the additional integer component of a full atom.
         */
        @JvmStatic
        fun parseFullAtomFlags(fullAtomInt: Int): Int {
            return 0x00FFFFFF and fullAtomInt
        }

        /**
         * Converts a numeric atom type to the corresponding four character string.
         *
         * @param type The numeric atom type.
         * @return The corresponding four character string.
         */
        @JvmStatic
        fun getAtomTypeString(type: Int): String {
            return ("" + (type shr 24 and 0xFF).toChar()
                    + (type shr 16 and 0xFF).toChar()
                    + (type shr 8 and 0xFF).toChar()
                    + (type and 0xFF).toChar())
        }
    }
}