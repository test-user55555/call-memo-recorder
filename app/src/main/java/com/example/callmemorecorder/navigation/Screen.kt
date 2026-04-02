package com.example.callmemorecorder.navigation

/**
 * Navigation routes for the app
 */
sealed class Screen(val route: String) {
    object Setup : Screen("setup")
    object Home : Screen("home")
    object History : Screen("history")
    object Detail : Screen("detail/{recordId}") {
        fun createRoute(recordId: String) = "detail/$recordId"
    }
    object Settings : Screen("settings")
}
