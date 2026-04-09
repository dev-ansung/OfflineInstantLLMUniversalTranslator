package dev.ansung.translator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ansung.translator.ui.theme.OfflineInstantLLMUniversalTranslatorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OfflineInstantLLMUniversalTranslatorTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    var showInputLangDialog by remember { mutableStateOf(false) }
    var showOutputLangDialog by remember { mutableStateOf(false) }
    var showCustomOutputDialog by remember { mutableStateOf(false) }

    // Stop all tasks when the app goes to the background to avoid hidden usage
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.stopAllTasks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.checkLlmStatus()
        viewModel.setAudioFileDir(context.cacheDir)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(
                            text = "An Universal Translator",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Offline Instant LLM Universal Translator",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                },
                actions = {
                    // "Instant" Mode Pill
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                        ) {
                            Text(
                                text = "Instant",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(4.dp))
                            Switch(
                                checked = viewModel.isInstantTranslationEnabled,
                                onCheckedChange = { viewModel.isInstantTranslationEnabled = it },
                                modifier = Modifier.scale(0.8f), // Scaled slightly for the pill container
                                thumbContent = if (viewModel.isInstantTranslationEnabled) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Bolt,
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (hasPermission) {
                        if (viewModel.isRecording) {
                            viewModel.stopRecording()
                        } else {
                            viewModel.startRecording()
                        }
                    } else {
                        launcher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                containerColor = if (viewModel.isRecording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = if (viewModel.isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (viewModel.isRecording) "Stop" else "Record"
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIndicator(label = "Speech", status = viewModel.speechStatus, modifier = Modifier.weight(1f))
                StatusIndicator(label = "LLM", status = viewModel.llmStatus, modifier = Modifier.weight(1f))
                if (!viewModel.isLlmDownloaded) {
                    Button(onClick = { viewModel.downloadLlm() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Download", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Input section
            SectionHeader(
                title = "Original text (${viewModel.inputLanguage.displayName})",
                onSettingsClick = { showInputLangDialog = true },
                textToCopy = viewModel.inputText,
                onRetryClick = { viewModel.retrySpeechRecognition() },
                isRetryEnabled = !viewModel.isRecording && !viewModel.isSpeechProcessing && viewModel.hasAudioFile()
            )
            val isSpeechClickable = !viewModel.isRecording && !viewModel.isSpeechProcessing && viewModel.hasAudioFile()
            TextBox(
                text = viewModel.inputText,
                onTextChange = { viewModel.inputText = it },
                isEditable = true,
                placeholder = "Speech input will appear here...",
                modifier = Modifier
                    .then(
                        if (isSpeechClickable) {
                            Modifier
                                .clickable { viewModel.retrySpeechRecognition() }
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        } else Modifier
                    )
            )

            // Translated (Final) section
            val outputDisplay = viewModel.customOutputLanguage.ifBlank { viewModel.outputLanguage.displayName }
            SectionHeader(
                title = "Translated ($outputDisplay)",
                onSettingsClick = { showOutputLangDialog = true },
                textToCopy = viewModel.translatedText,
                onRetryClick = { viewModel.retryTranslation() },
                isRetryEnabled = !viewModel.isTranslationProcessing && viewModel.inputText.isNotEmpty()
            )
            val isTranslationClickable = !viewModel.isTranslationProcessing && viewModel.inputText.isNotEmpty()
            TextBox(
                text = viewModel.translatedText,
                placeholder = "Final translation result...",
                modifier = Modifier
                    .then(
                        if (isTranslationClickable) {
                            Modifier
                                .clickable { viewModel.retryTranslation() }
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        } else Modifier
                    )
            )

            // Translating (Streaming) section
            SectionHeader(
                title = "Translating",
                showSettings = false,
                textToCopy = viewModel.translatingText
            )
            TextBox(
                text = viewModel.translatingText,
                placeholder = "Streaming translation...",
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
        }
    }

    if (showInputLangDialog) {
        LanguageSelectionDialog(
            title = "Input Language Option",
            currentLanguage = viewModel.inputLanguage,
            onDismiss = { showInputLangDialog = false },
            onSelect = {
                viewModel.updateInputLanguage(it)
                showInputLangDialog = false
            }
        )
    }

    if (showOutputLangDialog) {
        LanguageSelectionDialog(
            title = "Output Language Option",
            currentLanguage = viewModel.outputLanguage,
            showSettings = true,
            onSettingsClick = {
                showOutputLangDialog = false
                showCustomOutputDialog = true
            },
            onDismiss = { showOutputLangDialog = false },
            onSelect = {
                viewModel.outputLanguage = it
                viewModel.customOutputLanguage = "" // Reset custom if a standard lang is selected
                showOutputLangDialog = false
            }
        )
    }

    if (showCustomOutputDialog) {
        CustomLanguageDialog(
            currentValue = viewModel.customOutputLanguage,
            onDismiss = { showCustomOutputDialog = false },
            onConfirm = {
                viewModel.customOutputLanguage = it
                showCustomOutputDialog = false
            }
        )
    }
}

@Composable
fun StatusIndicator(label: String, status: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (status.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    showSettings: Boolean = true,
    onSettingsClick: () -> Unit = {},
    textToCopy: String = "",
    onRetryClick: (() -> Unit)? = null,
    isRetryEnabled: Boolean = true
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Row {
            if (textToCopy.isNotEmpty()) {
                IconButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("text", textToCopy)))
                    }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy to clipboard")
                }
            }
            if (onRetryClick != null) {
                IconButton(
                    onClick = onRetryClick,
                    enabled = isRetryEnabled
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = if (isRetryEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
            if (showSettings) {
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }
    }
}

@Composable
fun TextBox(
    text: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onTextChange: (String) -> Unit = {},
    isEditable: Boolean = false,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    if (isEditable) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            placeholder = { Text(placeholder) },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = backgroundColor,
                unfocusedContainerColor = backgroundColor,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .background(backgroundColor, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            if (text.isEmpty()) {
                Text(
                    text = placeholder,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                SelectionContainer {
                    Text(text = text)
                }
            }
        }
    }
}

@Composable
fun LanguageSelectionDialog(
    title: String,
    currentLanguage: Language,
    showSettings: Boolean = false,
    onSettingsClick: () -> Unit = {},
    onDismiss: () -> Unit,
    onSelect: (Language) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val allLanguages = remember {
        LanguageProvider.getLanguages(context)
    }
    
    val filteredLanguages = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            allLanguages
        } else {
            allLanguages.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.localName.contains(searchQuery, ignoreCase = true) ||
                it.languageCode.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Type a query...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    if (showSettings) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Custom language")
                        }
                    }
                }
                
                LazyColumn(modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)) {
                    items(filteredLanguages) { language ->
                        ListItem(
                            headlineContent = { Text(language.displayName) },
                            supportingContent = { Text("${language.localName} (${language.languageCode})") },
                            modifier = Modifier.clickable { onSelect(language) },
                            trailingContent = {
                                if (language.languageCode == currentLanguage.languageCode) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CustomLanguageDialog(
    currentValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom language") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                placeholder = { Text("e.g. Meme language composed of entirely emojis") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
