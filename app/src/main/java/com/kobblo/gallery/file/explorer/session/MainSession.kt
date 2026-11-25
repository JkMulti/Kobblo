package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.explorer.ExplorerOpenWith
import com.kobblo.gallery.file.explorer.explorer.FolderViewMemory
import com.kobblo.gallery.file.explorer.explorer.search.ExplorerSearch
import com.kobblo.gallery.file.explorer.music.MusicPlayer
import com.kobblo.gallery.file.explorer.transfer.core.DeviceName
import com.kobblo.gallery.file.explorer.ui.TabSwitchLatencyTracker
import com.kobblo.gallery.file.explorer.ui.expressive.AutoIconShapeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The main screen's work, put together from its parts. Each part is one feature and knows only the others it
 * was given; where two need each other, the earlier one is given a way to call the later one.
 *
 * @param scope ends when the main screen is gone for good.
 */
internal class MainSession(private val app: Application, private val scope: CoroutineScope) {
    private val prefs = app.getSharedPreferences(SettingsStore.FILE_NAME, Context.MODE_PRIVATE)
    private val store = SettingsStore(prefs, SettingsDefaults(downloadsFolder()) { DeviceName.default(app) })
    private val words = AppWords(app)

    val repository = FileRepository(app)
    val live = LiveState(store.restore(AppState(isLoading = true)) { AutoIconShapeSession.refresh(app) })
    val messages = Messages(scope)

    val settings = Settings(app, live, store)
    val selection = Selection(live)
    val storage = PhoneStorage(app, scope, live, repository)
    val recents = Recents(scope, live, repository)
    val cleaner = Cleaner(scope, live, repository)

    // What's at a location, wherever that is.

    private val arrangement = Arrangement(FolderViewMemory::isReversed)
    private val contents = FolderContents(scope, live, arrangement)
    val search = FolderSearch(app, scope, live, repository, contents, arrangement)
    val favorites = Favorited(app, scope, live, repository, contents, reload = { reload() })
    val drive = DriveAccounts(scope, live, repository)
    val mega = MegaAccounts(app, scope, live, words, MegaAccountStore(prefs), reload = { load(it) })
    val servers = Servers.of(app, ServerMaker())
    private val locationFiles = LocationFiles(live, repository, drive, mega, servers, cleaner)
    private val loader = FolderLoader(scope, live, words, locationFiles, contents, arrangement, AroundLoading())
    val navigator = Navigator(app, scope, live, words, store, repository, loader, search, recents, drive, mega, servers)
    val folderOptions = FolderOptions(live, store, contents, loader, recents)

    // Opening files.

    private val copies = CloudCopies(app.cacheDir)
    val opening = FileOpening(
        scope, live, words, messages, recents, copies, drive, mega, servers,
        openFolder = { location, name -> navigator.goTo(location, name) }
    )
    val music = MusicControls(scope, live, MusicPlayer.get(app), copies, opening)
    val outside = OutsideFiles(app, scope, live, words, messages, music, opening)
    val locks = ItemLocks(
        app, scope, live, words, messages, repository,
        open = { path, name -> navigator.goTo(path, name) },
        reload = { loadOpenFolder() }
    )

    // Changing files.

    private val jobs = JobDesk(words, messages)
    val bin = Bin(app, scope, live, repository, jobs)
    val transfers = FileTransfers(app, scope, live, messages, repository, jobs, selection, bin, refresh = { reload() })
    val deletion = FileDeletion(
        app, scope, live, words, messages, repository, jobs, drive, cleaner,
        reload = {
            loadOpenFolder()
            storage.measure()
        }
    )
    val names = FileNames(
        app, scope, live, words, messages, repository, jobs, selection,
        load = { load(it) },
        reopen = { navigator.goTo(live.now.location) }
    )
    val hiding = HideFiles(
        app, scope, live, words, messages, repository, jobs, selection,
        refresh = {
            loadOpenFolder()
            recents.refresh()
        }
    )
    val downloads = DownloadsSorting(app, scope, live, words, messages, repository, jobs)
    val installs = AppInstalls(scope, words, messages, repository)

    // Other devices and other apps.

    val nearby = NearbyShare(app, scope, live, store, messages, recents)
    val shares = IncomingShares(
        app, scope, live, words, messages, jobs,
        showSaved = { folder, name ->
            navigator.tabTapped("browse")
            navigator.goTo(folder, name)
            reload()
        }
    )

    /** Called once, when everything above is in place. */
    fun start() {
        FolderViewMemory.init(app)
        ExplorerOpenWith.init(app)
        ExplorerSearch.init(app)
        music.observe()
        transfers.observeJobs()
        servers.restore()
        scope.launch(Dispatchers.IO) {
            storage.showShizuku()
            drive.restore()
            mega.restore()
            storage.measure()
        }
        nearby.observe()
        nearby.leaveOldPort()
        storage.checkAccess()
        load(Places.Gallery)
        recents.refresh()
    }

    /** The main screen is gone for good. */
    fun stop() {
        servers.dropNow()
    }

    /** Reads again everything that another app, or a change of permission, may have changed. */
    fun reload() {
        storage.measure()
        storage.recheckShizuku()
        loadOpenFolder()
        recents.refresh()
    }

    private fun load(location: String) {
        loader.load(location)
    }

    private fun loadOpenFolder() {
        load(live.now.location)
    }

    private fun downloadsFolder(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path

    private inner class AroundLoading : FolderLoader.Outside {
        override fun isSearch(query: String) = ExplorerSearch.isOpen(query)

        override fun searchAgain(query: String) = search.ask(query, keepSelection = true)

        override fun favorites() = favorites.onPhone()

        override fun countCategories() = cleaner.refreshTotals()

        override fun timeRead(location: String): (count: Int) -> Unit {
            val startedAt = SystemClock.elapsedRealtime()
            TabSwitchLatencyTracker.onDataFetchStart(location)
            return { TabSwitchLatencyTracker.onDataFetchEnd(location, it, SystemClock.elapsedRealtime() - startedAt) }
        }
    }

    private inner class ServerMaker : Servers.Maker {
        override fun <C : Any, E> make(
            traits: ServerTraits<C, E>,
            saved: SavedServers<C>,
            link: ServerLink<C>
        ): ServerConnections<C, E> = ServerConnections(
            traits, saved, link, scope, live, store, messages, words,
            goTo = { navigator.goTo(it) },
            fallback = { navigator.signedInCloudOrPhone() }
        )
    }
}
