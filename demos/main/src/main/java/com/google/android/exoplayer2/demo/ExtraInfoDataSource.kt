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
package com.google.android.exoplayer2.demo

import android.net.Uri
import android.text.TextUtils
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.BaseDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.util.Assertions
import com.google.android.exoplayer2.util.Util
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/** A [DataSource] for reading local files.  */
class ExtraInfoDataSource : BaseDataSource( /* isNetwork= */false) {
    /** Thrown when a [ExtraInfoDataSource] encounters an error reading a file.  */
    class FileDataSourceException : IOException {
        constructor(cause: IOException?) : super(cause) {}
        constructor(message: String?, cause: IOException?) : super(message, cause) {}
    }

    /** [DataSource.Factory] for [ExtraInfoDataSource] instances.  */
    class Factory : DataSource.Factory {
        private var listener: TransferListener? = null

        /**
         * Sets a [TransferListener] for [ExtraInfoDataSource] instances created by this factory.
         *
         * @param listener The [TransferListener].
         * @return This factory.
         */
        fun setListener(listener: TransferListener?): Factory {
            this.listener = listener
            return this
        }

        override fun createDataSource(): ExtraInfoDataSource {
            val dataSource = ExtraInfoDataSource()
            if (listener != null) {
                dataSource.addTransferListener(listener!!)
            }
            return dataSource
        }
    }

    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    @Throws(FileDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        try {
            val uri = dataSpec.uri
            this.uri = uri
            transferInitializing(dataSpec)
            file = openLocalFile(uri)
            file?.seek(dataSpec.position)
            bytesRemaining =
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) file?.length()
                    ?: 0 - dataSpec.position else dataSpec.length
            if (bytesRemaining < 0) {
                throw EOFException()
            }
        } catch (e: IOException) {
            throw FileDataSourceException(e)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private val PEEK_MIN_FREE_SPACE_AFTER_RESIZE = 64 * 1024


    fun readBytes(dataSpec: DataSpec): ByteArray {
        val byteArray = ByteArray(dataSpec.length.toInt())
//        ByteArrayInputStream()
//        while (file?.read())
        while (Util.castNonNull(file).read(byteArray,0, dataSpec.length.toInt()) == -1) {
            break
        }
        return byteArray
    }

    @Throws(FileDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        return when {
            readLength == 0 -> {
                0
            }
            bytesRemaining == 0L -> {
                C.RESULT_END_OF_INPUT
            }
            else -> {
                val bytesRead: Int = try {
                    Util.castNonNull(file).read(
                        buffer, offset, min(bytesRemaining, readLength.toLong())
                            .toInt()
                    )
                } catch (e: IOException) {
                    throw FileDataSourceException(e)
                }
                if (bytesRead > 0) {
                    bytesRemaining -= bytesRead.toLong()
                    bytesTransferred(bytesRead)
                }
                bytesRead
            }
        }
    }

    override fun getUri(): Uri? {
        return uri
    }

    @Throws(FileDataSourceException::class)
    override fun close() {
        uri = null
        try {
            if (file != null) {
                file!!.close()
            }
        } catch (e: IOException) {
            throw FileDataSourceException(e)
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        @Throws(FileDataSourceException::class)
        private fun openLocalFile(uri: Uri): RandomAccessFile {
            return try {
                RandomAccessFile(Assertions.checkNotNull(uri.path), "r")
            } catch (e: FileNotFoundException) {
                if (!TextUtils.isEmpty(uri.query) || !TextUtils.isEmpty(uri.fragment)) {
                    throw FileDataSourceException(
                        String.format(
                            "uri has query and/or fragment, which are not supported. Did you call Uri.parse()"
                                    + " on a string containing '?' or '#'? Use Uri.fromFile(new File(path)) to"
                                    + " avoid this. path=%s,query=%s,fragment=%s",
                            uri.path, uri.query, uri.fragment
                        ),
                        e
                    )
                }
                throw FileDataSourceException(e)
            }
        }
    }
}