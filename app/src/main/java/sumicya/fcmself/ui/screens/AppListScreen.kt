@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package sumicya.fcmself.ui.screens

import android.widget.ImageView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sumicya.fcmself.data.AppCatalog
import sumicya.fcmself.data.IconLoader
import sumicya.fcmself.model.AppInfo
import sumicya.fcmself.model.FilterMode
import sumicya.fcmself.ui.viewmodel.AppViewModel

/**
 * The main application grid — fully Compose-based.
 * Features: pull-to-refresh, long-press detail sheet, search, filter chips.
 */
@Composable
fun AppListScreen(
    viewModel: AppViewModel,
) {
    val filteredApps by viewModel.filteredApps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val filterMode by viewModel.filterMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val serviceConnected by viewModel.serviceConnected.collectAsState()
    val configReady by viewModel.configReady.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    val scope = rememberCoroutineScope()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()

    // App detail bottom sheet
    if (selectedApp != null) {
        AppDetailSheet(
            appInfo = selectedApp!!,
            onDismiss = { selectedApp = null },
            onToggle = {
                viewModel.toggleApp(selectedApp!!)
                selectedApp = null
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("Fcmself", fontWeight = FontWeight.Bold)
                        if (!serviceConnected) {
                            Text(
                                "服务未连接",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (!configReady) {
                            Text(
                                "配置加载中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("启用全部 FCM") },
                                onClick = {
                                    showMenu = false
                                    viewModel.enableAllFcm()
                                },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("FCM Diagnostics") },
                                onClick = {
                                    showMenu = false
                                    viewModel.openDiagnostics(LocalContext.current)
                                },
                                leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新应用列表") },
                                onClick = {
                                    showMenu = false
                                    AppCatalog.invalidate()
                                    viewModel.refreshApps()
                                },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                AppCatalog.invalidate()
                viewModel.refreshApps()
                scope.launch {
                    delay(500)
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Search + Filter row ────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showSearch) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("搜索应用…") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清除")
                                    }
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            shape = RoundedCornerShape(28.dp),
                        )
                    }

                    FilterChip(
                        selected = filterMode == FilterMode.ALL,
                        onClick = { viewModel.setFilterMode(FilterMode.ALL) },
                        label = { Text("全部") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.FCM_ONLY,
                        onClick = { viewModel.setFilterMode(FilterMode.FCM_ONLY) },
                        label = { Text("FCM") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.ALLOWED,
                        onClick = { viewModel.setFilterMode(FilterMode.ALLOWED) },
                        label = { Text("已开") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.DISALLOWED,
                        onClick = { viewModel.setFilterMode(FilterMode.DISALLOWED) },
                        label = { Text("已关") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }

                // ── App grid ───────────────────────────────────────────
                if (isLoading && filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "正在扫描应用…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "没有匹配的应用",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        state = gridState,
                    ) {
                        items(
                            items = filteredApps,
                            key = { it.packageName },
                        ) { appInfo ->
                            AppGridItem(
                                appInfo = appInfo,
                                configReady = configReady,
                                onClick = { viewModel.toggleApp(appInfo) },
                                onLongClick = { selectedApp = appInfo },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── App Grid Item ───────────────────────────────────────────────────────────

@Composable
private fun AppGridItem(
    appInfo: AppInfo,
    configReady: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isActive = appInfo.isAllow
    val hasFcm = appInfo.includeFcm

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            hasFcm && configReady -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = spring(),
        label = "bg",
    )

    val contentColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        hasFcm && configReady -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIconView(
                appInfo = appInfo,
                modifier = Modifier.size(44.dp),
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = appInfo.name,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            val statusIcon = when {
                isActive -> Icons.Filled.Check
                else -> Icons.Filled.Close
            }
            Icon(
                imageVector = statusIcon,
                contentDescription = if (isActive) "已允许" else "已禁止/不支持",
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(16.dp),
                tint = contentColor,
            )
        }
    }
}

// ── App Detail Bottom Sheet ──────────────────────────────────────────────────

@Composable
private fun AppDetailSheet(
    appInfo: AppInfo,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconView(
                    appInfo = appInfo,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = appInfo.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = appInfo.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            DetailRow("包名", appInfo.packageName)
            DetailRow("支持 FCM", if (appInfo.includeFcm) "是" else "否")
            DetailRow("当前状态", if (appInfo.isAllow) "已允许唤醒" else "已禁止唤醒")

            Spacer(Modifier.height(20.dp))

            // Toggle button
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                colors = CardDefaults.cardColors(
                    containerColor = if (appInfo.isAllow)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (appInfo.isAllow) Icons.Filled.Close else Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (appInfo.isAllow)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (appInfo.isAllow) "禁止唤醒" else "允许唤醒",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (appInfo.isAllow)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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

// ── App Icon View ───────────────────────────────────────────────────────────

@Composable
private fun AppIconView(
    appInfo: AppInfo,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val defaultIcon = remember { packageManager.defaultActivityIcon }

    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(defaultIcon)
                IconLoader.load(
                    packageManager,
                    appInfo,
                    defaultIcon,
                ) { /* icon cached */ }
            }
        },
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)),
        update = { imageView ->
            val cached = IconLoader.cached(appInfo.packageName)
            if (cached != null) {
                imageView.setImageDrawable(cached)
            } else {
                imageView.setImageDrawable(defaultIcon)
            }
        },
    )
}