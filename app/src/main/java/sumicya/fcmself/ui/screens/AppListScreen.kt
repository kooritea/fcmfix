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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sumicya.fcmself.R
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
    val context = LocalContext.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()

    // App detail bottom sheet
    val detailApp = selectedApp
    if (detailApp != null) {
        AppDetailSheet(
            appInfo = detailApp,
            onDismiss = { selectedApp = null },
            onToggle = {
                viewModel.toggleApp(detailApp)
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
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                        )
                        if (!serviceConnected) {
                            Text(
                                text = stringResource(R.string.service_not_connected),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (!configReady) {
                            Text(
                                text = stringResource(R.string.config_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.enable_all_fcm)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.enableAllFcm()
                                },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.fcm_diagnostics)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.openDiagnostics(context)
                                },
                                leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.refresh_app_list)) },
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
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.clear_search),
                                        )
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
                        label = { Text(stringResource(R.string.filter_all)) },
                        colors = filterChipColors(),
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.FCM_ONLY,
                        onClick = { viewModel.setFilterMode(FilterMode.FCM_ONLY) },
                        label = { Text(stringResource(R.string.filter_fcm)) },
                        colors = filterChipColors(),
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.ALLOWED,
                        onClick = { viewModel.setFilterMode(FilterMode.ALLOWED) },
                        label = { Text(stringResource(R.string.filter_allowed)) },
                        colors = filterChipColors(),
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.DISALLOWED,
                        onClick = { viewModel.setFilterMode(FilterMode.DISALLOWED) },
                        label = { Text(stringResource(R.string.filter_disallowed)) },
                        colors = filterChipColors(),
                    )
                }

                // ── App grid ───────────────────────────────────────────
                when {
                    isLoading && filteredApps.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.scanning_apps),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    filteredApps.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.empty_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
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
}

@Composable
private fun filterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

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

            Icon(
                imageVector = if (isActive) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = stringResource(
                    if (isActive) R.string.status_allowed_desc else R.string.status_disallowed_desc,
                ),
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

            DetailRow(
                stringResource(R.string.detail_package),
                appInfo.packageName,
            )
            DetailRow(
                stringResource(R.string.detail_fcm_support),
                stringResource(if (appInfo.includeFcm) R.string.yes else R.string.no),
            )
            DetailRow(
                stringResource(R.string.detail_status),
                stringResource(
                    if (appInfo.isAllow) R.string.allowed_wakeup else R.string.disallowed_wakeup,
                ),
            )

            Spacer(Modifier.height(20.dp))

            // Toggle button
            val disallow = appInfo.isAllow
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                colors = CardDefaults.cardColors(
                    containerColor = if (disallow) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
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
                        imageVector = if (disallow) Icons.Filled.Close else Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (disallow) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (disallow) R.string.disallow_wakeup else R.string.allow_wakeup,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (disallow) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
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
    val packageName = appInfo.packageName
    val defaultIcon = remember { packageManager.defaultActivityIcon }

    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(IconLoader.cached(packageName) ?: defaultIcon)
                // Async decode: the callback fires on the main thread once the
                // icon is in the cache, so the ImageView can pick it up directly.
                IconLoader.load(packageManager, appInfo, defaultIcon) {
                    val cached = IconLoader.cached(packageName)
                    setImageDrawable(cached ?: defaultIcon)
                }
            }
        },
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)),
        update = { imageView ->
            val cached = IconLoader.cached(packageName)
            if (cached != null) {
                imageView.setImageDrawable(cached)
            }
        },
    )
}
