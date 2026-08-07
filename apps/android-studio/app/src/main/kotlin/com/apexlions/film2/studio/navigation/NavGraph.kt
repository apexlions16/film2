package com.apexlions.film2.studio.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.apexlions.film2.studio.catalog.TitleType
import com.apexlions.film2.studio.ui.editorial.EditorialScreen
import com.apexlions.film2.studio.ui.list.CatalogListScreen
import com.apexlions.film2.studio.ui.newtitle.AttachFilesScreen
import com.apexlions.film2.studio.ui.newtitle.NewTitleScreen
import com.apexlions.film2.studio.ui.quality.QualityGenerationScreen
import com.apexlions.film2.studio.ui.settings.SettingsScreen
import com.apexlions.film2.studio.ui.trailer.TrailerUploadScreen

@Composable
fun Film2StudioNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Destinations.LIST) {
        composable(Destinations.LIST) {
            CatalogListScreen(
                onAddNew = { navController.navigate(Destinations.NEW_TITLE) },
                onOpenSettings = { navController.navigate(Destinations.SETTINGS) },
                onOpenEditorial = { navController.navigate(Destinations.EDITORIAL) },
                onAttachMedia = { titleId, titleType ->
                    navController.navigate(Destinations.attachFiles(titleId, titleType.name))
                },
                onGenerateQualities = { titleId, titleType ->
                    navController.navigate(Destinations.quality(titleId, titleType.name))
                },
                onManageTrailer = { titleId -> navController.navigate(Destinations.trailer(titleId)) },
            )
        }

        composable(Destinations.SETTINGS) { SettingsScreen() }
        composable(Destinations.EDITORIAL) { EditorialScreen(onBack = { navController.popBackStack() }) }

        composable(Destinations.NEW_TITLE) {
            NewTitleScreen(
                onBack = { navController.popBackStack() },
                onSaved = { titleId, titleType ->
                    navController.navigate(Destinations.attachFiles(titleId, titleType.name)) {
                        popUpTo(Destinations.LIST)
                    }
                },
            )
        }

        composable(
            route = Destinations.ATTACH_FILES_ROUTE,
            arguments = listOf(
                navArgument(Destinations.ARG_TITLE_ID) { type = NavType.StringType },
                navArgument(Destinations.ARG_TITLE_TYPE) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val titleId = backStackEntry.arguments?.getString(Destinations.ARG_TITLE_ID).orEmpty()
            val titleTypeRaw = backStackEntry.arguments?.getString(Destinations.ARG_TITLE_TYPE).orEmpty()
            val titleType = runCatching { TitleType.valueOf(titleTypeRaw) }.getOrDefault(TitleType.MOVIE)
            AttachFilesScreen(
                titleId = titleId,
                titleType = titleType,
                onDone = {
                    navController.navigate(Destinations.LIST) {
                        popUpTo(Destinations.LIST) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Destinations.QUALITY_ROUTE,
            arguments = listOf(
                navArgument(Destinations.ARG_TITLE_ID) { type = NavType.StringType },
                navArgument(Destinations.ARG_TITLE_TYPE) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val titleId = backStackEntry.arguments?.getString(Destinations.ARG_TITLE_ID).orEmpty()
            val titleTypeRaw = backStackEntry.arguments?.getString(Destinations.ARG_TITLE_TYPE).orEmpty()
            val titleType = runCatching { TitleType.valueOf(titleTypeRaw) }.getOrDefault(TitleType.MOVIE)
            QualityGenerationScreen(
                titleId = titleId,
                titleType = titleType,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Destinations.TRAILER_ROUTE,
            arguments = listOf(navArgument(Destinations.ARG_TITLE_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val titleId = backStackEntry.arguments?.getString(Destinations.ARG_TITLE_ID).orEmpty()
            TrailerUploadScreen(titleId = titleId, onBack = { navController.popBackStack() })
        }
    }
}
