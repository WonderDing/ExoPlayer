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

import com.google.android.exoplayer2.demo.DownloadTracker
import android.widget.ExpandableListView.OnChildClickListener
import com.google.android.exoplayer2.demo.SampleChooserActivity.SampleAdapter
import android.widget.ExpandableListView
import android.os.Bundle
import com.google.android.exoplayer2.demo.R
import android.content.Intent
import android.content.res.AssetManager
import android.widget.Toast
import com.google.android.exoplayer2.offline.DownloadService
import com.google.android.exoplayer2.demo.DemoDownloadService
import android.content.pm.PackageManager
import com.google.android.exoplayer2.util.Assertions
import com.google.android.exoplayer2.demo.SampleChooserActivity.SampleListLoader
import com.google.android.exoplayer2.demo.SampleChooserActivity.PlaylistGroup
import android.content.SharedPreferences
import android.net.Uri
import android.os.AsyncTask
import android.os.Environment
import com.google.android.exoplayer2.demo.SampleChooserActivity
import com.google.android.exoplayer2.demo.SampleChooserActivity.PlaylistHolder
import com.google.android.exoplayer2.demo.PlayerActivity
import com.google.android.exoplayer2.demo.IntentUtil
import com.google.android.exoplayer2.MediaItem.PlaybackProperties
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DataSourceInputStream
import android.util.JsonReader
import android.view.*
import kotlin.Throws
import com.google.common.collect.ImmutableList
import android.widget.BaseExpandableListAdapter
import android.widget.TextView
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.util.Log
import com.google.android.exoplayer2.util.UriUtil
import com.google.android.exoplayer2.util.Util
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.Exception
import java.lang.IllegalStateException
import java.net.URI
import java.util.*

