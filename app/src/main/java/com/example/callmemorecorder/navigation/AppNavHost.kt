package com.example.callmemorecorder.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.callmemorecorder.CallMemoApp
import com.example.callmemorecorder.ui.detail.DetailScreen
import com.example.callmemorecorder.ui.detail.DetailViewModel
import com.example.callmemorecorder.ui.history.HistoryScreen
import com.example.callmemorecorder.ui.history.HistoryViewModel
import com.example.callmemorecorder.ui.home.HomeScreen
import com.example.callmemorecorder.ui.home.HomeViewModel
import com.example.callmemorecorder.ui.settings.SettingsScreen
import com.example.callmemorecorder.ui.settings.SettingsViewModel
import com.example.callmemorecorder.ui.setup.SetupScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String
) {
    val context = LocalContext.current
    val container = (context.applicationContext as CallMemoApp).container

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Setup.route) {
            val settingsVm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                },
                viewModel = settingsVm
            )
        }

        composable(Screen.Home.route) {
            val homeVm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container, context))
            HomeScreen(
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                viewModel = homeVm
            )
        }

        composable(Screen.History.route) {
            val historyVm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
            HistoryScreen(
                onNavigateToDetail = { recordId ->
                    navController.navigate(Screen.Detail.createRoute(recordId))
                },
                onNavigateBack = { navController.popBackStack() },
                viewModel = historyVm
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(navArgument("recordId") { type = NavType.StringType })
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString("recordId") ?: ""
            val detailVm: DetailViewModel = viewModel(factory = DetailViewModel.factory(container, context))
            DetailScreen(
                recordId = recordId,
                onNavigateBack = { navController.popBackStack() },
                viewModel = detailVm
            )
        }

        composable(Screen.Settings.route) {
            val settingsVm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = settingsVm
            )
        }
    }
}
