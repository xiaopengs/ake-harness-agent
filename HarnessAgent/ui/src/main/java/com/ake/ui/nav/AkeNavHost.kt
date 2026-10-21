package com.ake.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ake.ui.feature.home.HomeScreen
import com.ake.ui.feature.mcp.McpScreen
import com.ake.ui.feature.modelmgt.ModelScreen
import com.ake.ui.feature.settings.SettingsScreen
import com.ake.ui.feature.task.TaskScreen

/**
 * 导航结构。
 *
 * ## 为什么只有一层底部导航 + 一个全屏任务页
 *
 * 端侧 Agent 的操作链路是「下达 → 盯着看 → 中途处理确认」，不是一棵深树。
 * 底部四个 tab 覆盖管理与配置，**任务执行页刻意不带 tab** ——
 * 它需要一个完整高度的步骤流，而且确认弹窗出现时不能被底栏抢走注意力。
 */
public object AkeRoute {
    public const val HOME: String = "home"
    public const val TASK: String = "task"
    public const val MODEL: String = "model"
    public const val TOOLS: String = "tools"
    public const val SETTINGS: String = "settings"
}

private val TABS: List<Pair<String, String>> = listOf(
    AkeRoute.HOME to "任务",
    AkeRoute.MODEL to "模型",
    AkeRoute.TOOLS to "工具",
    AkeRoute.SETTINGS to "设置",
)

@Composable
public fun AkeNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = AkeRoute.HOME) {

        composable(AkeRoute.HOME) {
            TabScaffold(navController, AkeRoute.HOME) {
                HomeScreen(
                    onOpenTask = { navController.navigate(AkeRoute.TASK) },
                    onOpenModel = { navController.navigateToTab(AkeRoute.MODEL) },
                    onOpenTools = { navController.navigateToTab(AkeRoute.TOOLS) },
                    onOpenSettings = { navController.navigateToTab(AkeRoute.SETTINGS) },
                )
            }
        }

        composable(AkeRoute.MODEL) {
            TabScaffold(navController, AkeRoute.MODEL) { ModelScreen() }
        }

        composable(AkeRoute.TOOLS) {
            TabScaffold(navController, AkeRoute.TOOLS) { McpScreen() }
        }

        composable(AkeRoute.SETTINGS) {
            TabScaffold(navController, AkeRoute.SETTINGS) { SettingsScreen() }
        }

        composable(AkeRoute.TASK) {
            // ★ 全屏、不带底栏：确认弹窗出现时不该有任何东西与它抢注意力
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                TaskScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun TabScaffold(
    navController: NavHostController,
    current: String,
    content: @Composable () -> Unit,
) {
    val entry by navController.currentBackStackEntryAsState()
    val selected = entry?.destination?.route ?: current
    Scaffold(
        bottomBar = {
            NavigationBar(tonalElevation = 2.dp) {
                for ((route, label) in TABS) {
                    NavigationBarItem(
                        selected = selected == route,
                        onClick = { navController.navigateToTab(route) },
                        icon = { },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium) },
                    )
                }
            }
        },
        content = { padding: PaddingValues ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        },
    )
}

/**
 * 切 tab：弹出到起始页再进目标，避免「任务 → 模型 → 任务」堆出一串返回栈。
 * 端侧任务全程在前台，返回栈一深，用户按返回键就会在几个页面之间迷路。
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