/** An activity for selecting from a list of media samples.  */
class SampleChooserActivity : AppCompatActivity(), DownloadTracker.Listener, OnChildClickListener {
    private lateinit var uris: Array<String?>
    private var useExtensionRenderers = false
    private var downloadTracker: DownloadTracker? = null
    private var sampleAdapter: SampleAdapter? = null
    private var preferExtensionDecodersMenuItem: MenuItem? = null
    private var sampleListView: ExpandableListView? = null
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.sample_chooser_activity)
        sampleAdapter = SampleAdapter()
        sampleListView = findViewById(R.id.sample_list)
        sampleListView?.setAdapter(sampleAdapter)
        sampleListView?.setOnChildClickListener(this)
        val intent = intent
        val dataUri = intent.dataString
        val mp4Uri = Uri.parse(getExternalFilesDir(null)?.absolutePath + File.separator + "test.mp4")
        Log.e("url", "mp4Uri=:$mp4Uri")
        if (dataUri != null) {
            uris = arrayOf(dataUri)
        } else {
            val uriList = ArrayList<String>()
            val assetManager = assets
            try {
                for (asset in assetManager.list("")!!) {
                    if (asset.endsWith(".exolist.json")) {
                        uriList.add("asset:///$asset")
                    }
                }
            } catch (e: IOException) {
                Toast.makeText(applicationContext, R.string.sample_list_load_error, Toast.LENGTH_LONG)
                        .show()
            }
            uris = arrayOfNulls(uriList.size)
            uriList.toArray(uris)
            Arrays.sort(uris)
        }
        useExtensionRenderers = DemoUtil.useExtensionRenderers()
        downloadTracker = DemoUtil.getDownloadTracker( /* context= */this)
        loadSample()

        // Start the download service if it should be running but it's not currently.
        // Starting the service in the foreground causes notification flicker if there is no scheduled
        // action. Starting it in the background throws an exception if the app is in the background too
        // (e.g. if device screen is locked).
        try {
            DownloadService.start(this, DemoDownloadService::class.java)
        } catch (e: IllegalStateException) {
            DownloadService.startForeground(this, DemoDownloadService::class.java)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.sample_chooser_menu, menu)
        preferExtensionDecodersMenuItem = menu.findItem(R.id.prefer_extension_decoders)
        preferExtensionDecodersMenuItem?.isVisible = useExtensionRenderers
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.prefer_extension_decoders -> {
                item.isChecked = !item.isChecked
            }
            R.id.local_video -> {
                startActivity(Intent(this, LocalVideoActivity::class.java))
            }
        }
        return true
    }

    public override fun onStart() {
        super.onStart()
        downloadTracker!!.addListener(this)
        sampleAdapter!!.notifyDataSetChanged()
    }

    public override fun onStop() {
        downloadTracker!!.removeListener(this)
        super.onStop()
    }

    override fun onDownloadsChanged() {
        sampleAdapter!!.notifyDataSetChanged()
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.size == 0) {
            // Empty results are triggered if a permission is requested while another request was already
            // pending and can be safely ignored in this case.
            return
        }
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadSample()
        } else {
            Toast.makeText(applicationContext, R.string.sample_list_load_error, Toast.LENGTH_LONG)
                    .show()
            finish()
        }
    }

    private fun loadSample() {
        Assertions.checkNotNull(uris)
        for (i in uris.indices) {
            val uri = Uri.parse(uris[i])
            if (Util.maybeRequestReadExternalStoragePermission(this, uri)) {
                return
            }
        }
        val loaderTask = SampleListLoader()
        loaderTask.execute(*uris)
    }

    private fun onPlaylistGroups(groups: List<PlaylistGroup>, sawError: Boolean) {
        if (sawError) {
            Toast.makeText(applicationContext, R.string.sample_list_load_error, Toast.LENGTH_LONG)
                    .show()
        }
        sampleAdapter!!.setPlaylistGroups(groups)
        val preferences = getPreferences(MODE_PRIVATE)
        val groupPosition = preferences.getInt(GROUP_POSITION_PREFERENCE_KEY,  /* defValue= */-1)
        val childPosition = preferences.getInt(CHILD_POSITION_PREFERENCE_KEY,  /* defValue= */-1)
        // Clear the group and child position if either are unset or if either are out of bounds.
        if (groupPosition != -1 && childPosition != -1 && groupPosition < groups.size && childPosition < groups[groupPosition].playlists.size) {
            sampleListView!!.expandGroup(groupPosition) // shouldExpandGroup does not work without this.
            sampleListView!!.setSelectedChild(groupPosition, childPosition,  /* shouldExpandGroup= */true)
        }
    }

    override fun onChildClick(
            parent: ExpandableListView, view: View, groupPosition: Int, childPosition: Int, id: Long): Boolean {
        // Save the selected item first to be able to restore it if the tested code crashes.
        val prefEditor = getPreferences(MODE_PRIVATE).edit()
        prefEditor.putInt(GROUP_POSITION_PREFERENCE_KEY, groupPosition)
        prefEditor.putInt(CHILD_POSITION_PREFERENCE_KEY, childPosition)
        prefEditor.apply()
        val playlistHolder = view.tag as PlaylistHolder
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(
                IntentUtil.PREFER_EXTENSION_DECODERS_EXTRA,
                isNonNullAndChecked(preferExtensionDecodersMenuItem))
        IntentUtil.addToIntent(playlistHolder.mediaItems, intent)
        startActivity(intent)
        return true
    }

    private fun onSampleDownloadButtonClicked(playlistHolder: PlaylistHolder) {
        val downloadUnsupportedStringId = getDownloadUnsupportedStringId(playlistHolder)
        if (downloadUnsupportedStringId != 0) {
            Toast.makeText(applicationContext, downloadUnsupportedStringId, Toast.LENGTH_LONG)
                    .show()
        } else {
            val renderersFactory = DemoUtil.buildRenderersFactory( /* context= */
                    this, isNonNullAndChecked(preferExtensionDecodersMenuItem))
            downloadTracker!!.toggleDownload(
                    supportFragmentManager, playlistHolder.mediaItems[0], renderersFactory)
        }
    }

    private fun getDownloadUnsupportedStringId(playlistHolder: PlaylistHolder): Int {
        if (playlistHolder.mediaItems.size > 1) {
            return R.string.download_playlist_unsupported
        }
        val playbackProperties = Assertions.checkNotNull(playlistHolder.mediaItems[0].playbackProperties)
        if (playbackProperties.adsConfiguration != null) {
            return R.string.download_ads_unsupported
        }
        val scheme = playbackProperties.uri.scheme
        return if (!("http" == scheme || "https" == scheme)) {
            R.string.download_scheme_unsupported
        } else 0
    }

    private inner class SampleListLoader : AsyncTask<String?, Void?, List<PlaylistGroup>>() {
        private var sawError = false
        override fun doInBackground(vararg uris: String?): List<PlaylistGroup> {
            val result: MutableList<PlaylistGroup> = ArrayList()
            val context = applicationContext
            val dataSource = DemoUtil.getDataSourceFactory(context).createDataSource()
            for (uri in uris) {
                val dataSpec = DataSpec(Uri.parse(uri))
                val inputStream: InputStream = DataSourceInputStream(dataSource, dataSpec)
                try {
                    readPlaylistGroups(JsonReader(InputStreamReader(inputStream, "UTF-8")), result)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading sample list: $uri", e)
                    sawError = true
                } finally {
                    Util.closeQuietly(dataSource)
                }
            }
            return result
        }

        override fun onPostExecute(result: List<PlaylistGroup>) {
            onPlaylistGroups(result, sawError)
        }

        @Throws(IOException::class)
        private fun readPlaylistGroups(reader: JsonReader, groups: MutableList<PlaylistGroup>) {
            reader.beginArray()
            while (reader.hasNext()) {
                readPlaylistGroup(reader, groups)
            }
            reader.endArray()
        }

        @Throws(IOException::class)
        private fun readPlaylistGroup(reader: JsonReader, groups: MutableList<PlaylistGroup>) {
            var groupName = ""
            val playlistHolders = ArrayList<PlaylistHolder>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                when (name) {
                    "name" -> groupName = reader.nextString()
                    "samples" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            playlistHolders.add(readEntry(reader, false))
                        }
                        reader.endArray()
                    }
                    "_comment" -> reader.nextString() // Ignore.
                    else -> throw ParserException("Unsupported name: $name")
                }
            }
            reader.endObject()
            val group = getGroup(groupName, groups)
            group.playlists.addAll(playlistHolders)
        }

        @Throws(IOException::class)
        private fun readEntry(reader: JsonReader, insidePlaylist: Boolean): PlaylistHolder {
            var uri: Uri? = null
            var extension: String? = null
            var title: String? = null
            var children: ArrayList<PlaylistHolder>? = null
            var subtitleUri: Uri? = null
            var subtitleMimeType: String? = null
            var subtitleLanguage: String? = null
            val mediaItem = MediaItem.Builder()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                when (name) {
                    "name" -> title = reader.nextString()
                    "uri" -> uri = Uri.parse(reader.nextString())
                    "extension" -> extension = reader.nextString()
                    "clip_start_position_ms" -> mediaItem.setClipStartPositionMs(reader.nextLong())
                    "clip_end_position_ms" -> mediaItem.setClipEndPositionMs(reader.nextLong())
                    "ad_tag_uri" -> mediaItem.setAdTagUri(reader.nextString())
                    "drm_scheme" -> mediaItem.setDrmUuid(Util.getDrmUuid(reader.nextString()))
                    "drm_license_uri", "drm_license_url" -> mediaItem.setDrmLicenseUri(reader.nextString())
                    "drm_key_request_properties" -> {
                        val requestHeaders: MutableMap<String, String> = HashMap()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            requestHeaders[reader.nextName()] = reader.nextString()
                        }
                        reader.endObject()
                        mediaItem.setDrmLicenseRequestHeaders(requestHeaders)
                    }
                    "drm_session_for_clear_content" -> if (reader.nextBoolean()) {
                        mediaItem.setDrmSessionForClearTypes(
                                ImmutableList.of(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO))
                    }
                    "drm_multi_session" -> mediaItem.setDrmMultiSession(reader.nextBoolean())
                    "drm_force_default_license_uri" -> mediaItem.setDrmForceDefaultLicenseUri(reader.nextBoolean())
                    "subtitle_uri" -> subtitleUri = Uri.parse(reader.nextString())
                    "subtitle_mime_type" -> subtitleMimeType = reader.nextString()
                    "subtitle_language" -> subtitleLanguage = reader.nextString()
                    "playlist" -> {
                        Assertions.checkState(!insidePlaylist, "Invalid nesting of playlists")
                        children = ArrayList()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            children.add(readEntry(reader,  /* insidePlaylist= */true))
                        }
                        reader.endArray()
                    }
                    else -> throw ParserException("Unsupported attribute name: $name")
                }
            }
            reader.endObject()
            return if (children != null) {
                val mediaItems: MutableList<MediaItem> = ArrayList()
                for (i in children.indices) {
                    mediaItems.addAll(children[i].mediaItems)
                }
                PlaylistHolder(title, mediaItems)
            } else {
                val adaptiveMimeType = Util.getAdaptiveMimeTypeForContentType(Util.inferContentType(uri!!, extension))
                mediaItem
                        .setUri(uri)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                        .setMimeType(adaptiveMimeType)
                if (subtitleUri != null) {
                    val subtitle = MediaItem.Subtitle(
                            subtitleUri,
                            Assertions.checkNotNull(
                                    subtitleMimeType, "subtitle_mime_type is required if subtitle_uri is set."),
                            subtitleLanguage)
                    mediaItem.setSubtitles(listOf(subtitle))
                }
                PlaylistHolder(title, listOf(mediaItem.build()))
            }
        }

        private fun getGroup(groupName: String, groups: MutableList<PlaylistGroup>): PlaylistGroup {
            for (i in groups.indices) {
                if (Util.areEqual(groupName, groups[i].title)) {
                    return groups[i]
                }
            }
            val group = PlaylistGroup(groupName)
            groups.add(group)
            return group
        }

    }

    private inner class SampleAdapter : BaseExpandableListAdapter(), View.OnClickListener {
        private var playlistGroups: List<PlaylistGroup>
        fun setPlaylistGroups(playlistGroups: List<PlaylistGroup>) {
            this.playlistGroups = playlistGroups
            notifyDataSetChanged()
        }

        override fun getChild(groupPosition: Int, childPosition: Int): PlaylistHolder {
            return getGroup(groupPosition).playlists[childPosition]
        }

        override fun getChildId(groupPosition: Int, childPosition: Int): Long {
            return childPosition.toLong()
        }

        override fun getChildView(
                groupPosition: Int,
                childPosition: Int,
                isLastChild: Boolean,
                convertView: View?,
                parent: ViewGroup): View {
            var view = convertView
            if (view == null) {
                view = layoutInflater.inflate(R.layout.sample_list_item, parent, false)
                val downloadButton = view.findViewById<View>(R.id.download_button)
                downloadButton.setOnClickListener(this)
                downloadButton.isFocusable = false
            }
            initializeChildView(view!!, getChild(groupPosition, childPosition))
            return view
        }

        override fun getChildrenCount(groupPosition: Int): Int {
            return getGroup(groupPosition).playlists.size
        }

        override fun getGroup(groupPosition: Int): PlaylistGroup {
            return playlistGroups[groupPosition]
        }

        override fun getGroupId(groupPosition: Int): Long {
            return groupPosition.toLong()
        }

        override fun getGroupView(
                groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup): View {
            var view = convertView
            if (view == null) {
                view = layoutInflater
                        .inflate(android.R.layout.simple_expandable_list_item_1, parent, false)
            }
            (view as TextView).text = getGroup(groupPosition).title
            return view
        }

        override fun getGroupCount(): Int {
            return playlistGroups.size
        }

        override fun hasStableIds(): Boolean {
            return false
        }

        override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean {
            return true
        }

        override fun onClick(view: View) {
            onSampleDownloadButtonClicked(view.tag as PlaylistHolder)
        }

        private fun initializeChildView(view: View, playlistHolder: PlaylistHolder) {
            view.tag = playlistHolder
            val sampleTitle = view.findViewById<TextView>(R.id.sample_title)
            sampleTitle.text = playlistHolder.title
            val canDownload = getDownloadUnsupportedStringId(playlistHolder) == 0
            val isDownloaded = canDownload && downloadTracker!!.isDownloaded(playlistHolder.mediaItems[0])
            val downloadButton = view.findViewById<ImageButton>(R.id.download_button)
            downloadButton.tag = playlistHolder
            downloadButton.setColorFilter(
                    if (canDownload) if (isDownloaded) -0xbd5a0b else -0x424243 else -0x99999a)
            downloadButton.setImageResource(
                    if (isDownloaded) R.drawable.ic_download_done else R.drawable.ic_download)
        }

        init {
            playlistGroups = emptyList()
        }
    }

    private class PlaylistHolder(title: String?, mediaItems: List<MediaItem>) {
        val title: String?
        val mediaItems: List<MediaItem>

        init {
            Assertions.checkArgument(!mediaItems.isEmpty())
            this.title = title
            this.mediaItems = Collections.unmodifiableList(ArrayList(mediaItems))
        }
    }

    private class PlaylistGroup(val title: String) {
        val playlists: MutableList<PlaylistHolder>

        init {
            playlists = ArrayList()
        }
    }

    companion object {
        private const val TAG = "SampleChooserActivity"
        private const val GROUP_POSITION_PREFERENCE_KEY = "sample_chooser_group_position"
        private const val CHILD_POSITION_PREFERENCE_KEY = "sample_chooser_child_position"
        private fun isNonNullAndChecked(menuItem: MenuItem?): Boolean {
            // Temporary workaround for layouts that do not inflate the options menu.
            return menuItem != null && menuItem.isChecked
        }
    }
}