package com.ake.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ake.ui.R
import com.ake.ui.feature.agent.AgentDetailScreen
import com.ake.ui.feature.agent.AgentListScreen
import com.ake.ui.feature.agent.CreateAgentScreen
import com.ake.ui.feature.agent.TemplatePickerScreen
import com.ake.ui.feature.chat.ChatListScreen
import com.ake.ui.feature.chat.ChatScreen
import com.ake.ui.feature.chat.ChatViewModel
import com.ake.ui.feature.home.HomeScreen
import com.ake.ui.feature.mcp.McpScreen
import com.ake.ui.feature.modelmgt.ModelScreen
import com.ake.ui.feature.onboarding.ModelGateScreen
import com.ake.ui.feature.onboarding.ModelGateViewModel
import com.ake.ui.feature.settings.SettingsScreen
import com.ake.ui.feature.task.TaskScreen
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * 导航结构。
 *
 * ## 为什么首页是「对话」而不是「任务」
 *
 * 这个 App 原来的形态是"下达一次任务、盯着它跑完"。加了人格之后，用户的主循环变成
 * **"找一个角色 → 说句话 → 它自己决定要不要去干活"** —— 对话是入口，任务降级成
 * 对话里的一次技能调用。所以 `startDestination` 改成对话列表，任务页退成一个
 * 从对话里跳进去的全屏详情（[AkeRoute.TASK]）。
 *
 * ## 底栏四个 tab
 *
 * 对话 / Agent / 任务 / 设置。模型与工具页仍在图里，但**不占底栏**：
 * 它们是低频配置项，从任务页与设置页进得去。四个 tab 是拇指舒适区的上限，
 * 五个以上就开始有点不准了。
 *
 * ## 为什么对话页与任务页不带底栏
 *
 * 这两个页面都有一个"正在发生"的区域（消息流 / 步骤流）和一个贴底的操作区。
 * 底栏会同时吃掉垂直空间、并在确认弹窗出现时抢注意力 —— 所以它们走全屏路由。
 */
public object AkeRoute {

    // ── tab
    public const val CHAT_LIST: String = "chats"
    public const val AGENT_LIST: String = "agents?pick={pick}"
    public const val HOME: String = "home"
    public const val SETTINGS: String = "settings"

    // ── 全屏
    public const val MODEL_GATE: String = "model-gate"
    public const val MODEL_DOWNLOAD: String = "model-download"
    public const val CHAT: String = "chat?sessionId={sessionId}&agentId={agentId}"
    public const val TASK: String = "task"
    public const val MODEL: String = "model"
    public const val TOOLS: String = "tools"
    public const val AGENT_DETAIL: String = "agent/detail/{profileId}"
    public const val AGENT_CREATE: String = "agent/create?templateId={templateId}&prefillProfileId={prefillProfileId}"
    public const val TEMPLATE_PICKER: String = "agent/templates"

    /** 开一个已有会话，或从某个 Agent 开新会话（两者互斥，都给就以前者为准）。 */
    public fun chat(sessionId: String? = null, agentId: String? = null): String {
        val query = buildList {
            sessionId?.let { add("sessionId=" + Uri.encode(it)) }
            agentId?.let { add("agentId=" + Uri.encode(it)) }
        }
        return if (query.isEmpty()) "chat" else "chat?" + query.joinToString("&")
    }

    public fun agents(pick: Boolean = false): String = if (pick) "agents?pick=true" else "agents"

    public fun agentDetail(profileId: String): String = "agent/detail/" + Uri.encode(profileId)

    public fun createAgent(templateId: String? = null, prefillProfileId: String? = null): String {
        val query = buildList {
            templateId?.let { add("templateId=" + Uri.encode(it)) }
            prefillProfileId?.let { add("prefillProfileId=" + Uri.encode(it)) }
        }
        return if (query.isEmpty()) "agent/create" else "agent/create?" + query.joinToString("&")
    }
}

private val TABS: List<Pair<String, Int>> = listOf(
    AkeRoute.CHAT_LIST to R.string.nav_tab_chat,
    // ★ 底栏指向"非挑人"形态；`pick` 有默认值，所以裸路由也能匹配
    "agents" to R.string.nav_tab_agent,
    AkeRoute.SETTINGS to R.string.nav_tab_settings,
)

