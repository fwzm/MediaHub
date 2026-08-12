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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.navArgument
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
            var forceRestoreId by remember { mutableStateOf<String?>(null) }
            // re-login 成功后 Home 状态立即刷新（FINAL PATCH 4）
            LaunchedEffect(homeEntry) {
                homeEntry?.savedStateHandle?.getStateFlow<String?>("auth_changed_server_id", null)?.collect { id ->
                    if (id != null) {
                        forceRestoreId = id
                        homeEntry.savedStateHandle["auth_changed_server_id"] = null
                    }
                }
            }
            HomeRoute(
                onOpenServer = { server ->
                    navController.navigate("library/${server.id}/root?name=${Uri.encode(server.name)}")
                },
                onReauthorizeServer = { server ->
                    navController.navigate("server/add?reauthorizeId=${Uri.encode(server.id)}")
                },
                onRelogin = { server ->
                    // Existing Server Re-login：复用原 serverId，进入 server/add?reauthorizeId 模式
                    navController.navigate("server/add?reauthorizeId=${Uri.encode(server.id)}")
                },
                onAddServer = { navController.navigate("server/add") },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenItem = { progress ->
                    navController.navigate(
                        "player/${progress.serverId}/${NavArgCodec.encode(progress.itemId)}" +
                            "?title=${Uri.encode(progress.itemTitle ?: "")}" +
                            "&type=${progress.itemType?.name.orEmpty()}"
                    )
                },
                forceRestoreId = forceRestoreId,
                onForceRestore = { forceRestoreId = null }, // 消费完成清空
            )
        }

        composable(
            route = Routes.ADD_SERVER,
            arguments = listOf(
                navArgument("reauthorizeId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            AddServerRoute(
                onDone = { saved ->
                    // re-login / 新建成功后通知 Home 强制刷新该服务器认证状态（FINAL PATCH 4）
                    navController.previousBackStackEntry?.savedStateHandle
                        ?.set("auth_changed_server_id", saved.id)
                    navController.popBackStack()
                },
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
                onOpenItem = { item ->
                    navController.navigate(
                        "player/$serverId/${NavArgCodec.encode(item.id)}?title=${Uri.encode(item.title)}"
                            + "&type=${item.type.name}"
                    )
                },
            )
        }

        composable(
            route = "player/{serverId}/{itemId}?title={title}&type={type}",
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("itemId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("type") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            PlayerRoute(onBack = { navController.popBackStack() })
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val ADD_SERVER = "server/add?reauthorizeId={reauthorizeId}"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
}
