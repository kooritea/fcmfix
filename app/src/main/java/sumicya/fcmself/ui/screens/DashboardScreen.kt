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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sumicya.fcmself.R
import sumicya.fcmself.core.Contract
import sumicya.fcmself.ui.viewmodel.AppViewModel

/**
 * Dashboard screen — shows an overview of the module's status at a glance:
 * service status, config health, app stats, the main boolean switches and the
 * live hook health report.
 */
@Composable
fun DashboardScreen(
    viewModel: AppViewModel,
) {
    val apps by viewModel.apps.collectAsState()
    val switches by viewModel.switches.collectAsState()
    val serviceConnected by viewModel.serviceConnected.collectAsState()
    val configReady by viewModel.configReady.collectAsState()
    val configError by viewModel.configError.collectAsState()
    val healthStatus by viewModel.healthStatus.collectAsState()

    val totalApps = apps.size
    val allowedApps = apps.count { it.isAllow }
    val fcmApps = apps.count { it.includeFcm }
    val disallowedFcm = apps.count { it.includeFcm && !it.isAllow }

    // Run the nonce-tagged health query whenever the dashboard is (re)opened.
    LaunchedEffect(Unit) {
        viewModel.requestHealthCheck()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_dashboard),
                        fontWeight = FontWeight.Bold,
                    )
                },
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
                    title = stringResource(R.string.service_status),
                    icon = Icons.Filled.CheckCircle,
                    iconTint = if (serviceConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (serviceConnected) {
                                R.string.service_connected
                            } else {
                                R.string.service_disconnected
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (serviceConnected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    if (serviceConnected) {
                        Text(
                            text = stringResource(
                                if (configReady) {
                                    R.string.config_ready
                                } else {
                                    R.string.config_not_ready
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (configReady) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    if (configError) {
                        Text(
                            text = stringResource(R.string.config_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── App statistics card ────────────────────────────────────
            item {
                StatCard(
                    title = stringResource(R.string.app_stats),
                    icon = Icons.Filled.Apps,
                    iconTint = MaterialTheme.colorScheme.primary,
                ) {
                    StatRow(stringResource(R.string.installed_apps), totalApps.toString())
                    StatRow(stringResource(R.string.fcm_apps), fcmApps.toString())
                    StatRow(stringResource(R.string.allowed_apps), allowedApps.toString())
                    StatRow(stringResource(R.string.pending_fcm_apps), disallowedFcm.toString())
                }
            }

            // ── FCM status ─────────────────────────────────────────────
            item {
                StatCard(
                    title = stringResource(R.string.fcm_status),
                    icon = Icons.Filled.Cloud,
                    iconTint = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = if (fcmApps > 0) {
                            pluralStringResource(R.plurals.fcm_scanned_count, fcmApps, fcmApps)
                        } else {
                            stringResource(R.string.fcm_none_scanned)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.fcm_allowed_ratio,
                            allowedApps,
                            allowedApps,
                            fcmApps,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (disallowedFcm > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.fcm_pending_hint,
                                disallowedFcm,
                                disallowedFcm,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── Feature toggles ────────────────────────────────────────
            item {
                StatCard(
                    title = stringResource(R.string.feature_toggles),
                    icon = Icons.Filled.Settings,
                    iconTint = MaterialTheme.colorScheme.primary,
                ) {
                    ToggleRow(
                        label = stringResource(R.string.toggle_disable_auto_clean),
                        description = stringResource(R.string.toggle_disable_auto_clean_desc),
                        checked = switches[Contract.KEY_DISABLE_AUTO_CLEAN] ?: false,
                        onCheckedChange = {
                            viewModel.setSwitchEnabled(Contract.KEY_DISABLE_AUTO_CLEAN, it)
                        },
                    )
                    ToggleRow(
                        label = stringResource(R.string.toggle_include_icebox),
                        description = stringResource(R.string.toggle_include_icebox_desc),
                        checked = switches[Contract.KEY_INCLUDE_ICEBOX] ?: false,
                        onCheckedChange = {
                            viewModel.setSwitchEnabled(Contract.KEY_INCLUDE_ICEBOX, it)
                        },
                    )
                    ToggleRow(
                        label = stringResource(R.string.toggle_no_response),
                        description = stringResource(R.string.toggle_no_response_desc),
                        checked = switches[Contract.KEY_NO_RESPONSE_NOTIFICATION] ?: false,
                        onCheckedChange = {
                            viewModel.setSwitchEnabled(Contract.KEY_NO_RESPONSE_NOTIFICATION, it)
                        },
                    )
                }
            }

            // ── Health status ──────────────────────────────────────────
            item {
                StatCard(
                    title = stringResource(R.string.hook_status),
                    icon = Icons.Filled.Favorite,
                    iconTint = MaterialTheme.colorScheme.primary,
                    action = {
                        TextButton(onClick = { viewModel.requestHealthCheck() }) {
                            Text(stringResource(R.string.hook_status_recheck))
                        }
                    },
                ) {
                    Text(
                        text = healthStatus.ifEmpty {
                            stringResource(R.string.hook_status_unknown)
                        },
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
    iconTint: Color,
    action: (@Composable () -> Unit)? = null,
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
                Spacer(Modifier.weight(1f))
                action?.invoke()
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