@Composable
public fun AkeNavHost(
    navController: NavHostController = rememberNavController(),
    gateViewModel: ModelGateViewModel = hiltViewModel(),
) {
    val gateUi by gateViewModel.ui.collectAsStateWithLifecycle()
    if (!gateUi.initialCheckComplete) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Text(
                text = "正在检查本地模型…",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    val startDestination = remember {
        if (gateUi.ready) AkeRoute.CHAT_LIST else AkeRoute.MODEL_GATE
    }

    NavHost(navController = navController, startDestination = startDestination) {

        composable(AkeRoute.MODEL_GATE) {
            ModelGateScreen(
                viewModel = gateViewModel,
                onStartDownload = {
                    navController.navigate(AkeRoute.MODEL_DOWNLOAD) {
                        popUpTo(AkeRoute.MODEL_GATE) { inclusive = true }
                    }
                },
                onModelReady = { navController.navigateToChatsClearingStart() },
            )
        }

        composable(AkeRoute.CHAT_LIST) {
            TabScaffold(navController, AkeRoute.CHAT_LIST) {
                ChatListScreen(
                    onOpenSession = { navController.navigate(AkeRoute.chat(sessionId = it)) },
                    onNewChat = { navController.navigate(AkeRoute.agents(pick = true)) },
                )
            }
        }

        composable(
            route = AkeRoute.AGENT_LIST,
            arguments = listOf(navArgument("pick") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val pick = entry.arguments?.getBoolean("pick") == true
            TabScaffold(navController, "agents") {
                AgentListScreen(
                    pickMode = pick,
                    onOpenAgent = { navController.navigate(AkeRoute.agentDetail(it)) },
                    onPickAgent = { navController.navigate(AkeRoute.chat(agentId = it)) },
                    onCreateBlank = { navController.navigate(AkeRoute.createAgent()) },
                    onCreateFromTemplate = { navController.navigate(AkeRoute.TEMPLATE_PICKER) },
                    onOpenTemplateMarket = { navController.navigate(AkeRoute.TEMPLATE_PICKER) },
                )
            }
        }

        composable(AkeRoute.HOME) {
            TabScaffold(navController, AkeRoute.HOME) {
                HomeScreen(
                    onOpenTask = { navController.navigate(AkeRoute.TASK) },
                    onOpenModel = { navController.navigate(AkeRoute.MODEL) },
                    onOpenTools = { navController.navigate(AkeRoute.TOOLS) },
                    onOpenSettings = { navController.navigateToTab(AkeRoute.SETTINGS) },
                )
            }
        }

        composable(AkeRoute.SETTINGS) {
            TabScaffold(navController, AkeRoute.SETTINGS) {
                SettingsScreen(
                    onOpenModel = { navController.navigate(AkeRoute.MODEL) },
                    onOpenTools = { navController.navigate(AkeRoute.TOOLS) },
                )
            }
        }

        composable(
            route = AkeRoute.CHAT,
            arguments = listOf(
                navArgument(ChatViewModel.ARG_SESSION_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(ChatViewModel.ARG_AGENT_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            // ★ 全屏、不带底栏：输入框要贴着键盘，消息流要吃满高度
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTask = { navController.navigate(AkeRoute.TASK) },
                    onPrepareModel = { navController.navigate(AkeRoute.MODEL) },
                )
            }
        }

        composable(AkeRoute.TASK) {
            // ★ 全屏、不带底栏：确认弹窗出现时不该有任何东西与它抢注意力
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                TaskScreen(onBack = { navController.popBackStack() })
            }
        }

        composable(AkeRoute.MODEL_DOWNLOAD) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ModelScreen(
                    autoStart = true,
                    onModelReady = { navController.navigateToChatsClearingStart() },
                    onCancelled = {
                        navController.navigate(AkeRoute.MODEL_GATE) {
                            popUpTo(AkeRoute.MODEL_DOWNLOAD) { inclusive = true }
                        }
                    },
                )
            }
        }

        composable(AkeRoute.MODEL) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ModelScreen()
            }
        }

        composable(AkeRoute.TOOLS) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                McpScreen()
            }
        }

        composable(AkeRoute.AGENT_DETAIL) { entry ->
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AgentDetailScreen(
                    onBack = { navController.popBackStack() },
                    onClone = { navController.navigate(AkeRoute.createAgent(prefillProfileId = it)) },
                    onDeleted = { navController.popBackStack() },
                )
            }
        }

        composable(
            route = AkeRoute.AGENT_CREATE,
            arguments = listOf(
                navArgument("templateId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("prefillProfileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                CreateAgentScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { navController.popBackStack() },
                )
            }
        }

        composable(AkeRoute.TEMPLATE_PICKER) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                TemplatePickerScreen(
                    onBack = { navController.popBackStack() },
                    onUseTemplate = { navController.navigate(AkeRoute.createAgent(templateId = it)) },
                )
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
            NavigationBar(
                modifier = Modifier.navigationBarsPadding().heightIn(min = 64.dp),
                tonalElevation = 2.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                for ((route, labelRes) in TABS) {
                    NavigationBarItem(
                        // 路由带可选参数时 destination.route 是模板串（`agents?pick={pick}`），
                        // 所以这里按前缀比较，而不是全等
                        selected = selected == route || selected.startsWith(route.substringBefore("?")),
                        onClick = { navController.navigateToTab(route) },
                        icon = { },
                        label = {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        },
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
 * 切 tab：弹出到起始页再进目标，避免「对话 → Agent → 对话」堆出一串返回栈。
 * 端侧任务全程在前台，返回栈一深，用户按返回键就会在几个页面之间迷路。
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.navigateToChatsClearingStart() {
    navigate(AkeRoute.CHAT_LIST) {
        popUpTo(graph.findStartDestination().id) { inclusive = true }
        launchSingleTop = true
    }
}
