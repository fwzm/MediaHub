package com.mediahub.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.common.NavArgCodec
import com.mediahub.feature.home.HomeRoute
import com.mediahub.feature.library.LibraryRoute
import com.mediahub.feature.player.PlayerRoute
import com.mediahub.feature.search.SearchRoute
import com.mediahub.feature.server.AddServerRoute
import com.mediahub.feature.settings.SettingsRoute

/** 应用导航图（Phase 0 路由）。 */
@Composable
fun MediaHubNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            val homeEntry = navController.currentBackStackEntry
            // re-login 成功后 Home 状态立即刷新：观察导航返回的 auth_changed_server_id（评审 FINAL PATCH 3）
            var forceRestoreId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(homeEntry) {
                homeEntry?.savedStateHandle?.getStateFlow<String?>("auth_changed_server_id", null)?.collect { id ->
                    if (id != null) {
                        forceRestoreId = id
                        homeEntry.savedStateHandle["auth_changed_server_id"] = null
                    }
                }
            }
            HomeRoute(
                onForceRestore = { forceRestoreId = null }, // 消费完成清空，避免重复 forceRestore
                onOpenServer = { server ->
                    navController.navigate("library/${server.id}/root?name=${Uri.encode(server.name)}")
                },
                onRelogin = { server ->
                    // Existing Server Re-login：复用原 serverId，进入添加页（reauthorizeId 模式）
                    navController.navigate(
                        "server/add?reauthorizeId=${Uri.encode(server.id)}"
                    )
                },
                onAddServer = { navController.navigate(Routes.ADD_SERVER) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenItem = { progress ->
                    navController.navigate(
                        "player/${progress.serverId}/${NavArgCodec.encode(progress.itemId)}" +
                            "?title=${Uri.encode(progress.itemTitle ?: "")}"
                    )
                },
                forceRestoreId = forceRestoreId,
            )
        }

        composable(
            route = "server/add?reauthorizeId={reauthorizeId}",
            arguments = listOf(
                navArgument("reauthorizeId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            AddServerRoute(
                onDone = { saved ->
                    // re-login 成功：通知 Home 强制刷新该服务器认证状态（评审 FINAL PATCH 3）
                    navController.previousBackStackEntry?.savedStateHandle
                        ?.set("auth_changed_server_id", saved.id)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.ADD_SERVER) {
            AddServerRoute(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }

        composable(Routes.SEARCH) {
            SearchRoute(onBack = { navController.popBackStack() })
        }

        composable(
            route = "library/{serverId}/{libraryId}?name={name}",
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("libraryId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val serverId = entry.arguments?.getString("serverId").orEmpty()
            LibraryRoute(
                serverId = serverId,
                libraryId = entry.arguments?.getString("libraryId").orEmpty(),
                name = entry.arguments?.getString("name").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenLibrary = { library ->
                    // 进入具体媒体库：library/{serverId}/{library.id}
                    navController.navigate(
                        "library/$serverId/${Uri.encode(library.id)}?name=${Uri.encode(library.name)}"
                    )
                },
                onOpenItem = { item ->
                    navController.navigate(
                        "player/$serverId/${NavArgCodec.encode(item.id)}?title=${Uri.encode(item.title)}"
                    )
                },
            )
        }

        composable(
            route = "player/{serverId}/{itemId}?title={title}",
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("itemId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            PlayerRoute(onBack = { navController.popBackStack() })
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val ADD_SERVER = "server/add"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
}
