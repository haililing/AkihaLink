package com.akiha.akihalink

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.akiha.akihalink.ui.AkihaTheme
import org.junit.Rule
import org.junit.Test

class MainNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectTabReturnsHomeAfterOpeningNodesFromHome() {
        compose.setContent {
            AkihaTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = MainDestination.HOME.route,
                ) {
                    composable(MainDestination.HOME.route) {
                        Button(
                            onClick = {
                                navController.navigate(MainDestination.NODES.route)
                            },
                        ) {
                            Text("Open nodes")
                        }
                    }
                    composable(MainDestination.NODES.route) {
                        Column {
                            Text("Nodes screen")
                            Button(
                                onClick = {
                                    navController.navigateToMainDestination(MainDestination.HOME)
                                },
                            ) {
                                Text("Connect tab")
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Open nodes").performClick()
        compose.onNodeWithText("Nodes screen").assertIsDisplayed()

        compose.onNodeWithText("Connect tab").performClick()
        compose.onNodeWithText("Open nodes").assertIsDisplayed()
    }
}
