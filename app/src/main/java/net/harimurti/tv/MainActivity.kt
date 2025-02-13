package net.harimurti.tv

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.os.Build.VERSION_CODES
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.DividerItemDecoration
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.BaseHttpStack
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import net.harimurti.tv.adapter.CategoryAdapter
import net.harimurti.tv.databinding.ActivityMainBinding
import net.harimurti.tv.dialog.ProgressDialog
import net.harimurti.tv.dialog.SettingsDialog
import net.harimurti.tv.extra.*
import net.harimurti.tv.model.*
import java.util.*

open class MainActivity : AppCompatActivity() {
    private var doubleBackToExitPressedOnce = false
    private var isTelevision = false
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: Preferences
    private lateinit var playlistHelper: PlaylistHelper
    private lateinit var volley: RequestQueue
    private lateinit var loading: ProgressDialog

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when(intent.getStringExtra(MAIN_CALLBACK)){
                UPDATE_PLAYLIST -> updatePlaylist()
                OPEN_SETTINGS -> openSettings()
            }
        }
    }

    companion object {
        const val MAIN_CALLBACK = "MAIN_CALLBACK"
        const val UPDATE_PLAYLIST = "UPDATE_PLAYLIST"
        const val OPEN_SETTINGS = "OPEN_SETTINGS"
    }

    @SuppressLint("DefaultLocale")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        binding.swipeContainer.setOnRefreshListener {
            updatePlaylist()
        }

        preferences = Preferences(this)
        playlistHelper = PlaylistHelper(this)
        loading = ProgressDialog(this)

        isTelevision = UiMode(this).isTelevision()
        if (isTelevision) {
            setTheme(R.style.AppThemeTv)
        }
        setContentView(binding.root)

        // show loading message
        loading.show(getString(R.string.loading))

        // ask all premissions need
        askPermissions()

        // local broadcast receiver to update playlist
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(broadcastReceiver, IntentFilter(MAIN_CALLBACK))

        // launch player if playlastwatched is true
        if (preferences.playLastWatched && PlayerActivity.isFirst) {
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra(PlayData.VALUE, preferences.watched)
            this.startActivity(intent)
        }

        // volley library
        var stack: BaseHttpStack = HurlStack()
        try {
            val factory = TLSSocketFactory(this)
            factory.trustAllHttps()
            stack = HurlStack(null, factory)
        } catch (e: Exception) {
            Log.e("MainApp", "Could not trust all HTTPS connection!", e)
        } finally {
            volley = Volley.newRequestQueue(this, stack)
        }

        // playlist update
        if (Playlist.loaded == null) {
            updatePlaylist()
        } else {
            setPlaylistToAdapter(Playlist.loaded!!,false)
        }

        // check new release
        if (!preferences.isCheckedReleaseUpdate) {
            checkNewRelease()
        }

        // get contributors
        if (preferences.lastVersionCode != BuildConfig.VERSION_CODE || !preferences.showLessContributors) {
            preferences.lastVersionCode = BuildConfig.VERSION_CODE
            getContributors()
        }
    }

    private fun setPlaylistToAdapter(playlist: Playlist, isMerge: Boolean) {
        val playlistSet: Playlist = playlist
        if(isMerge && Playlist.loaded != null){
            Playlist.loaded!!.categories?.let { playlistSet.categories?.addAll(it) }
            Playlist.loaded!!.drm_licenses?.let { playlistSet.drm_licenses?.addAll(it) }
        }

        // set new playlist
        if (binding.rvCategory.adapter == null) {
            binding.rvCategory.adapter = CategoryAdapter(playlistSet.categories)
            binding.rvCategory.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        }
        else {
            val adapter = binding.rvCategory.adapter as CategoryAdapter
            adapter.change(playlistSet.categories)
        }

        // end the loading
        loading.dismiss()
        binding.swipeContainer.isRefreshing = false

        if (Playlist.loaded != null)
            Toast.makeText(this, R.string.playlist_updated, Toast.LENGTH_SHORT).show()

        Playlist.loaded = playlistSet
    }

    private fun updatePlaylist() {
        //reinit helper for new name
        playlistHelper = PlaylistHelper(this)

        // from local storage
        if (playlistHelper.mode() == PlaylistHelper.MODE_LOCAL) {
            val local = playlistHelper.readLocal()
            if (local == null || local.categories.isNullOrEmpty()) {
                showAlertLocalError()
                return
            }
            setPlaylistToAdapter(local,false)
            if(!preferences.mergePlaylist)
                return
        }

        // from local pick
        if (playlistHelper.mode() == PlaylistHelper.MODE_SELECT) {
            val pick = playlistHelper.readSelect()
            if (pick == null || pick.categories.isNullOrEmpty()) {
                showAlertLocalError()
                return
            }
            setPlaylistToAdapter(pick,false)
            if(!preferences.mergePlaylist)
                return
        }

        // from internet
        val stringRequest = StringRequest(Request.Method.GET,
            playlistHelper.urlPath,
            { response: String? ->
                try {
                    val newPls = Gson().fromJson(response, Playlist::class.java)
                    playlistHelper.writeCache(response)
                    setPlaylistToAdapter(newPls,preferences.mergePlaylist)
                } catch (error: JsonSyntaxException) {
                    showAlertPlaylistError(error.message)
                }
            }
        ) { error: VolleyError ->
            var message = getString(R.string.something_went_wrong)
            if (error.networkResponse != null) {
                val errorcode = error.networkResponse.statusCode
                if (errorcode in 400..499) message =
                    String.format(getString(R.string.error_4xx), errorcode)
                if (errorcode in 500..599) message =
                    String.format(getString(R.string.error_5xx), errorcode)
            } else if (!Network(this).isConnected()) {
                message = getString(R.string.no_network)
            }
            showAlertPlaylistError(message)
        }
        volley.cache.clear()
        volley.add(stringRequest)
    }

    private fun checkNewRelease() {
        val stringRequest = StringRequest(Request.Method.GET,
            getString(R.string.json_release),
            Response.Listener { response: String? ->
                try {
                    val release = Gson().fromJson(response, Release::class.java)
                    if (release.versionCode <= BuildConfig.VERSION_CODE) return@Listener
                    val message = StringBuilder(
                        String.format(
                            getString(R.string.message_update),
                            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                            release.versionName, release.versionCode
                        )
                    )
                    for (log in release.changelog) {
                        message.append(String.format(getString(R.string.message_update_changelog), log))
                    }
                    if (release.changelog.isEmpty()) {
                        message.append(getString(R.string.message_update_no_changelog))
                    }
                    showAlertUpdate(message.toString(), release.downloadUrl)
                } catch (e: Exception) {
                    Log.e("Volley", "Could not check new update!", e)
                }
            }, null
        )
        volley.add(stringRequest)
    }

    private fun getContributors() {
            val stringRequest = StringRequest(Request.Method.GET,
                getString(R.string.gh_contributors),
                { response: String? ->
                    try {
                        val users = Gson().fromJson(response, Array<GithubUser>::class.java)
                        val message = StringBuilder(getString(R.string.message_thanks_to))
                        for (user in users) {
                            message.append(user.login).append(", ")
                        }
                        if (users.isNotEmpty() && preferences.totalContributors < users.size) {
                            preferences.totalContributors = users.size
                            showAlertContributors(message.substring(0, message.length - 2))
                        }
                    } catch (e: Exception) {
                        Log.e("Volley", "Could not get contributors!", e)
                    }
                }, null
            )
            volley.add(stringRequest)
        }

    private fun showAlertLocalError() {
        askPermissions()
        AlertDialog.Builder(this).apply {
            setTitle(R.string.alert_title_playlist_error)
            setMessage(R.string.local_playlist_read_error)
            setCancelable(false)
            setPositiveButton(R.string.dialog_retry) { _: DialogInterface?, _: Int -> updatePlaylist() }
            setNegativeButton(getString(R.string.dialog_default)) { _: DialogInterface?, _: Int ->
                preferences.useCustomPlaylist = false
                preferences.mergePlaylist = false
                preferences.playlistSelect = ""
                preferences.radioPlaylist = 0
                updatePlaylist()
            }
            create()
            show()
        }
    }

    private fun showAlertPlaylistError(error: String?) {
        val message = error ?: getString(R.string.something_went_wrong)
        val alert = AlertDialog.Builder(this)
        alert.setTitle(R.string.alert_title_playlist_error)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_retry) { _: DialogInterface?, _: Int -> updatePlaylist() }
        val cache = playlistHelper.readCache()
        if (cache != null) {
            alert.setNegativeButton(R.string.dialog_cached) { _: DialogInterface?, _: Int -> setPlaylistToAdapter(cache,false) }
        }
        alert.create().show()
    }

    private fun showAlertUpdate(message: String, fileUrl: String) {
        askPermissions()
        val alert = AlertDialog.Builder(this)
        alert.setTitle(R.string.alert_new_update)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_download) { _: DialogInterface?, _: Int -> downloadFile(fileUrl) }
            .setNegativeButton(R.string.dialog_skip) { _: DialogInterface?, _: Int -> preferences.setLastCheckUpdate() }
        alert.create().show()
    }

    private fun showAlertContributors(message: String) {
        val alert = AlertDialog.Builder(this)
        alert.setTitle(R.string.alert_title_contributors)
            .setMessage(message)
            .setNeutralButton(R.string.dialog_telegram) { _: DialogInterface?, _: Int -> openWebsite(getString(R.string.telegram_group)) }
            .setNegativeButton(R.string.dialog_website) { _: DialogInterface?, _: Int -> openWebsite(getString(R.string.website)) }
            .setPositiveButton(if (preferences.showLessContributors) R.string.dialog_close else R.string.dialog_show_less) {
                    _: DialogInterface?, _: Int -> preferences.showLessContributors()
            }
        val dialog = alert.create()
        dialog.show()
        Handler(Looper.getMainLooper()).postDelayed({ try { dialog.dismiss() } catch (e: Exception) { }}, 10000)
    }

    private fun openWebsite(link: String) {
        startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(link)))
    }

    private fun downloadFile(url: String) {
        try {
            val uri = Uri.parse(url)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(uri)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, uri.lastPathSegment)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            dm.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    @TargetApi(23)
    protected fun askPermissions() {
        if (Build.VERSION.SDK_INT < VERSION_CODES.M) return
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        for (perm in permissions) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, 260621)
                break
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 260621) return
        if (!grantResults.contains(PackageManager.PERMISSION_DENIED)) return
        Toast.makeText(this, getString(R.string.must_allow_permissions), Toast.LENGTH_LONG).show()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when(keyCode) {
            KeyEvent.KEYCODE_MENU -> openSettings()
            else -> return super.onKeyUp(keyCode, event)
        }
        return true
    }

    override fun onBackPressed() {
        if (isTelevision || doubleBackToExitPressedOnce) {
            super.onBackPressed()
            finish()
            return
        }
        doubleBackToExitPressedOnce = true
        Toast.makeText(this, getString(R.string.press_back_twice_exit_app), Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(broadcastReceiver)
        super.onDestroy()
    }

    private fun openSettings(){
        SettingsDialog().show(supportFragmentManager.beginTransaction(),null)
    }
}