package com.example.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.api.GeminiClient
import com.example.data.pref.SecurePreferences
import com.example.webbuilder.GeneratedWebsiteProject
import com.example.webbuilder.WebsiteBuilderManager
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsiteBuilderScreen(
    initialPrompt: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val prefs = remember { SecurePreferences(context) }
    val geminiClient = remember { GeminiClient() }
    val webManager = remember { WebsiteBuilderManager(context) }

    var promptText by remember(initialPrompt) { mutableStateOf(initialPrompt.orEmpty()) }
    var currentTitle by remember { mutableStateOf("MYRAA Web Studio") }
    var currentHtml by remember {
        mutableStateOf(
            WebsiteBuilderManager.buildStarterTemplate("Modern Personal Portfolio & AI Showcase")
        )
    }
    var savedProjects by remember { mutableStateOf<List<GeneratedWebsiteProject>>(emptyList()) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Preview, 1 = HTML Code, 2 = Saved
    var isMobileViewport by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var refineMode by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(currentHtml.toByteArray(Charsets.UTF_8))
                    }
                    snackbarHostState.showSnackbar("Website HTML exported successfully!")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Export failed: ${e.localizedMessage}")
                }
            }
        }
    }

    fun buildWebsite(requestPrompt: String, useExistingHtml: Boolean) {
        val clean = requestPrompt.trim()
        if (clean.isBlank() || isGenerating) return
        focusManager.clearFocus()
        isGenerating = true
        scope.launch {
            val res = geminiClient.generateWebsiteHtml(
                prompt = clean,
                apiKey = prefs.getGeminiApiKey(),
                preferredModel = prefs.getSelectedGeminiModel(),
                existingHtml = if (useExistingHtml) currentHtml else null
            )
            res.onSuccess { generatedHtml ->
                val title = WebsiteBuilderManager.deriveTitleFromPrompt(clean)
                currentTitle = title
                currentHtml = generatedHtml
                val saved = webManager.saveProject(title, clean, generatedHtml)
                savedProjects = webManager.getSavedProjects()
                selectedTab = 0
                snackbarHostState.showSnackbar("Website ready & saved: ${saved.title}")
            }.onFailure { err ->
                snackbarHostState.showSnackbar("Build failed: ${err.localizedMessage ?: "Error"}")
            }
            isGenerating = false
        }
    }

    LaunchedEffect(Unit) {
        savedProjects = webManager.getSavedProjects()
        if (!initialPrompt.isNullOrBlank()) {
            buildWebsite(initialPrompt, useExistingHtml = false)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Web Studio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = currentTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("web_builder_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val fileSlug = currentTitle
                                .lowercase()
                                .replace(Regex("[^a-z0-9]+"), "-")
                                .trim('-')
                                .ifBlank { "myra-website" }
                            exportLauncher.launch("$fileSlug.html")
                        },
                        modifier = Modifier.testTag("web_builder_export_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export HTML",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Prompt + Build Card (Material You Tonal Surface)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("web_builder_prompt_input"),
                        label = {
                            Text(
                                if (refineMode) "Describe edits to update current site..."
                                else "Describe website to build (e.g. Coffee Shop landing page)"
                            )
                        },
                        singleLine = false,
                        maxLines = 3,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { buildWebsite(promptText, refineMode) }
                        )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Modern Portfolio" to "Personal developer portfolio with projects & contact card",
                            "Cafe Menu" to "Stylish cafe & bakery website with menu pricing cards",
                            "Fitness Club" to "Fitness studio landing page with membership plans",
                            "Priyanshu Page" to "Cute aesthetic romantic memory & photo timeline webpage for Priyanshu"
                        ).forEach { (chipLabel, chipPrompt) ->
                            AssistChip(
                                onClick = { promptText = chipPrompt },
                                label = { Text(chipLabel, fontSize = 12.sp) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = refineMode,
                            onClick = { refineMode = !refineMode },
                            label = { Text("Edit Current") }
                        )
                        Button(
                            onClick = { buildWebsite(promptText, refineMode) },
                            enabled = !isGenerating && promptText.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("web_builder_generate_button"),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Building Website...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (refineMode) "Update Site" else "Build Website")
                            }
                        }
                    }
                }
            }

            // Tab Bar: Preview | Code | Saved + Viewport Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        leadingIcon = {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Preview") },
                        modifier = Modifier.testTag("web_builder_tab_preview")
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        leadingIcon = {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Code") },
                        modifier = Modifier.testTag("web_builder_tab_code")
                    )
                    FilterChip(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        leadingIcon = {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Saved (${savedProjects.size})") },
                        modifier = Modifier.testTag("web_builder_tab_saved")
                    )
                }

                if (selectedTab == 0) {
                    IconButton(
                        onClick = { isMobileViewport = !isMobileViewport },
                        modifier = Modifier.testTag("web_builder_viewport_toggle")
                    ) {
                        Icon(
                            imageVector = if (isMobileViewport) Icons.Default.PhoneAndroid else Icons.Default.DesktopWindows,
                            contentDescription = if (isMobileViewport) "Mobile Width" else "Full Width",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Workspace Area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp
            ) {
                when (selectedTab) {
                    0 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(if (isMobileViewport) 8.dp else 0.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            val webModifier = if (isMobileViewport) {
                                Modifier
                                    .widthIn(max = 390.dp)
                                    .fillMaxSize()
                                    .clip(MaterialTheme.shapes.medium)
                            } else {
                                Modifier.fillMaxSize()
                            }
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.allowFileAccess = false
                                        webViewClient = WebViewClient()
                                        loadDataWithBaseURL(
                                            "https://myraa.local/",
                                            currentHtml,
                                            "text/html",
                                            "UTF-8",
                                            null
                                        )
                                    }
                                },
                                update = { webView ->
                                    val previousHtml = webView.tag as? String
                                    if (previousHtml != currentHtml) {
                                        webView.tag = currentHtml
                                        webView.loadDataWithBaseURL(
                                            "https://myraa.local/",
                                            currentHtml,
                                            "text/html",
                                            "UTF-8",
                                            null
                                        )
                                    }
                                },
                                modifier = webModifier.testTag("web_builder_webview")
                            )
                        }
                    }

                    1 -> {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "index.html (${currentHtml.length} chars)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(currentHtml))
                                        scope.launch {
                                            snackbarHostState.showSnackbar("HTML code copied to clipboard!")
                                        }
                                    },
                                    modifier = Modifier.testTag("web_builder_copy_html_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy HTML")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = currentHtml,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    else -> {
                        if (savedProjects.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No saved websites yet.\nBuild one above!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(savedProjects, key = { it.id }) { project ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                currentTitle = project.title
                                                currentHtml = project.htmlContent
                                                promptText = project.prompt
                                                selectedTab = 0
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ),
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = project.title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = project.prompt,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        webManager.deleteProject(project.id)
                                                        savedProjects = webManager.getSavedProjects()
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete Website",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
