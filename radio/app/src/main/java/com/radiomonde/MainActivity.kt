package com.radiomonde

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private enum class Mode { HOME, SEARCH, GENRES, GENRE, COUNTRIES, FAVORITES, RECENTS }

    private lateinit var store: StationStore
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val network = Executors.newFixedThreadPool(3)

    // Views
    private lateinit var tabs: LinearLayout
    private lateinit var searchBox: View
    private lateinit var searchInput: EditText
    private lateinit var sectionTitle: TextView
    private lateinit var sectionAction: TextView
    private lateinit var list: ListView
    private lateinit var messageBox: View
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var retryButton: TextView
    private lateinit var sleepButton: TextView
    private lateinit var player: View
    private lateinit var playerLogo: ImageView
    private lateinit var playerName: TextView
    private lateinit var playerStatus: TextView
    private lateinit var playerFavorite: ImageButton
    private lateinit var playerPlay: ImageButton
    private lateinit var playerLoading: ProgressBar

    // Screen state
    private var mode = Mode.HOME
    private var genre = ""
    private var worldScope = false
    private val queries = HashMap<Mode, String>()
    private var settingText = false
    private var generation = 0
    private var retry: (() -> Unit)? = null

    // Station list with paging
    private val stations = ArrayList<Station>()
    private var stationSource: ((Int) -> List<Station>)? = null
    private var nextOffset = 0
    private var canLoadMore = false
    private var loadingMore = false
    private val stationAdapter = StationAdapter()
    private val rowAdapter = RowAdapter()
    private var genres: List<Genre>? = null
    private var countries: List<Country>? = null

    // Playback
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var current: Station? = null
    private var pendingPlay: Station? = null
    private var retriedAfterError = false
    private var playbackError = false

    private val searchRunnable = Runnable { runSearch() }
    private val sleepPoll = object : Runnable {
        override fun run() {
            refreshSleepTimer()
            main.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = StationStore(this)

        tabs = findViewById(R.id.tabs)
        searchBox = findViewById(R.id.searchBox)
        searchInput = findViewById(R.id.searchInput)
        sectionTitle = findViewById(R.id.sectionTitle)
        sectionAction = findViewById(R.id.sectionAction)
        list = findViewById(R.id.list)
        messageBox = findViewById(R.id.messageBox)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        retryButton = findViewById(R.id.retryButton)
        sleepButton = findViewById(R.id.sleepButton)
        player = findViewById(R.id.player)
        playerLogo = findViewById(R.id.playerLogo)
        playerName = findViewById(R.id.playerName)
        playerStatus = findViewById(R.id.playerStatus)
        playerFavorite = findViewById(R.id.playerFavorite)
        playerPlay = findViewById(R.id.playerPlay)
        playerLoading = findViewById(R.id.playerLoading)

        applyWindowInsets(findViewById(R.id.root))
        buildTabs()
        setUpList()
        setUpSearch()

        retryButton.setOnClickListener { retry?.invoke() }
        sleepButton.setOnClickListener { showSleepDialog() }
        playerPlay.setOnClickListener { togglePlayback() }
        playerFavorite.setOnClickListener { current?.let { toggleFavorite(it) } }
        player.setOnClickListener { togglePlayback() }

        current = store.lastStation()
        updatePlayer()
        show(Mode.HOME)
    }

    override fun onStart() {
        super.onStart()
        connectController()
        main.post(sleepPoll)
    }

    override fun onStop() {
        main.removeCallbacks(sleepPoll)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        network.shutdownNow()
        super.onDestroy()
    }

    // Still called on Android 13+ because predictive back is not enabled for this app.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (mode) {
            Mode.GENRE -> show(Mode.GENRES)
            Mode.HOME -> super.onBackPressed()
            else -> show(Mode.HOME)
        }
    }

    // ---------------------------------------------------------------- layout

    /** Android 15 draws apps behind the status and navigation bars: keep the content clear of them. */
    private fun applyWindowInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private val tabModes = listOf(
        Mode.HOME to R.string.tab_home,
        Mode.SEARCH to R.string.tab_search,
        Mode.GENRES to R.string.tab_genres,
        Mode.COUNTRIES to R.string.tab_countries,
        Mode.FAVORITES to R.string.tab_favorites,
        Mode.RECENTS to R.string.tab_recents,
    )

    private fun buildTabs() {
        val d = resources.displayMetrics.density
        for ((tabMode, label) in tabModes) {
            val tab = TextView(this).apply {
                setText(label)
                textSize = 14f
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                gravity = android.view.Gravity.CENTER
                tag = tabMode
                setOnClickListener { show(tabMode) }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * d).toInt()).apply {
                marginEnd = (8 * d).toInt()
            })
        }
    }

    private fun highlightTab() {
        val selected = if (mode == Mode.GENRE) Mode.GENRES else mode
        for (i in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(i) as TextView
            val on = tab.tag == selected
            tab.setBackgroundResource(if (on) R.drawable.chip_selected else R.drawable.chip_normal)
            tab.setTextColor(getColor(if (on) R.color.header_text else R.color.header_text_dim))
            tab.paint.isFakeBoldText = on
            tab.invalidate()
        }
    }

    private fun setUpList() {
        list.setOnItemClickListener { _, _, position, _ ->
            if (list.adapter === stationAdapter) {
                stationAdapter.items.getOrNull(position)?.let { play(it) }
            } else {
                rowAdapter.items.getOrNull(position)?.onClick?.invoke()
            }
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) hideKeyboard()
            }

            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (list.adapter === stationAdapter && canLoadMore && !loadingMore && total > 0 && first + visible >= total - 6) {
                    loadMoreStations()
                }
            }
        })
    }

    private fun setUpSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (settingText) return
                queries[mode] = s?.toString().orEmpty()
                when (mode) {
                    Mode.SEARCH -> {
                        main.removeCallbacks(searchRunnable)
                        main.postDelayed(searchRunnable, 700)
                    }
                    Mode.GENRES -> showGenres()
                    Mode.COUNTRIES -> showCountries()
                    else -> Unit
                }
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                if (mode == Mode.SEARCH) {
                    main.removeCallbacks(searchRunnable)
                    runSearch()
                }
                true
            } else {
                false
            }
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ---------------------------------------------------------------- screens

    private fun show(newMode: Mode) {
        mode = newMode
        generation++ // drop results still loading for the previous screen
        main.removeCallbacks(searchRunnable)
        highlightTab()
        sectionAction.visibility = View.GONE
        sectionAction.setOnClickListener(null)

        val hint = when (mode) {
            Mode.SEARCH -> R.string.hint_search
            Mode.GENRES -> R.string.hint_filter_genres
            Mode.COUNTRIES -> R.string.hint_filter_countries
            else -> 0
        }
        if (hint == 0) {
            searchBox.visibility = View.GONE
            hideKeyboard()
        } else {
            searchBox.visibility = View.VISIBLE
            searchInput.setHint(hint)
            settingText = true
            searchInput.setText(queries[mode].orEmpty())
            searchInput.setSelection(searchInput.text.length)
            settingText = false
        }

        when (mode) {
            Mode.HOME -> {
                val code = store.countryCode
                sectionTitle.text = getString(R.string.title_top, flagEmoji(code) + " " + store.countryName)
                showSectionAction(getString(R.string.tab_countries) + " ▾") { show(Mode.COUNTRIES) }
                loadStations { offset -> RadioBrowserApi.topStations(code, offset) }
            }
            Mode.SEARCH -> runSearch()
            Mode.GENRES -> showGenres()
            Mode.GENRE -> {
                sectionTitle.text = getString(R.string.title_genre, genre.displayName())
                showScopeToggle { show(Mode.GENRE) }
                val code = scopeCountry()
                val g = genre
                loadStations { offset -> RadioBrowserApi.byGenre(g, code, offset) }
            }
            Mode.COUNTRIES -> showCountries()
            Mode.FAVORITES -> {
                sectionTitle.setText(R.string.title_favorites)
                showLocalStations(store.favorites(), R.string.empty_favorites)
            }
            Mode.RECENTS -> {
                sectionTitle.setText(R.string.title_recents)
                val recents = store.recents()
                if (recents.isNotEmpty()) {
                    showSectionAction(getString(R.string.clear_recents)) {
                        store.clearRecents()
                        show(Mode.RECENTS)
                    }
                }
                showLocalStations(recents, R.string.empty_recents)
            }
        }
        list.setSelection(0)
    }

    private fun showSectionAction(text: String, onClick: () -> Unit) {
        sectionAction.text = text
        sectionAction.visibility = View.VISIBLE
        sectionAction.setOnClickListener { onClick() }
    }

    /** "🇫🇷 France" / "🌍 Monde entier" chip used by search and genre lists. */
    private fun showScopeToggle(reload: () -> Unit) {
        val label = if (worldScope) getString(R.string.scope_world) else flagEmoji(store.countryCode) + " " + store.countryName
        showSectionAction("$label ▾") {
            worldScope = !worldScope
            reload()
        }
    }

    private fun scopeCountry(): String? = if (worldScope) null else store.countryCode

    private fun runSearch() {
        if (mode != Mode.SEARCH) return
        val text = queries[Mode.SEARCH].orEmpty().trim()
        showScopeToggle { show(Mode.SEARCH) }
        if (text.isEmpty()) {
            generation++
            sectionTitle.setText(R.string.title_search)
            stations.clear()
            stationSource = null
            canLoadMore = false
            stationAdapter.items = stations
            list.adapter = stationAdapter
            showMessage(getString(R.string.empty_search))
            return
        }
        sectionTitle.text = getString(R.string.title_search_results, text)
        val code = scopeCountry()
        loadStations { offset -> RadioBrowserApi.search(text, code, offset) }
    }

    private fun showLocalStations(items: List<Station>, emptyText: Int) {
        stations.clear()
        stations.addAll(items)
        stationSource = null
        canLoadMore = false
        stationAdapter.items = stations
        list.adapter = stationAdapter
        if (items.isEmpty()) showMessage(getString(emptyText)) else hideMessage()
    }

    private fun showGenres() {
        sectionTitle.setText(R.string.title_genres)
        val all = genres
        if (all == null) {
            loadOnce({ RadioBrowserApi.genres() }) { genres = it; showGenres() }
            return
        }
        val filter = queries[Mode.GENRES].orEmpty().trim().lowercase(Locale.ROOT)
        val rows = all.filter { filter.isEmpty() || it.name.lowercase(Locale.ROOT).contains(filter) }.map { g ->
            Row(genreIcon(g.name), g.name.displayName(), getString(R.string.stations_count, g.stationCount)) {
                genre = g.name
                show(Mode.GENRE)
            }
        }
        showRows(rows)
    }

    private fun showCountries() {
        sectionTitle.setText(R.string.title_countries)
        val all = countries
        if (all == null) {
            loadOnce({ RadioBrowserApi.countries() }) { countries = it; showCountries() }
            return
        }
        val filter = queries[Mode.COUNTRIES].orEmpty().trim().lowercase(Locale.ROOT)
        val home = store.countryCode
        val rows = all
            .filter { filter.isEmpty() || it.name.lowercase(Locale.ROOT).contains(filter) || it.code.equals(filter, true) }
            .sortedByDescending { it.code == home } // current country first
            .map { c ->
                Row(c.flag, (if (c.code == home) "✓ " else "") + c.name, getString(R.string.stations_count, c.stationCount)) {
                    store.countryCode = c.code
                    store.countryName = c.name
                    worldScope = false
                    Toast.makeText(this, getString(R.string.country_set, c.name), Toast.LENGTH_SHORT).show()
                    show(Mode.HOME)
                }
            }
        showRows(rows)
    }

    private fun showRows(rows: List<Row>) {
        rowAdapter.items = rows
        if (list.adapter !== rowAdapter) list.adapter = rowAdapter
        if (rows.isEmpty()) showMessage(getString(R.string.empty_results)) else hideMessage()
    }

    // ---------------------------------------------------------------- loading

    private fun <T> loadOnce(task: () -> T, onDone: (T) -> Unit) {
        val gen = generation
        rowAdapter.items = emptyList()
        list.adapter = rowAdapter
        showLoading()
        network.execute {
            val result = runCatching(task)
            main.post {
                if (gen != generation || isDestroyed) return@post
                result.onSuccess(onDone).onFailure { showError { loadOnce(task, onDone) } }
            }
        }
    }

    private fun loadStations(source: (Int) -> List<Station>) {
        val gen = ++generation
        stationSource = source
        stations.clear()
        nextOffset = 0
        canLoadMore = false
        loadingMore = true
        stationAdapter.items = stations
        list.adapter = stationAdapter
        showLoading()
        network.execute {
            val result = runCatching { source(0) }
            main.post {
                if (gen != generation || isDestroyed) return@post
                loadingMore = false
                result.onSuccess { page ->
                    addPage(page)
                    if (stations.isEmpty()) showMessage(getString(R.string.empty_results)) else hideMessage()
                }.onFailure {
                    showError { loadStations(source) }
                }
            }
        }
    }

    private fun loadMoreStations() {
        val source = stationSource ?: return
        val gen = generation
        val offset = nextOffset
        loadingMore = true
        network.execute {
            val result = runCatching { source(offset) }
            main.post {
                if (gen != generation || isDestroyed) return@post
                loadingMore = false
                result.onSuccess { addPage(it) }.onFailure { canLoadMore = false }
            }
        }
    }

    private fun addPage(page: List<Station>) {
        nextOffset += RadioBrowserApi.PAGE_SIZE
        canLoadMore = page.size >= RadioBrowserApi.PAGE_SIZE / 2
        val known = stations.mapTo(HashSet()) { it.uuid }
        stations.addAll(page.filter { known.add(it.uuid) })
        stationAdapter.notifyDataSetChanged()
    }

    private fun showLoading() {
        messageBox.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        message.setText(R.string.loading)
        retryButton.visibility = View.GONE
    }

    private fun showMessage(text: String) {
        messageBox.visibility = View.VISIBLE
        progress.visibility = View.GONE
        message.text = text
        retryButton.visibility = View.GONE
    }

    private fun showError(onRetry: () -> Unit) {
        messageBox.visibility = View.VISIBLE
        progress.visibility = View.GONE
        message.setText(R.string.network_error)
        retryButton.visibility = View.VISIBLE
        retry = onRetry
    }

    private fun hideMessage() {
        messageBox.visibility = View.GONE
    }

    // ---------------------------------------------------------------- playback

    private fun connectController() {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            val c = try { future.get() } catch (_: Exception) { return@addListener }
            controller = c
            c.addListener(playerListener)
            c.currentMediaItem?.mediaId?.let { id -> store.find(id)?.let { current = it } }
            pendingPlay?.let { pendingPlay = null; play(it) }
            updatePlayer()
            refreshSleepTimer()
        }, mainExecutor)
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) {
                retriedAfterError = false
                playbackError = false
            }
            updatePlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { id -> store.find(id)?.let { current = it } }
        }

        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            if (!retriedAfterError) {
                // Live streams often drop once on a weak connection: try again silently.
                retriedAfterError = true
                main.postDelayed({ c.prepare(); c.play() }, 1500)
            } else {
                playbackError = true
                updatePlayer()
                Toast.makeText(this@MainActivity, R.string.status_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun play(station: Station) {
        hideKeyboard()
        current = station
        playbackError = false
        retriedAfterError = false
        store.addRecent(station)
        updatePlayer()
        val c = controller
        if (c == null) {
            pendingPlay = station
            connectController()
            return
        }
        // Show the "connecting" state right away while the address is checked.
        playerLoading.visibility = View.VISIBLE
        network.execute {
            RadioBrowserApi.registerClick(station.uuid)
        }
        network.execute {
            val url = PlaylistResolver.resolve(station.streamUrl)
            main.post {
                if (current?.uuid != station.uuid) return@post
                val ctrl = controller ?: return@post
                ctrl.setMediaItem(mediaItemFor(station, url))
                ctrl.prepare()
                ctrl.play()
            }
        }
    }

    private fun mediaItemFor(station: Station, url: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            // Title is replaced by the song name when the station sends it; the artist line keeps the station name.
            .setTitle(getString(R.string.status_live))
            .setArtist(station.name)
            .setStation(station.name)
            .setIsPlayable(true)
            .setIsBrowsable(false)
        if (station.favicon.startsWith("http")) metadata.setArtworkUri(Uri.parse(station.favicon))
        return MediaItem.Builder()
            .setMediaId(station.uuid)
            .setUri(url)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).build())
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun togglePlayback() {
        val station = current ?: return
        val c = controller
        if (c == null) {
            play(station)
            return
        }
        when {
            c.playWhenReady && !playbackError -> c.pause()
            c.currentMediaItem?.mediaId == station.uuid && !playbackError && c.playbackState != Player.STATE_IDLE -> {
                // Jump back to the live broadcast instead of playing what was buffered before the pause.
                c.seekToDefaultPosition()
                c.play()
            }
            else -> play(station)
        }
    }

    private fun toggleFavorite(station: Station) {
        val added = store.toggleFavorite(station)
        Toast.makeText(this, if (added) R.string.favorite_added else R.string.favorite_removed, Toast.LENGTH_SHORT).show()
        updatePlayer()
        if (mode == Mode.FAVORITES) show(Mode.FAVORITES) else stationAdapter.notifyDataSetChanged()
    }

    private fun isCurrentPlaying(station: Station): Boolean {
        val c = controller ?: return false
        return c.currentMediaItem?.mediaId == station.uuid && c.playWhenReady && !playbackError
    }

    private fun updatePlayer() {
        val station = current
        if (station == null) {
            player.visibility = View.GONE
            return
        }
        player.visibility = View.VISIBLE
        playerName.text = station.name
        ImageLoader.load(station.favicon, playerLogo, R.drawable.ic_radio)
        playerFavorite.setImageResource(if (store.isFavorite(station.uuid)) R.drawable.ic_star else R.drawable.ic_star_border_light)

        val c = controller
        val ours = c != null && c.currentMediaItem?.mediaId == station.uuid
        val wantsToPlay = ours && c!!.playWhenReady && !playbackError
        val buffering = wantsToPlay && c!!.playbackState == Player.STATE_BUFFERING
        val songTitle = if (ours) c!!.mediaMetadata.title?.toString()?.trim().orEmpty() else ""

        playerStatus.text = when {
            playbackError -> getString(R.string.status_error)
            buffering -> getString(if (c!!.isLoading && c.bufferedPosition > 0) R.string.status_buffering else R.string.status_connecting)
            wantsToPlay && songTitle.isNotEmpty() && songTitle != getString(R.string.status_live) &&
                !songTitle.equals(station.name, true) -> "♪ $songTitle"
            wantsToPlay -> getString(R.string.status_live) + " · " + station.subtitle()
            else -> station.subtitle().ifEmpty { getString(R.string.status_paused) }
        }
        playerStatus.isSelected = true // marquee
        playerLoading.visibility = if (buffering) View.VISIBLE else View.GONE
        playerPlay.setImageResource(if (wantsToPlay) R.drawable.ic_pause else R.drawable.ic_play)
        playerPlay.contentDescription = getString(if (wantsToPlay) R.string.action_pause else R.string.action_play)
        stationAdapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- sleep timer

    private val sleepChoices = intArrayOf(0, 15, 30, 45, 60, 90, 120)

    private fun showSleepDialog() {
        val labels = sleepChoices.map { if (it == 0) getString(R.string.sleep_off) else durationLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_title)
            .setItems(labels) { _, which -> setSleepTimer(sleepChoices[which]) }
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        val c = controller ?: return
        val args = Bundle().apply { putInt(PlaybackService.EXTRA_MINUTES, minutes) }
        c.sendCustomCommand(SessionCommand(PlaybackService.CMD_SET_SLEEP, Bundle.EMPTY), args)
            .addListener({ refreshSleepTimer() }, mainExecutor)
        val text = if (minutes == 0) getString(R.string.sleep_cancelled) else getString(R.string.sleep_set, durationLabel(minutes))
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun refreshSleepTimer() {
        val c = controller ?: return
        val future = c.sendCustomCommand(SessionCommand(PlaybackService.CMD_GET_SLEEP, Bundle.EMPTY), Bundle.EMPTY)
        future.addListener({
            val remaining = try { future.get().extras.getLong(PlaybackService.EXTRA_REMAINING_MS) } catch (_: Exception) { 0L }
            sleepButton.text = if (remaining <= 0) {
                getString(R.string.sleep_button)
            } else {
                durationLabel(((remaining + 59_999) / 60_000).toInt())
            }
        }, mainExecutor)
    }

    private fun durationLabel(minutes: Int): String = when {
        minutes >= 60 && minutes % 60 == 0 -> getString(R.string.sleep_hours, minutes / 60)
        minutes > 60 -> getString(R.string.sleep_hours, minutes / 60) + " " + (minutes % 60)
        else -> getString(R.string.sleep_minutes, minutes)
    }

    // ---------------------------------------------------------------- adapters

    private data class Row(val icon: String, val name: String, val count: String, val onClick: () -> Unit)

    private inner class StationAdapter : BaseAdapter() {
        var items: List<Station> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
            val station = items[position]
            val name = view.findViewById<TextView>(R.id.name)
            val playing = isCurrentPlaying(station)
            name.text = if (playing) "▶ " + station.name else station.name
            name.setTextColor(getColor(if (playing) R.color.accent else R.color.text_primary))
            view.findViewById<TextView>(R.id.details).text = station.subtitle()
            view.setBackgroundColor(if (playing) getColor(R.color.playing_row) else 0)
            ImageLoader.load(station.favicon, view.findViewById(R.id.logo), R.drawable.ic_radio)
            view.findViewById<ImageButton>(R.id.favorite).apply {
                setImageResource(if (store.isFavorite(station.uuid)) R.drawable.ic_star else R.drawable.ic_star_border)
                setOnClickListener { toggleFavorite(station) }
            }
            return view
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        var items: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_simple, parent, false)
            val row = items[position]
            view.findViewById<TextView>(R.id.icon).text = row.icon
            view.findViewById<TextView>(R.id.name).text = row.name
            view.findViewById<TextView>(R.id.count).text = row.count
            return view
        }
    }

    private fun String.displayName(): String = replaceFirstChar { it.titlecase(Locale.FRENCH) }

    private fun genreIcon(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        return when {
            "news" in n || "info" in n || "actualit" in n -> "📰"
            "talk" in n || "sport" in n -> "🎙"
            "jazz" in n || "blues" in n -> "🎷"
            "classic" in n || "classique" in n || "baroque" in n -> "🎻"
            "rock" in n || "metal" in n -> "🎸"
            "hip" in n || "rap" in n || "rnb" in n || "r&b" in n -> "🎤"
            "dance" in n || "electro" in n || "house" in n || "techno" in n || "edm" in n -> "🎧"
            "afro" in n || "african" in n || "reggae" in n || "zouk" in n || "world" in n -> "🌍"
            "relig" in n || "christian" in n || "gospel" in n || "islam" in n -> "🕊"
            "kids" in n || "children" in n || "enfant" in n -> "🧸"
            else -> "🎵"
        }
    }
}
