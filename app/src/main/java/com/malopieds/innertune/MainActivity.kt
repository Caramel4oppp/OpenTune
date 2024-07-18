package com.malopieds.innertune

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.imageLoader
import coil.request.ImageRequest
import com.valentinilk.shimmer.LocalShimmerTheme
import com.malopieds.innertube.YouTube
import com.malopieds.innertube.models.SongItem
import com.malopieds.innertube.models.WatchEndpoint
import com.malopieds.innertune.constants.*
import com.malopieds.innertune.db.MusicDatabase
import com.malopieds.innertune.db.entities.SearchHistory
import com.malopieds.innertune.extensions.*
import com.malopieds.innertune.models.toMediaMetadata
import com.malopieds.innertune.playback.DownloadUtil
import com.malopieds.innertune.playback.MusicService
import com.malopieds.innertune.playback.MusicService.MusicBinder
import com.malopieds.innertune.playback.PlayerConnection
import com.malopieds.innertune.playback.queues.YouTubeQueue
import com.malopieds.innertune.ui.component.*
import com.malopieds.innertune.ui.component.shimmer.ShimmerTheme
import com.malopieds.innertune.ui.menu.YouTubeSongMenu
import com.malopieds.innertune.ui.player.BottomSheetPlayer
import com.malopieds.innertune.ui.screens.*
import com.malopieds.innertune.ui.screens.artist.ArtistItemsScreen
import com.malopieds.innertune.ui.screens.artist.ArtistScreen
import com.malopieds.innertune.ui.screens.artist.ArtistSongsScreen
import com.malopieds.innertune.ui.screens.library.LibraryScreen
import com.malopieds.innertune.ui.screens.playlist.AutoPlaylistScreen
import com.malopieds.innertune.ui.screens.playlist.LocalPlaylistScreen
import com.malopieds.innertune.ui.screens.playlist.OnlinePlaylistScreen
import com.malopieds.innertune.ui.screens.playlist.TopPlaylistScreen
import com.malopieds.innertune.ui.screens.search.LocalSearchScreen
import com.malopieds.innertune.ui.screens.search.OnlineSearchResult
import com.malopieds.innertune.ui.screens.search.OnlineSearchScreen
import com.malopieds.innertune.ui.screens.settings.*
import com.malopieds.innertune.ui.theme.*
import com.malopieds.innertune.ui.utils.appBarScrollBehavior
import com.malopieds.innertune.ui.utils.backToMain
import com.malopieds.innertune.ui.utils.canNavigateUp
import com.malopieds.innertune.ui.utils.resetHeightOffset
import com.malopieds.innertune.utils.dataStore
import com.malopieds.innertune.utils.get
import com.malopieds.innertune.utils.rememberEnumPreference
import com.malopieds.innertune.utils.rememberPreference
import com.malopieds.innertune.utils.reportException
import com.malopieds.innertune.utils.setupRemoteConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import androidx.compose.runtime.mutableStateOf as mutableStateOf1
import coil.compose.AsyncImage

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    private var playerConnection by mutableStateOf1<PlayerConnection?>(null)
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is MusicBinder) {
                playerConnection = PlayerConnection(this@MainActivity, service, database, lifecycleScope)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerConnection?.dispose()
            playerConnection = null
        }
    }

    override fun onStart() {
        super.onStart()
        startService(Intent(this, MusicService::class.java))
        bindService(Intent(this, MusicService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        unbindService(serviceConnection)
        super.onStop()
    }



    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setupRemoteConfig()

        setContent {
            val enableDynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
            val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
            val pureBlack by rememberPreference(PureBlackKey, defaultValue = false)
            val isSystemInDarkTheme = isSystemInDarkTheme()
            val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
                if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            }
            LaunchedEffect(useDarkTheme) {
                setSystemBarAppearance(useDarkTheme)
            }
            var themeColor by rememberSaveable(stateSaver = ColorSaver) {
                mutableStateOf1(DefaultThemeColor)
            }

            LaunchedEffect(playerConnection, enableDynamicTheme, isSystemInDarkTheme) {
                val playerConnection = playerConnection
                if (!enableDynamicTheme || playerConnection == null) {
                    themeColor = DefaultThemeColor
                    return@LaunchedEffect
                }
                playerConnection.service.currentMediaMetadata.collectLatest { song ->
                    themeColor = if (song != null) {
                        withContext(Dispatchers.IO) {
                            val result = imageLoader.execute(
                                ImageRequest.Builder(this@MainActivity)
                                    .data(song.thumbnailUrl)
                                    .allowHardware(false) // pixel access is not supported on Config#HARDWARE bitmaps
                                    .build()
                            )
                            (result.drawable as? BitmapDrawable)?.bitmap?.extractThemeColor() ?: DefaultThemeColor
                        }
                    } else DefaultThemeColor
                }
            }

            InnerTuneTheme(
                darkTheme = useDarkTheme,
                pureBlack = pureBlack,
                themeColor = themeColor
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    val focusManager = LocalFocusManager.current
                    val density = LocalDensity.current
                    val windowsInsets = WindowInsets.systemBars
                    val bottomInset = with(density) { windowsInsets.getBottom(density).toDp() }

                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()

                    val navigationItems = remember { Screens.MainScreens }
                    val defaultOpenTab = remember {
                        dataStore[DefaultOpenTabKey].toEnum(defaultValue = NavigationTab.HOME)
                    }
                    val tabOpenedFromShortcut = remember {
                        when (intent?.action) {
                            ACTION_LIBRARY -> NavigationTab.LIBRARY
                            else -> null
                        }
                    }

                    val (query, onQueryChange) = rememberSaveable(stateSaver = TextFieldValue.Saver) {
                        mutableStateOf1(TextFieldValue())
                    }
                    var active by rememberSaveable {
                        mutableStateOf1(false)
                    }
                    val onActiveChange: (Boolean) -> Unit = { newActive ->
                        active = newActive
                        if (!newActive) {
                            focusManager.clearFocus()
                            if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                                onQueryChange(TextFieldValue())
                            }
                        }
                    }
                    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)

                    val searchBarFocusRequester = remember { FocusRequester() }

                    val onSearch: (String) -> Unit = {
                        if (it.isNotEmpty()) {
                            onActiveChange(false)
                            navController.navigate("search/${URLEncoder.encode(it, "UTF-8")}")
                            if (dataStore[PauseSearchHistoryKey] != true) {
                                database.query {
                                    insert(SearchHistory(query = it))
                                }
                            }
                        }
                    }

                    var openSearchImmediately: Boolean by remember {
                        mutableStateOf1(intent?.action == ACTION_SEARCH)
                    }

                    val shouldShowSearchBar = remember(active, navBackStackEntry) {
                        active || navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } ||
                                navBackStackEntry?.destination?.route?.startsWith("search/") == true
                    }
                    val shouldShowNavigationBar = remember(navBackStackEntry, active) {
                        navBackStackEntry?.destination?.route == null ||
                                navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } && !active
                    }
                    val navigationBarHeight by animateDpAsState(
                        targetValue = if (shouldShowNavigationBar) NavigationBarHeight else 0.dp,
                        animationSpec = NavigationBarAnimationSpec,
                        label = ""
                    )

                    val playerBottomSheetState = rememberBottomSheetState(
                        dismissedBound = 0.dp,
                        collapsedBound = bottomInset + (if (shouldShowNavigationBar) NavigationBarHeight else 0.dp) + MiniPlayerHeight,
                        expandedBound = maxHeight,
                    )

                    val playerAwareWindowInsets = remember(bottomInset, shouldShowNavigationBar, playerBottomSheetState.isDismissed) {
                        var bottom = bottomInset
                        if (shouldShowNavigationBar) bottom += NavigationBarHeight
                        if (!playerBottomSheetState.isDismissed) bottom += MiniPlayerHeight
                        windowsInsets
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                            .add(WindowInsets(top = AppBarHeight, bottom = bottom))
                    }

                    val scrollBehavior = appBarScrollBehavior(
                        canScroll = {
                            navBackStackEntry?.destination?.route?.startsWith("search/") == false &&
                                    (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                        }
                    )

                    LaunchedEffect(navBackStackEntry) {
                        if (navBackStackEntry?.destination?.route?.startsWith("search/") == true) {
                            val searchQuery = withContext(Dispatchers.IO) {
                                if (navBackStackEntry?.arguments?.getString("query")!!.contains("%")) navBackStackEntry?.arguments?.getString("query")!! else URLDecoder.decode(navBackStackEntry?.arguments?.getString("query")!!, "UTF-8")
                            }
                            onQueryChange(TextFieldValue(searchQuery, TextRange(searchQuery.length)))
                        } else if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                            onQueryChange(TextFieldValue())
                        }
                        scrollBehavior.state.resetHeightOffset()
                    }
                    LaunchedEffect(active) {
                        if (active) {
                            scrollBehavior.state.resetHeightOffset()
                        }
                    }

                    LaunchedEffect(playerConnection) {
                        val player = playerConnection?.player ?: return@LaunchedEffect
                        if (player.currentMediaItem == null) {
                            if (!playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.dismiss()
                            }
                        } else {
                            if (playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.collapseSoft()
                            }
                        }
                    }

                    DisposableEffect(playerConnection, playerBottomSheetState) {
                        val player = playerConnection?.player ?: return@DisposableEffect onDispose { }
                        val listener = object : Player.Listener {
                            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem != null && playerBottomSheetState.isDismissed) {
                                    playerBottomSheetState.collapseSoft()
                                }
                            }
                        }
                        player.addListener(listener)
                        onDispose {
                            player.removeListener(listener)
                        }
                    }

                    val coroutineScope = rememberCoroutineScope()
                    var sharedSong: SongItem? by remember {
                        mutableStateOf1(null)
                    }
                    DisposableEffect(Unit) {
                        val listener = Consumer<Intent> { intent ->
                            val uri = intent.data ?: intent.extras?.getString(Intent.EXTRA_TEXT)?.toUri() ?: return@Consumer
                            when (val path = uri.pathSegments.firstOrNull()) {
                                "playlist" -> uri.getQueryParameter("list")?.let { playlistId ->
                                    if (playlistId.startsWith("OLAK5uy_")) {
                                        coroutineScope.launch {
                                            YouTube.albumSongs(playlistId).onSuccess { songs ->
                                                songs.firstOrNull()?.album?.id?.let { browseId ->
                                                    navController.navigate("album/$browseId")
                                                }
                                            }.onFailure {
                                                reportException(it)
                                            }
                                        }
                                    } else {
                                        navController.navigate("online_playlist/$playlistId")
                                    }
                                }

                                "channel", "c" -> uri.lastPathSegment?.let { artistId ->
                                    navController.navigate("artist/$artistId")
                                }

                                else -> when {
                                    path == "watch" -> uri.getQueryParameter("v")
                                    uri.host == "youtu.be" -> path
                                    else -> null
                                }?.let { videoId ->
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            YouTube.queue(listOf(videoId))
                                        }.onSuccess {
                                            playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(videoId = it.firstOrNull()?.id), it.firstOrNull()?.toMediaMetadata()))
                                        }.onFailure {
                                            reportException(it)
                                        }
                                    }
                                }
                            }
                        }

                        addOnNewIntentListener(listener)
                        onDispose { removeOnNewIntentListener(listener) }
                    }

                    CompositionLocalProvider(
                        LocalDatabase provides database,
                        LocalContentColor provides contentColorFor(MaterialTheme.colorScheme.background),
                        LocalPlayerConnection provides playerConnection,
                        LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                        LocalDownloadUtil provides downloadUtil,
                        LocalShimmerTheme provides ShimmerTheme
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = when (tabOpenedFromShortcut ?: defaultOpenTab) {
                                NavigationTab.HOME -> Screens.Home
                                NavigationTab.EXPLORE -> Screens.Explore
                                NavigationTab.LIBRARY-> Screens.Library
                            }.route,
                            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                        ) {
                            composable(Screens.Home.route) {
                                HomeScreen(navController)
                            }
                            composable(Screens.Library.route) {
                                LibraryScreen(navController)
                            }
                            composable(Screens.Explore.route) {
                                ExploreScreen(navController)
                            }
                            composable("history") {
                                HistoryScreen(navController)
                            }
                            composable("stats") {
                                StatsScreen(navController)
                            }
                            composable("mood_and_genres") {
                                MoodAndGenresScreen(navController, scrollBehavior)
                            }
                            composable("account") {
                                AccountScreen(navController, scrollBehavior)
                            }
                            composable("new_release") {
                                NewReleaseScreen(navController, scrollBehavior)
                            }
                            composable(
                                route = "search/{query}",
                                arguments = listOf(
                                    navArgument("query") {
                                        type = NavType.StringType
                                    }
                                )
                            ) {
                                OnlineSearchResult(navController)
                            }
                            composable(
                                route = "album/{albumId}",
                                arguments = listOf(
                                    navArgument("albumId") {
                                        type = NavType.StringType
                                    },
                                )
                            ) {
                                AlbumScreen(navController, scrollBehavior)
                            }
                            composable(
                                route = "artist/{artistId}",
                                arguments = listOf(
                                    navArgument("artistId") {
                                        type = NavType.StringType
                                    }
                                )
                            ) { backStackEntry ->
                                val artistId = backStackEntry.arguments?.getString("artistId")!!
                                if (artistId.startsWith("LA")) {
                                    ArtistSongsScreen(navController, scrollBehavior)
                                } else {
                                    ArtistScreen(navController, scrollBehavior)
                                }
                            }
                            composable(
                                route = "artist/{artistId}/songs",
                                arguments = listOf(
                                    navArgument("artistId") {
                                        type = NavType.StringType
                                    }
                                )
                            ) {
                                ArtistSongsScreen(navController, scrollBehavior)
                            }
                            composable(
                                route = "artist/{artistId}/items?browseId={browseId}?params={params}",
                                arguments = listOf(
                                    navArgument("artistId") {
                                        type = NavType.StringType
                                    },
                                    navArgument("browseId") {
                                        type = NavType.StringType
                                        nullable = true
                                    },
                                    navArgument("params") {
                                        type = NavType.StringType
                                        nullable = true
                                    }
                                )
                            ) {
                                ArtistItemsScreen(navController, scrollBehavior)
                            }
                            composable(
                                route = "online_playlist/{playlistId}",
                                arguments = listOf(
                                    navArgument("playlistId") {
                                        type = NavType.StringType
                                    }
                                )
                            ) {
                                OnlinePlaylistScreen(navController, scrollBehavior)
                            }
                            composable(
                                route = "local_playlist/{playlistId}",
                                arguments = listOf(
                                    navArgument("playlistId") {
                                        type = NavType.StringType
                                    }
                                )
                            ) {
                                LocalPlaylistScreen(navController, scrollBehavior)
                            }
                            composable(
                                route = "auto_playlist/{playlist}",
                                arguments = listOf(
                                    navArgument("playlist") {
                                        type = NavType.StringType
                                    }
                                )
                            ) {
                                AutoPlaylistScreen(navController, scrollBehavior)
                            }
                            composable(
                                route = "top_playlist/{top}",
                                arguments = listOf(
                                    navArgument("top") {
                                        type = NavType.StringType
                                    }
                                )
                            ) {
                                TopPlaylistScreen(navController, scrollBehavior)
                            }
                            composable(
                                route = "youtube_browse/{browseId}?params={params}",
                                arguments = listOf(
                                    navArgument("browseId") {
                                        type = NavType.StringType
                                        nullable = true
                                    },
                                    navArgument("params") {
                                        type = NavType.StringType
                                        nullable = true
                                    }
                                )
                            ) {
                                YouTubeBrowseScreen(navController, scrollBehavior)

                            }
                            composable("settings") {
                                val latestVersion = 0
                                SettingsScreen(latestVersion.toLong(), navController, scrollBehavior)
                            }
                            composable("settings/appearance") {
                                AppearanceSettings(navController, scrollBehavior)
                            }
                            composable("settings/content") {
                                ContentSettings(navController, scrollBehavior)
                            }
                            composable("settings/player") {
                                PlayerSettings(navController, scrollBehavior)
                            }
                            composable("settings/storage") {
                                StorageSettings(navController, scrollBehavior)
                            }
                            composable("settings/privacy") {
                                PrivacySettings(navController, scrollBehavior)
                            }
                            composable("settings/backup_restore") {
                                BackupAndRestore(navController, scrollBehavior)
                            }

                            composable("settings/about") {
                                AboutScreen(navController, scrollBehavior)
                            }
                            composable("login") {
                                LoginScreen(navController)
                            }


                        }

                        AnimatedVisibility(
                            visible = shouldShowSearchBar,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            SearchBar(
                                query = query,
                                onQueryChange = onQueryChange,
                                onSearch = onSearch,
                                active = active,
                                onActiveChange = onActiveChange,
                                scrollBehavior = scrollBehavior,
                                placeholder = {
                                    Text(
                                        text = stringResource(
                                            if (!active) R.string.search
                                            else when (searchSource) {
                                                SearchSource.LOCAL -> R.string.search_library
                                                SearchSource.ONLINE -> R.string.search_yt_music
                                            }
                                        )
                                    )
                                },
                                leadingIcon = {
                                    IconButton(
                                        onClick = {
                                            when {
                                                active -> onActiveChange(false)
                                                navController.canNavigateUp && !navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } -> {
                                                    navController.navigateUp()
                                                }

                                                else -> onActiveChange(true)
                                            }
                                        },
                                        onLongClick = {
                                            when {
                                                active -> {}
                                                navController.canNavigateUp && !navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } -> {
                                                    navController.backToMain()
                                                }

                                                else -> {}
                                            }
                                        }
                                    ) {
                                        Icon(
                                            painterResource(
                                                if (active || (navController.canNavigateUp && !navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route })) {
                                                    R.drawable.arrow_back
                                                } else {
                                                    R.drawable.search
                                                }
                                            ),
                                            contentDescription = null
                                        )
                                    }
                                    Icon(
                                        painter = painterResource(R.drawable.casa),
                                        contentDescription = "home",
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clickable {
                                                navController.navigate("home") //
                                            }
                                    )
                                    Spacer(modifier = Modifier.width(20.dp)) // Espacio horizontal entre los iconos
                                },
                                trailingIcon = {
                                    if (active) {
                                        if (query.text.isNotEmpty()) {
                                            IconButton(
                                                onClick = { onQueryChange(TextFieldValue("")) }
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.close),
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                searchSource = if (searchSource == SearchSource.ONLINE) SearchSource.LOCAL else SearchSource.ONLINE
                                            }
                                        ) {
                                            Icon(
                                                painter = painterResource(
                                                    when (searchSource) {
                                                        SearchSource.LOCAL -> R.drawable.library_music
                                                        SearchSource.ONLINE -> R.drawable.language
                                                    }
                                                ),
                                                contentDescription = null
                                            )
                                        }
                                    } else if (navBackStackEntry?.destination?.route in listOf(
                                            Screens.Home.route,
                                            Screens.Explore.route,
                                            Screens.Library.route,
                                        )
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    navController.navigate("settings")
                                                }
                                        ) {


                                            Icon(
                                                painter = painterResource(R.drawable.settings),
                                                contentDescription = null
                                            )
                                        }
                                    }
                                },
                                focusRequester = searchBarFocusRequester,
                                modifier = Modifier.align(Alignment.TopCenter),
                            ) {
                                Crossfade(
                                    targetState = searchSource,
                                    label = "",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = if (!playerBottomSheetState.isDismissed) MiniPlayerHeight else 0.dp)
                                        .navigationBarsPadding()
                                ) { searchSource ->
                                    when (searchSource) {
                                        SearchSource.LOCAL -> LocalSearchScreen(
                                            query = query.text,
                                            navController = navController,
                                            onDismiss = { onActiveChange(false) }
                                        )

                                        SearchSource.ONLINE -> OnlineSearchScreen(
                                            query = query.text,
                                            onQueryChange = onQueryChange,
                                            navController = navController,
                                            onSearch = {
                                                navController.navigate("search/${URLEncoder.encode(it, "UTF-8")}")
                                                if (dataStore[PauseSearchHistoryKey] != true) {
                                                    database.query {
                                                        insert(SearchHistory(query = it))
                                                    }
                                                }
                                            },
                                            onDismiss = { onActiveChange(false) }
                                        )
                                    }
                                }
                            }
                        }

                        BottomSheetPlayer(
                            state = playerBottomSheetState,
                            navController = navController
                        )

                        NavigationBar(

                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .align(Alignment.BottomCenter)
                                .offset {
                                    if (navigationBarHeight == 0.dp) {
                                        IntOffset(
                                            x = 0,
                                            y = (bottomInset + NavigationBarHeight).roundToPx()
                                        )
                                    } else {
                                        val slideOffset =
                                            (bottomInset + NavigationBarHeight) * playerBottomSheetState.progress.coerceIn(
                                                0f,
                                                1f
                                            )
                                        val hideOffset =
                                            (bottomInset + NavigationBarHeight) * (1 - navigationBarHeight / NavigationBarHeight)
                                        IntOffset(
                                            x = 0,
                                            y = (slideOffset + hideOffset).roundToPx()
                                        )
                                    }
                                }

                        )

                        {
                            navigationItems.fastForEach { screen ->
                                NavigationBarItem(
                                    selected = navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true,
                                    icon = {
                                        Icon(
                                            painter = painterResource(screen.iconId),
                                            contentDescription = null
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(screen.titleId),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }

                        BottomSheetMenu(
                            state = LocalMenuState.current,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )

                        sharedSong?.let { song ->
                            playerConnection?.let {
                                Dialog(
                                    onDismissRequest = { sharedSong = null },
                                    properties = DialogProperties(usePlatformDefaultWidth = false)
                                ) {
                                    Surface(
                                        modifier = Modifier.padding(24.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        color = AlertDialogDefaults.containerColor,
                                        tonalElevation = AlertDialogDefaults.TonalElevation
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            YouTubeSongMenu(
                                                song = song,
                                                navController = navController,
                                                onDismiss = { sharedSong = null }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    LaunchedEffect(shouldShowSearchBar, openSearchImmediately) {
                        if (shouldShowSearchBar && openSearchImmediately) {
                            onActiveChange(true)
                            searchBarFocusRequester.requestFocus()
                            openSearchImmediately = false
                        }
                    }
                }
            }
        }
    }




    @SuppressLint("ObsoleteSdkInt")
    private fun setSystemBarAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView.rootView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            window.statusBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
    }

    companion object {
        const val ACTION_SEARCH = "com.zionhuang.music.action.SEARCH"
        const val ACTION_LIBRARY = "com.zionhuang.music.action.LIBRARY"
    }
}

val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database provided") }
val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection provided") }
val LocalPlayerAwareWindowInsets = compositionLocalOf<WindowInsets> { error("No WindowInsets provided") }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil provided") }
