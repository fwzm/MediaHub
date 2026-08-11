package com.mediahub.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
            HomeRoute(
                onOpenServer = { server ->
                    navController.navigate("library/${server.id}/root?name=${Uri.encode(server.name)}")
                },
                onAddServer = { navController.navigate(Routes.ADD_SERVER) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenItem = { progress ->
                    navController.navigate(
                        "player/${progress.serverId}/${NavArgCodec.encode(progress.itemId)}" +
                            "?title=${Uri.encode(progress.itemTitle ?: "")}"
                    )
                },
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
