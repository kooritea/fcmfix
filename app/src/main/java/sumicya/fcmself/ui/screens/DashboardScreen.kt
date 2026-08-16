@file:OptIn(ExperimentalMaterial3Api::class)

package sumicya.fcmself.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sumicya.fcmself.core.Contract
import sumicya.fcmself.ui.viewmodel.AppViewModel

/**
 * Dashboard screen — shows an overview of the module's status at a glance.
 *
 * New feature: a central place to see service status, config health, app stats,
 * and toggle the main boolean switches without the old FAB popup.
 */
@Composable
fun DashboardScreen(
    viewModel: AppViewModel,
) {
    val apps by viewModel.apps.collectAsState()
    val serviceConnected by viewModel.serviceConnected.collectAsState()
    val configReady by viewModel.configReady.collectAsState()
    val configError by viewModel.configError.collectAsState()
    val healthStatus by viewModel.healthStatus.collectAsState()

    val totalApps = apps.size
    val allowedApps = apps.count { it.isAllow }
    val fcmApps = apps.count { it.includeFcm }
    val disallowedFcm = apps.count { it.includeFcm && !it.isAllow }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("概览", fontWeight = FontWeight.Bold) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Service status card ────────────────────────────────────
            item {
                StatCard(
                    title = "服务状态",
                    icon = Icons.Filled.CheckCircle,
                    iconTint = if (serviceConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                ) {
                    Text(
                        if (serviceConnected) "Xposed 服务已连接" else "Xposed 服务未连接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (serviceConnected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                    )
                    if (serviceConnected) {
                        Text(
                            if (configReady) "配置已就绪" else "配置尚未加载",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (configReady) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (configError) {
                        Text(
                            "配置错误",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── App statistics card ────────────────────────────────────
            item {
                StatCard(
                    title = "应用统计",
                    icon = Icons.Filled.Apps,
                    iconTint = MaterialTheme.colorScheme.primary,
                ) {
                    StatRow("已安装的应用", totalApps.toString())
                    StatRow("支持 FCM 的应用", fcmApps.toString())
                    StatRow("已允许唤醒", allowedApps.toString())
                    StatRow("待允许 (FCM)", disallowedFcm.toString())
                }
            }

            // ── FCM status ─────────────────────────────────────────────
            item {
                StatCard(
                    title = "FCM 状态",
                    icon = Icons.Filled.Cloud,
                    iconTint = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        if (fcmApps > 0) "扫描到 $fcmApps 个 FCM 应用"
                        else "未扫描到 FCM 应用",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "已允许 $allowedApps / $fcmApps 个 FCM 应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (disallowedFcm > 0) {
                        Text(
                            "$disallowedFcm 个 FCM 应用待允许",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── Feature toggles ────────────────────────────────────────
            item {
                StatCard(
                    title = "功能开关",
                    icon = Icons.Filled.Settings,
                    iconTint = MaterialTheme.colorScheme.primary,
                ) {
                    ToggleRow(
                        label = "停止应用时保留通知",
                        description = "防止系统在应用停止时清除通知",
                        checked = viewModel.isSwitchEnabled(Contract.KEY_DISABLE_AUTO_CLEAN),
                        onCheckedChange = {
                            viewModel.setSwitchEnabled(Contract.KEY_DISABLE_AUTO_CLEAN, it)
                        },
                    )
                    ToggleRow(
                        label = "唤醒冰箱冻结应用",
                        description = "允许唤醒被 Icebox 冻结的应用",
                        checked = viewModel.isSwitchEnabled(Contract.KEY_INCLUDE_ICEBOX),
                        onCheckedChange = {
                            viewModel.setSwitchEnabled(Contract.KEY_INCLUDE_ICEBOX, it)
                        },
                    )
                    ToggleRow(
                        label = "无响应时提示",
                        description = "FCM 无响应时发送通知提示",
                        checked = viewModel.isSwitchEnabled(Contract.KEY_NO_RESPONSE_NOTIFICATION),
                        onCheckedChange = {
                            viewModel.setSwitchEnabled(Contract.KEY_NO_RESPONSE_NOTIFICATION, it)
                        },
                    )
                }
            }

            // ── Health status ──────────────────────────────────────────
            item {
                StatCard(
                    title = "Hook 状态",
                    icon = Icons.Filled.Favorite,
                    iconTint = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        healthStatus.ifEmpty { "尚未检查" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ── Reusable composables ───────────────────────────────────────────────────

@Composable
private fun StatCard(
    title: String,
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                checkedThumbColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}