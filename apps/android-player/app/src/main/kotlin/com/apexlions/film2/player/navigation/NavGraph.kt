package com.apexlions.film2.player.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.apexlions.film2.player.ui.browse.BrowseScreen
import com.apexlions.film2.player.ui.detail.TitleDetailScreen
import com.apexlions.film2.player.ui.library.LibraryScreen
import com.apexlions.film2.player.ui.player.PlayerScreen
import com.apexlions.film2.player.ui.search.SearchScreen

@Composable
fun Film2PlayerNavGraph() {
    val navController = rememberNavController()

    fun navigateHome() {
        navController.navigate(Destinations.BROWSE) {
            popUpTo(Destinations.BROWSE) { inclusive = false }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Destinations.BROWSE) {
        composable(Destinations.BROWSE) {
            BrowseScreen(
                onTitleSelected = { title ->
                    navController.navigate(Destinations.titleDetail(title.id))
                },
                onContinuePlay = { id, season, episode ->
                    navController.navigate(Destinations.player(id, season, episode))
                },
                onSearchClick = { navController.navigate(Destinations.SEARCH) },
                onLibraryClick = { navController.navigate(Destinations.LIBRARY) },
            )
        }

        composable(Destinations.SEARCH) {
            SearchScreen(
                onTitleSelected = { title ->
                    navController.navigate(Destinations.titleDetail(title.id))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Destinations.LIBRARY) {
            LibraryScreen(
                onTitleSelected = { title ->
                    navController.navigate(Destinations.titleDetail(title.id))
                },
                onHome = ::navigateHome,
                onSearch = { navController.navigate(Destinations.SEARCH) },
            )
        }

        composable(
            route = Destinations.TITLE_DETAIL_ROUTE,
            arguments = listOf(navArgument(Destinations.ARG_TITLE_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val titleId = backStackEntry.arguments?.getString(Destinations.ARG_TITLE_ID).orEmpty()
            TitleDetailScreen(
                titleId = titleId,
                onBack = { navController.popBackStack() },
                onPlay = { id, season, episode ->
                    navController.navigate(Destinations.player(id, season, episode))
                },
            )
        }

        composable(
            route = Destinations.PLAYER_ROUTE,
            arguments = listOf(
                navArgument(Destinations.ARG_TITLE_ID) { type = NavType.StringType },
                navArgument(Destinations.ARG_SEASON) { type = NavType.IntType; defaultValue = -1 },
                navArgument(Destinations.ARG_EPISODE) { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { backStackEntry ->
            val titleId = backStackEntry.arguments?.getString(Destinations.ARG_TITLE_ID).orEmpty()
            val season = backStackEntry.arguments?.getInt(Destinations.ARG_SEASON)?.takeIf { it >= 0 }
            val episode = backStackEntry.arguments?.getInt(Destinations.ARG_EPISODE)?.takeIf { it >= 0 }
            PlayerScreen(
                titleId = titleId,
                seasonNumber = season,
                episodeNumber = episode,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
