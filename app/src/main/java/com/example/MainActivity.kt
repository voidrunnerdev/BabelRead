package com.example

import android.Manifest
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.Coil
import coil.ImageLoader
import com.example.data.parser.NetworkManager
import com.example.i18n.AppLanguageState
import com.example.i18n.AppLanguages
import com.example.i18n.LocalAppLanguage
import com.example.i18n.Text
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MangaViewModel

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* sonuç ayar ekranından takip edilir */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .okHttpClient { NetworkManager.client }
                .build()
        )
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: MangaViewModel = viewModel()
                val uiLanguage by viewModel.preferences.uiLanguage.collectAsState(initial = AppLanguages.SYSTEM)
                val appLanguage = remember(uiLanguage) { AppLanguages.resolve(uiLanguage) }
                SideEffect { AppLanguageState.current = appLanguage }
                CompositionLocalProvider(
                    LocalAppLanguage provides appLanguage,
                    androidx.compose.ui.platform.LocalLayoutDirection provides
                        if (appLanguage.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                LaunchedEffect(currentRoute) {
                    viewModel.onReaderVisibilityChanged(currentRoute?.startsWith("reader") == true)
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_STOP -> viewModel.pauseBackgroundTranslation()
                            Lifecycle.Event.ON_START -> viewModel.resumeForegroundTranslation()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val cfChallenge by viewModel.cfChallenge.collectAsState()
                cfChallenge?.let { challenge ->
                    CloudflareVerifyDialog(
                        challenge = challenge,
                        onVerified = { viewModel.completeCloudflareVerification() },
                        onCancel = { viewModel.cancelCloudflareVerification() }
                    )
                }

                val showBottomBar = currentRoute in listOf("library", "browse", "history", "settings")

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF121212),
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar(
                                containerColor = Color(0xFF161616),
                                contentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("global_bottom_nav")
                            ) {
                                NavigationBarItem(
                                    selected = currentRoute == "library",
                                    onClick = {
                                        navController.navigate("library") {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                                    label = { Text("Kütüphane") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = Color.Gray,
                                        unselectedTextColor = Color.Gray
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "browse",
                                    onClick = {
                                        navController.navigate("browse") {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.TravelExplore, contentDescription = null) },
                                    label = { Text("Göz At") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = Color.Gray,
                                        unselectedTextColor = Color.Gray
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "history",
                                    onClick = {
                                        navController.navigate("history") {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                                    label = { Text("Geçmiş") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = Color.Gray,
                                        unselectedTextColor = Color.Gray
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "settings",
                                    onClick = {
                                        navController.navigate("settings") {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("Ayarlar") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = Color.Gray,
                                        unselectedTextColor = Color.Gray
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "library",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding())
                    ) {
                        composable("library") {
                            LibraryScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = { mangaId, sourceName ->
                                    val encId = Uri.encode(mangaId)
                                    val encSrc = Uri.encode(sourceName)
                                    navController.navigate("detail/$encId/$encSrc")
                                },
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                },
                                onNavigateToHiddenMangas = {
                                    navController.navigate("hidden_mangas")
                                },
                                onJumpToDiscover = {
                                    navController.navigate("browse")
                                }
                            )
                        }

                        composable("hidden_mangas") {
                            HiddenMangasScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToDetail = { mangaId, sourceName ->
                                    val encId = Uri.encode(mangaId)
                                    val encSrc = Uri.encode(sourceName)
                                    navController.navigate("detail/$encId/$encSrc")
                                }
                            )
                        }

                        composable("browse") {
                            BrowseScreen(
                                viewModel = viewModel,
                                onOpenSource = { source ->
                                    viewModel.addRecentSource(source)
                                    navController.navigate("catalog/${Uri.encode(source)}")
                                },
                                onNavigateToDetail = { mangaId, sourceName ->
                                    val encId = Uri.encode(mangaId)
                                    val encSrc = Uri.encode(sourceName)
                                    navController.navigate("detail/$encId/$encSrc")
                                },
                                onOpenExtensions = { navController.navigate("extensions") }
                            )
                        }

                        composable(
                            route = "catalog/{source}",
                            arguments = listOf(
                                navArgument("source") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val presetSourceName = backStackEntry.arguments?.getString("source") ?: ""
                            DiscoverScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = { mangaId, sourceName ->
                                    val encId = Uri.encode(mangaId)
                                    val encSrc = Uri.encode(sourceName)
                                    navController.navigate("detail/$encId/$encSrc")
                                },
                                onNavigateToWebBrowser = { siteId ->
                                    navController.navigate("web_browser/${Uri.encode(siteId)}")
                                },
                                presetSource = presetSourceName,
                                onNavigateBack = { navController.popBackStack() },
                                onOpenExtensions = { navController.navigate("extensions") }
                            )
                        }

                        composable("extensions") {
                            ExtensionStoreScreen(
                                onNavigateBack = { navController.popBackStack() },
                                viewModel = viewModel,
                            )
                        }

                        composable("history") {
                            HistoryScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = { mangaId, sourceName ->
                                    val encId = Uri.encode(mangaId)
                                    val encSrc = Uri.encode(sourceName)
                                    navController.navigate("detail/$encId/$encSrc")
                                }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(viewModel = viewModel)
                        }

                        composable(
                            route = "detail/{mangaId}/{sourceName}",
                            arguments = listOf(
                                navArgument("mangaId") { type = NavType.StringType },
                                navArgument("sourceName") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val mangaId = backStackEntry.arguments?.getString("mangaId") ?: ""
                            val sourceName = backStackEntry.arguments?.getString("sourceName") ?: ""

                            DetailScreen(
                                viewModel = viewModel,
                                mangaId = mangaId,
                                sourceName = sourceName,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToReader = { chapterId ->
                                    val encCh = Uri.encode(chapterId)
                                    navController.navigate("reader/$encCh")
                                }
                            )
                        }

                        composable(
                            route = "reader/{chapterId}",
                            arguments = listOf(
                                navArgument("chapterId") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val chapterId = backStackEntry.arguments?.getString("chapterId") ?: ""

                            ReaderScreen(
                                viewModel = viewModel,
                                chapterId = chapterId,
                                onNavigateBack = { navController.popBackStack() },
                                onOpenInBrowser = { url ->
                                    navController.navigate("web_open/${Uri.encode(url)}")
                                }
                            )
                        }

                        composable(
                            route = "web_open/{url}",
                            arguments = listOf(
                                navArgument("url") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val url = backStackEntry.arguments?.getString("url") ?: ""
                            WebBrowseScreen(
                                viewModel = viewModel,
                                siteId = "",
                                startUrl = url,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToDetail = { mangaId, sourceName ->
                                    val encId = Uri.encode(mangaId)
                                    val encSrc = Uri.encode(sourceName)
                                    navController.navigate("detail/$encId/$encSrc")
                                },
                                onNavigateToReader = { chapterId ->
                                    val encCh = Uri.encode(chapterId)
                                    navController.navigate("reader/$encCh")
                                }
                            )
                        }

                        composable(
                            route = "web_browser/{siteId}",
                            arguments = listOf(
                                navArgument("siteId") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val siteId = backStackEntry.arguments?.getString("siteId") ?: ""

                            WebBrowseScreen(
                                viewModel = viewModel,
                                siteId = siteId,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToDetail = { mangaId, sourceName ->
                                    val encId = Uri.encode(mangaId)
                                    val encSrc = Uri.encode(sourceName)
                                    navController.navigate("detail/$encId/$encSrc")
                                },
                                onNavigateToReader = { chapterId ->
                                    val encCh = Uri.encode(chapterId)
                                    navController.navigate("reader/$encCh")
                                }
                            )
                        }
                    }
                }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
