package ir.tafakoritech.adminapplication.ui.herald

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.*
import ir.tafakoritech.adminapplication.data.AuthTokenStore
import kotlinx.coroutines.launch

private enum class HeraldTab { Posts, Articles, Pages }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeraldScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val token = remember { AuthTokenStore(context).apiKey.orEmpty() }
    val tabs = HeraldTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Herald CMS") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(tab.name) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { page ->
            when (tabs[page]) {
                HeraldTab.Posts    -> PostsTab(token)
                HeraldTab.Articles -> ArticlesTab(token)
                HeraldTab.Pages    -> PagesTab(token)
            }
        }
    }
}

// ── Posts tab ─────────────────────────────────────────────────────────────────

@Composable
private fun PostsTab(token: String) {
    val scope = rememberCoroutineScope()
    val posts = remember { mutableStateListOf<HeraldPostResponse>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(1) }
    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<HeraldContentItem?>(null) }

    fun load(p: Int = 1) {
        scope.launch {
            loading = true; error = null
            runCatching { HeraldClient.listAllPosts(p, token = token) }
                .onSuccess { resp ->
                    if (p == 1) posts.clear()
                    posts.addAll(resp.data)
                    page = resp.page
                    totalPages = resp.totalPages
                }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    ContentListTab(
        items = posts.toItems(),
        loading = loading,
        error = error,
        onRetry = { load() },
        onLoadMore = if (page < totalPages) ({ load(page + 1) }) else null,
        onPublish = { id -> scope.launch { runCatching { HeraldClient.publishContent(id, token) }.onSuccess { load() } } },
        onUnpublish = { id -> scope.launch { runCatching { HeraldClient.unpublishContent(id, token) }.onSuccess { load() } } },
        onFab = { showCreate = true },
        onEdit = { editTarget = it },
        onDelete = { id -> scope.launch { runCatching { HeraldClient.deletePost(id, token) }.onSuccess { load() } } },
    )

    if (showCreate) {
        SimpleContentDialog(
            title = "New post",
            onDismiss = { showCreate = false },
            onConfirm = { t, excerpt, body ->
                scope.launch {
                    runCatching {
                        HeraldClient.createPost(CreatePostRequest(title = t, excerpt = excerpt, body = body), token)
                    }.onSuccess { showCreate = false; load() }
                }
            },
        )
    }
    editTarget?.let { item ->
        SimpleContentDialog(
            title = "Edit post",
            initialTitle = item.title,
            initialExcerpt = item.excerpt,
            initialBody = item.body,
            onDismiss = { editTarget = null },
            onConfirm = { t, excerpt, body ->
                scope.launch {
                    runCatching {
                        HeraldClient.updatePost(item.resourceId, UpdatePostRequest(title = t, excerpt = excerpt, body = body), token)
                    }.onSuccess { editTarget = null; load() }
                }
            },
        )
    }
}

// ── Articles tab ──────────────────────────────────────────────────────────────

@Composable
private fun ArticlesTab(token: String) {
    val scope = rememberCoroutineScope()
    val articles = remember { mutableStateListOf<HeraldArticleResponse>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(1) }
    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<HeraldContentItem?>(null) }

    fun load(p: Int = 1) {
        scope.launch {
            loading = true; error = null
            runCatching { HeraldClient.listAllArticles(p, token = token) }
                .onSuccess { resp ->
                    if (p == 1) articles.clear()
                    articles.addAll(resp.data)
                    page = resp.page
                    totalPages = resp.totalPages
                }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    ContentListTab(
        items = articles.toArticleItems(),
        loading = loading,
        error = error,
        onRetry = { load() },
        onLoadMore = if (page < totalPages) ({ load(page + 1) }) else null,
        onPublish = { id -> scope.launch { runCatching { HeraldClient.publishContent(id, token) }.onSuccess { load() } } },
        onUnpublish = { id -> scope.launch { runCatching { HeraldClient.unpublishContent(id, token) }.onSuccess { load() } } },
        onFab = { showCreate = true },
        onEdit = { editTarget = it },
        onDelete = { id -> scope.launch { runCatching { HeraldClient.deleteArticle(id, token) }.onSuccess { load() } } },
    )

    if (showCreate) {
        SimpleContentDialog(
            title = "New article",
            onDismiss = { showCreate = false },
            onConfirm = { t, excerpt, body ->
                scope.launch {
                    runCatching {
                        HeraldClient.createArticle(CreateArticleRequest(title = t, excerpt = excerpt, body = body), token)
                    }.onSuccess { showCreate = false; load() }
                }
            },
        )
    }
    editTarget?.let { item ->
        SimpleContentDialog(
            title = "Edit article",
            initialTitle = item.title,
            initialExcerpt = item.excerpt,
            initialBody = item.body,
            onDismiss = { editTarget = null },
            onConfirm = { t, excerpt, body ->
                scope.launch {
                    runCatching {
                        HeraldClient.updateArticle(item.resourceId, UpdateArticleRequest(title = t, excerpt = excerpt, body = body), token)
                    }.onSuccess { editTarget = null; load() }
                }
            },
        )
    }
}

// ── Pages tab ─────────────────────────────────────────────────────────────────

@Composable
private fun PagesTab(token: String) {
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<HeraldPageResponse>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(1) }
    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<HeraldPageResponse?>(null) }
    var viewTarget by remember { mutableStateOf<HeraldPageResponse?>(null) }

    fun load(p: Int = 1) {
        scope.launch {
            loading = true; error = null
            runCatching { HeraldClient.listPages(p, token = token) }
                .onSuccess { resp ->
                    if (p == 1) pages.clear()
                    pages.addAll(resp.data)
                    page = resp.page
                    totalPages = resp.totalPages
                }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    if (viewTarget != null) {
        val p = viewTarget!!
        BackHandler { viewTarget = null }
        PageDetailView(
            page = p,
            onBack = { viewTarget = null },
            onEdit = { viewTarget = null; editTarget = p },
            onDelete = {
                scope.launch {
                    runCatching { HeraldClient.deletePage(p.id, token) }
                        .onSuccess { viewTarget = null; load() }
                }
            },
            onPublish = { scope.launch { runCatching { HeraldClient.publishContent(p.content.id, token) }.onSuccess { load() } } },
            onUnpublish = { scope.launch { runCatching { HeraldClient.unpublishContent(p.content.id, token) }.onSuccess { load() } } },
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            when {
                loading && pages.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null && pages.isEmpty() -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Failed to load", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { load() }) { Text("Retry") }
                }
                pages.isEmpty() -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.Article, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("No pages yet", style = MaterialTheme.typography.titleMedium)
                    Text("Tap + to create", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(pages, key = { it.id }) { p ->
                        PageCard(
                            page = p,
                            onClick = { viewTarget = p },
                            onPublish = {
                                scope.launch { runCatching { HeraldClient.publishContent(p.content.id, token) }.onSuccess { load() } }
                            },
                            onUnpublish = {
                                scope.launch { runCatching { HeraldClient.unpublishContent(p.content.id, token) }.onSuccess { load() } }
                            },
                            onEdit = { editTarget = p },
                            onDelete = {
                                scope.launch { runCatching { HeraldClient.deletePage(p.id, token) }.onSuccess { load() } }
                            },
                        )
                    }
                    if (page < totalPages) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) {
                                TextButton(onClick = { load(page + 1) }) { Text("Load more") }
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { showCreate = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Rounded.Add, null) }
        }
    }

    if (showCreate) {
        PageFormDialog(
            initial = null,
            onDismiss = { showCreate = false },
            onConfirm = { title, route, body, excerpt ->
                scope.launch {
                    runCatching {
                        HeraldClient.createPage(CreatePageRequest(title = title, route = route, body = body, excerpt = excerpt), token)
                    }.onSuccess { showCreate = false; load() }
                }
            },
        )
    }
    editTarget?.let { p ->
        PageFormDialog(
            initial = p,
            onDismiss = { editTarget = null },
            onConfirm = { title, route, body, excerpt ->
                scope.launch {
                    runCatching {
                        HeraldClient.updatePage(p.id, UpdatePageRequest(title = title, route = route, body = body, excerpt = excerpt), token)
                    }.onSuccess { editTarget = null; load() }
                }
            },
        )
    }
}

@Composable
private fun PageCard(
    page: HeraldPageResponse,
    onClick: () -> Unit,
    onPublish: () -> Unit,
    onUnpublish: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(page.content.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(page.route, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(page.content.status)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            if (expanded) {
                if (page.content.excerpt.isNotBlank()) {
                    Text(page.content.excerpt, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (page.content.status == "published") {
                        TextButton(onClick = onUnpublish) { Text("Unpublish") }
                    } else {
                        TextButton(onClick = onPublish) { Text("Publish") }
                    }
                    if (!page.isSystem) {
                        TextButton(onClick = onEdit) { Text("Edit") }
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete page?") },
            text = { Text("\"${page.content.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

// ── Page detail view ──────────────────────────────────────────────────────────

@Composable
private fun PageDetailView(
    page: HeraldPageResponse,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPublish: () -> Unit,
    onUnpublish: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(page.content.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(page.route, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusChip(page.content.status)
        }

        HorizontalDivider()

        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (page.content.status == "published") {
                TextButton(onClick = onUnpublish) { Text("Unpublish") }
            } else {
                TextButton(onClick = onPublish) { Text("Publish") }
            }
            if (!page.isSystem) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            }
        }

        HorizontalDivider()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page.content.slug.isNotBlank()) {
                item { PageInfoRow("Slug", page.content.slug) }
            }
            if (page.template.isNotBlank()) {
                item { PageInfoRow("Template", page.template) }
            }
            if (page.content.excerpt.isNotBlank()) {
                item {
                    Text("Excerpt", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(page.content.excerpt, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (page.content.body.isNotBlank()) {
                item {
                    Text("Body", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(page.content.body, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (page.content.seoTitle.isNotBlank() || page.content.seoDescription.isNotBlank()) {
                item {
                    Text("SEO", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    if (page.content.seoTitle.isNotBlank())
                        Text("Title: ${page.content.seoTitle}", style = MaterialTheme.typography.bodySmall)
                    if (page.content.seoDescription.isNotBlank())
                        Text("Description: ${page.content.seoDescription}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete page?") },
            text = { Text("\"${page.content.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PageInfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("$label: ", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// ── Shared content list ───────────────────────────────────────────────────────

private data class HeraldContentItem(
    val id: Long,         // content_id — for publish/unpublish
    val resourceId: Long, // post/article own id — for update/delete
    val title: String,
    val slug: String,
    val status: String,
    val excerpt: String,
    val body: String = "",
)

private fun List<HeraldPostResponse>.toItems() = map {
    HeraldContentItem(it.content.id, it.id, it.content.title, it.content.slug, it.content.status, it.content.excerpt, it.content.body)
}

private fun List<HeraldArticleResponse>.toArticleItems() = map {
    HeraldContentItem(it.content.id, it.id, it.content.title, it.content.slug, it.content.status, it.content.excerpt, it.content.body)
}

@Composable
private fun ContentListTab(
    items: List<HeraldContentItem>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onLoadMore: (() -> Unit)?,
    onPublish: (Long) -> Unit,
    onUnpublish: (Long) -> Unit,
    onFab: () -> Unit,
    onEdit: ((HeraldContentItem) -> Unit)? = null,
    onDelete: ((Long) -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            loading && items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            error != null && items.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Failed to load", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            items.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Article, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))
                Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                Text("Tap + to create", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    ContentCard(
                        item,
                        onPublish = { onPublish(item.id) },
                        onUnpublish = { onUnpublish(item.id) },
                        onEdit = onEdit?.let { cb -> { cb(item) } },
                        onDelete = onDelete?.let { cb -> { cb(item.resourceId) } },
                    )
                }
                if (onLoadMore != null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) {
                            TextButton(onClick = onLoadMore) { Text("Load more") }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onFab,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) { Icon(Icons.Rounded.Add, null) }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentCard(
    item: HeraldContentItem,
    onPublish: () -> Unit,
    onUnpublish: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.slug, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(item.status)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            if (expanded) {
                if (item.excerpt.isNotBlank()) {
                    Text(item.excerpt, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.status == "published") {
                        TextButton(onClick = onUnpublish) { Text("Unpublish") }
                    } else {
                        TextButton(onClick = onPublish) { Text("Publish") }
                    }
                    if (onEdit != null) TextButton(onClick = onEdit) { Text("Edit") }
                    if (onDelete != null) TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete?") },
            text = { Text("\"${item.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false; onDelete?.invoke() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = if (status == "published") Color(0xFF4CAF50) else Color(0xFFFF9800)
    Box(
        Modifier.padding(horizontal = 4.dp)
            .wrapContentSize()
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = color.copy(alpha = 0.15f),
        ) {
            Text(
                status.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun SimpleContentDialog(
    title: String,
    initialTitle: String = "",
    initialExcerpt: String = "",
    initialBody: String = "",
    onDismiss: () -> Unit,
    onConfirm: (title: String, excerpt: String, body: String) -> Unit,
) {
    var t by remember { mutableStateOf(initialTitle) }
    var excerpt by remember { mutableStateOf(initialExcerpt) }
    var body by remember { mutableStateOf(initialBody) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(t, { t = it }, label = { Text("Title *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(excerpt, { excerpt = it }, label = { Text("Excerpt") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3)
                OutlinedTextField(body, { body = it }, label = { Text("Body") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 6)
            }
        },
        confirmButton = {
            TextButton(enabled = t.isNotBlank(), onClick = { onConfirm(t.trim(), excerpt.trim(), body.trim()) }) {
                Text(if (initialTitle.isBlank()) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PageFormDialog(
    initial: HeraldPageResponse?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, route: String, body: String, excerpt: String) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.content?.title ?: "") }
    var route by remember { mutableStateOf(initial?.route ?: "") }
    var excerpt by remember { mutableStateOf(initial?.content?.excerpt ?: "") }
    var body by remember { mutableStateOf(initial?.content?.body ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New page" else "Edit page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(route, { route = it }, label = { Text("Route *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("/about") })
                OutlinedTextField(excerpt, { excerpt = it }, label = { Text("Excerpt") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3)
                OutlinedTextField(body, { body = it }, label = { Text("Body") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 6)
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && route.isNotBlank(),
                onClick = { onConfirm(title.trim(), route.trim(), body.trim(), excerpt.trim()) },
            ) { Text(if (initial == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
