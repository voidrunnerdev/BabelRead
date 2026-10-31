package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.i18n.AppLanguages
import com.example.i18n.Text
import com.example.i18n.TranslationLanguages
import com.example.ui.components.AppScaffold
import com.example.ui.components.AppTopBar
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.LanguagePickerOption
import com.example.ui.components.SectionHeader
import com.example.ui.components.SettingRow
import com.example.ui.components.SurfaceCard
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import com.example.ui.theme.Success
import com.example.ui.theme.Warning
import com.example.viewmodel.MangaViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: MangaViewModel) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val uriHandler = LocalUriHandler.current

    val uiLanguage by viewModel.preferences.uiLanguage.collectAsState(initial = AppLanguages.SYSTEM)
    var showAppLanguagePicker by remember { mutableStateOf(false) }
    val readingMode by viewModel.readingDirection.collectAsState()
    val pageQuality by viewModel.pageQuality.collectAsState()
    val brightnessLock by viewModel.brightnessLock.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()

    val translationEngine by viewModel.preferences.translationEngine.collectAsState(initial = "Otomatik")
    val ocrEngine by viewModel.preferences.ocrEngine.collectAsState(initial = "ML Kit")
    val translationBubbleStyle by viewModel.translationBubbleStyle.collectAsState()
    val translationOpacity by viewModel.translationOpacity.collectAsState()
    val geminiApiKey by viewModel.preferences.geminiApiKey.collectAsState(initial = "")
    val geminiModel by viewModel.preferences.geminiModel.collectAsState(initial = "gemini-flash-latest")
    val deeplApiKey by viewModel.preferences.deeplApiKey.collectAsState(initial = "")
    val engineTestState by viewModel.engineTestState.collectAsState()
    val engineTestRunning by viewModel.engineTestRunning.collectAsState()
    val translationTargetLanguage by viewModel.translationTargetLanguage.collectAsState()
    val translationSourceLanguage by viewModel.translationSourceLanguage.collectAsState()
    val recentTranslationLanguages by viewModel.recentTranslationLanguages.collectAsState()
    var showSourceLanguagePicker by remember { mutableStateOf(false) }
    var showTargetLanguagePicker by remember { mutableStateOf(false) }
    val sourceLanguageLabel = if (translationSourceLanguage == TranslationLanguages.AUTO) {
        "Otomatik"
    } else {
        TranslationLanguages.turkishName(translationSourceLanguage) ?: translationSourceLanguage
    }
    val targetLanguageLabel =
        TranslationLanguages.turkishName(translationTargetLanguage) ?: translationTargetLanguage
    val autoTranslate by viewModel.preferences.autoTranslate.collectAsState(initial = false)
    val turkishConvert by viewModel.preferences.turkishCharConvert.collectAsState(initial = false)

    val downloadLocation by viewModel.preferences.downloadLocation.collectAsState(initial = "Dahili")
    val cacheSizeLimit by viewModel.preferences.cacheSizeLimit.collectAsState(initial = 500)
    val notificationEnabled by viewModel.preferences.notificationNewChapter.collectAsState(initial = true)
    val checkInterval by viewModel.preferences.updateCheckInterval.collectAsState(initial = 12)
    val translationCacheStats by viewModel.translationCacheStats.collectAsState()

    val yoloAiEnabled by viewModel.preferences.yoloAiEnabled.collectAsState(initial = true)
    val confidenceThreshold by viewModel.preferences.confidenceThreshold.collectAsState(initial = 0.35f)

    val isYoloLoaded = remember(yoloAiEnabled) { viewModel.isYoloModelLoaded() }
    val yoloInfo = remember(yoloAiEnabled) { viewModel.getYoloModelInfo() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(engineTestState) {
        val message = engineTestState
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            viewModel.clearEngineTestState()
        }
    }
    var modelImportErrorDialog by remember { mutableStateOf<String?>(null) }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importYoloModel(it) { success, errorMsg ->
                coroutineScope.launch {
                    if (success) {
                        snackbarHostState.showSnackbar("RT-DETR-v2 ONNX modeli başarıyla yüklendi! Balon çerçeveleri ve metin blokları tespit edilecek.")
                    } else {
                        modelImportErrorDialog = errorMsg ?: "Model dosyası doğrulanamadı."
                        snackbarHostState.showSnackbar("Model yüklenemedi: ${errorMsg ?: "Bilinmeyen hata"}", duration = SnackbarDuration.Long)
                    }
                }
            }
        }
    }

    var geminiModelInput by remember { mutableStateOf(geminiModel) }
    LaunchedEffect(geminiModel) {
        geminiModelInput = geminiModel
    }
    LaunchedEffect(geminiModelInput) {
        if (geminiModelInput != geminiModel) {
            kotlinx.coroutines.delay(600)
            viewModel.preferences.setGeminiModel(geminiModelInput)
        }
    }

    var apiKeyStorageError by remember { mutableStateOf(false) }
    var geminiKeyInput by remember { mutableStateOf(geminiApiKey) }
    LaunchedEffect(geminiApiKey) {
        if (geminiKeyInput.isEmpty()) geminiKeyInput = geminiApiKey
    }
    LaunchedEffect(geminiKeyInput) {
        if (geminiKeyInput != geminiApiKey) {
            kotlinx.coroutines.delay(600)
            apiKeyStorageError = !viewModel.preferences.setGeminiApiKey(geminiKeyInput)
        }
    }

    var deeplKeyInput by remember { mutableStateOf(deeplApiKey) }
    LaunchedEffect(deeplApiKey) {
        if (deeplKeyInput.isEmpty()) deeplKeyInput = deeplApiKey
    }
    LaunchedEffect(deeplKeyInput) {
        if (deeplKeyInput != deeplApiKey) {
            kotlinx.coroutines.delay(600)
            apiKeyStorageError = !viewModel.preferences.setDeepLApiKey(deeplKeyInput)
        }
    }

    AppScaffold(
        topBar = {
            AppTopBar(title = "BabelRead Ayarları")
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xl)
        ) {
            SettingsSection("Dil") {
                SettingsCard {
                    SettingsLanguageRow(
                        label = "Uygulama Dili",
                        value = if (uiLanguage == AppLanguages.SYSTEM) {
                            "Sistem varsayılanı"
                        } else {
                            AppLanguages.resolve(uiLanguage).nativeName
                        },
                        onClick = { showAppLanguagePicker = true }
                    )
                    Text(
                        text = "Telefonunuzun dili kullanılır. Desteklenmeyen bir dilde uygulama İngilizce açılır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = AppSpacing.lg,
                            end = AppSpacing.lg,
                            bottom = AppSpacing.md
                        )
                    )
                }
            }

            SettingsSection("Yapay Zeka & Balon Algılama Motoru") {
                SettingsCard {
                    SettingRow(
                        title = "Yapay Zeka ile Konuşma Balonu Tespiti",
                        subtitle = "Manga sayfalarındaki konuşma balonları ve metin bloklarını sinir ağıyla algılar.",
                        trailing = {
                            Switch(
                                checked = yoloAiEnabled,
                                onCheckedChange = {
                                    coroutineScope.launch { viewModel.preferences.setYoloAiEnabled(it) }
                                }
                            )
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        SettingsInfoRow(label = "Motor:", value = "RT-DETR-v2 Comic AI (Gömülü)")
                        SettingsInfoRow(
                            label = "Durum:",
                            value = if (isYoloLoaded) "Aktif & Entegre (RT-DETR-v2 INT8)" else "Model Bekleniyor (Opsiyonel)",
                            valueColor = if (isYoloLoaded) Success else Warning
                        )
                        SettingsInfoRow(
                            label = "Dedektör Durumu:",
                            value = if (isYoloLoaded) {
                                yoloInfo["status"] ?: "Aktif & Yüklendi"
                            } else {
                                yoloInfo["status"] ?: "Model Bekleniyor"
                            },
                            valueColor = if (isYoloLoaded) Success else Warning
                        )
                        SettingsInfoRow(
                            label = "Model Dosya Boyutu:",
                            value = yoloInfo["file_size"] ?: "0 bayt"
                        )

                        if ((yoloInfo["last_error"] ?: "Hata Yok") != "Hata Yok") {
                            Surface(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(AppSpacing.md),
                                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                                ) {
                                    Text(
                                        text = "Model Yükleme Tanı / Hata:",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = yoloInfo["last_error"] ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(AppSpacing.md),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                            ) {
                                Text(
                                    text = "RT-DETR-v2 & Akıllı Kutu Prensibi:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "• Balon & Metin Tespiti: Konuşma balonunu (balloon) ve balon içi metin bölgesini (text_bubble) transformer yapay zekasıyla keskin olarak saptar.\n• Metin Merkezli Yerleşim: Türkçe çeviri, balonun kuyruğuna kaymadan orijinal metnin merkezine hizalanır.\n• Ses Efekti (SFX) Filtresi: Çizim üzerindeki dış ve arka plan ses efektlerini (text_free) otomatik olarak eler.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = AppSizes.minTouchTarget),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isYoloLoaded) {
                                    Success
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = null,
                                modifier = Modifier.size(AppSizes.icon)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(
                                text = if (isYoloLoaded) {
                                    "Yeni detector.onnx Dosyası Yükle"
                                } else {
                                    "Harici detector.onnx Yükle"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "Algılama Hassasiyeti (Confidence): %${(confidenceThreshold * 100).toInt()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = confidenceThreshold,
                            onValueChange = {
                                coroutineScope.launch { viewModel.preferences.setConfidenceThreshold(it) }
                            },
                            valueRange = 0.20f..0.80f,
                            steps = 12,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            SettingsSection("Okuyucu Ayarları") {
                SettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg)
                    ) {
                        SettingsSubHeader("Varsayılan Okuma Yönü")
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        SegmentedChoice(
                            options = listOf("Dikey", "Yatay"),
                            selected = readingMode,
                            onSelect = { mode ->
                                coroutineScope.launch { viewModel.preferences.setReadingMode(mode) }
                            }
                        )

                        Spacer(modifier = Modifier.height(AppSpacing.lg))
                        SettingsSubHeader("Görsel Sayfa Kalitesi")
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        SegmentedChoice(
                            options = listOf("Düşük", "Orta", "Yüksek"),
                            selected = pageQuality,
                            onSelect = { quality ->
                                coroutineScope.launch { viewModel.preferences.setPageQuality(quality) }
                            }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsToggleOption(
                        title = "Ekran Parlaklığı Kilidi (Maksimum)",
                        description = "Okuyucu açıkken ekran parlaklığını en üst düzeye sabitler.",
                        checked = brightnessLock,
                        onCheckedChange = {
                            coroutineScope.launch { viewModel.preferences.setBrightnessLock(it) }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsToggleOption(
                        title = "Ekranı Açık Tut",
                        description = "Manga okurken cihaz ekranının otomatik uykuya geçmesini engeller.",
                        checked = keepScreenOn,
                        onCheckedChange = {
                            coroutineScope.launch { viewModel.preferences.setKeepScreenOn(it) }
                        }
                    )
                }
            }

            SettingsSection("Çeviri Motoru & Baloncuk Stili") {
                SettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsSubHeader(
                                title = "Öncelikli Çeviri Motoru",
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { viewModel.testSelectedTranslationEngine() },
                                enabled = !engineTestRunning,
                                contentPadding = PaddingValues(horizontal = AppSpacing.sm)
                            ) {
                                Text(
                                    text = if (engineTestRunning) "Test ediliyor…" else "Test Et",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        ScrollableChoice(
                            options = listOf("Otomatik", "Gemini", "DeepL", "ML Kit", "Google"),
                            selected = translationEngine,
                            onSelect = { engine ->
                                coroutineScope.launch { viewModel.preferences.setTranslationEngine(engine) }
                            }
                        )
                        val engineNotice = when {
                            translationEngine.equals("Gemini", ignoreCase = true) && geminiApiKey.isBlank() ->
                                "Gemini için API anahtarı gerekli. Anahtar girilmezse yedek motorlar kullanılır."
                            translationEngine.equals("DeepL", ignoreCase = true) && deeplApiKey.isBlank() ->
                                "DeepL için API anahtarı gerekli. Anahtar girilmezse yedek motorlar kullanılır."
                            translationEngine.equals("ML Kit", ignoreCase = true) ->
                                "ML Kit ilk kullanımda dil modelini indirir; indirme bitene kadar çeviri bekleyebilir."
                            else -> null
                        }
                        if (engineNotice != null) {
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            Text(
                                text = engineNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = Warning
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg)
                    ) {
                        SettingsSubHeader("OCR Motoru")
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        SegmentedChoice(
                            options = listOf("ML Kit", "PP-OCRv5"),
                            selected = ocrEngine,
                            onSelect = { engine -> viewModel.setOcrEngine(engine) }
                        )
                        if (ocrEngine.equals("PP-OCRv5", ignoreCase = true)) {
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            Text(
                                text = if (yoloAiEnabled) {
                                    "PP-OCRv5 deneysel: yalnızca İngilizce ve Japonca; RT-DETR metin kutuları kullanılır."
                                } else {
                                    "PP-OCRv5 için RT-DETR açık olmalı; şu anda tüm sayfa OCR'ı devre dışı."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Warning
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppSpacing.md, bottom = AppSpacing.sm)
                    ) {
                        SettingsSubHeader(
                            title = "Çeviri Dilleri",
                            modifier = Modifier.padding(horizontal = AppSpacing.lg)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        SettingsLanguageRow(
                            label = "Kaynak Dil",
                            value = sourceLanguageLabel,
                            onClick = { showSourceLanguagePicker = true }
                        )
                        SettingsLanguageRow(
                            label = "Hedef Dil",
                            value = targetLanguageLabel,
                            onClick = { showTargetLanguagePicker = true }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg)
                    ) {
                        SettingsSubHeader("Balon & Yazı Stili")
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        ScrollableChoice(
                            options = listOf("Doğal Balon", "Alt Yazı (Şeffaf)", "Koyu Zemin"),
                            selected = translationBubbleStyle,
                            onSelect = { style ->
                                coroutineScope.launch {
                                    viewModel.preferences.setTranslationBubbleStyle(style)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        Text(
                            text = "Balon Opaklığı: %$translationOpacity",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = translationOpacity.toFloat(),
                            onValueChange = {
                                coroutineScope.launch { viewModel.preferences.setTranslationOpacity(it.toInt()) }
                            },
                            valueRange = 20f..100f,
                            steps = 15,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsToggleOption(
                        title = "Otomatik Çeviri Modu",
                        description = "Sayfa her açıldığında seçili hedef dile çeviriyi otomatik başlatır.",
                        checked = autoTranslate,
                        onCheckedChange = {
                            coroutineScope.launch { viewModel.preferences.setAutoTranslate(it) }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg)
                    ) {
                        SettingsSubHeader("Gemini Modeli")
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        SegmentedChoice(
                            options = listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-3.5-flash-lite"),
                            selected = geminiModel,
                            onSelect = { model ->
                                geminiModelInput = model
                                coroutineScope.launch { viewModel.preferences.setGeminiModel(model) }
                            },
                            label = { it.removePrefix("gemini-") }
                        )

                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = geminiModelInput,
                            onValueChange = { geminiModelInput = it },
                            label = { Text("Model Adı (Örn: gemini-flash-latest)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        OutlinedTextField(
                            value = geminiKeyInput,
                            onValueChange = { geminiKeyInput = it },
                            label = { Text("Gemini API Anahtarı") },
                            placeholder = { Text("Gemini API anahtarını girin...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = deeplKeyInput,
                            onValueChange = { deeplKeyInput = it },
                            label = { Text("DeepL API Anahtarı (İsteğe Bağlı)") },
                            placeholder = { Text("DeepL anahtarını girin...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (apiKeyStorageError) {
                            Spacer(modifier = Modifier.height(AppSpacing.xs))
                            Text(
                                text = "API anahtarı Android Keystore ile şifrelenemedi ve kaydedilmedi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            SettingsSection("Depolama Ayarları") {
                SettingsCard {
                    SettingRow(
                        title = "İndirme Konumu",
                        subtitle = downloadLocation,
                        leadingIcon = Icons.Default.Folder
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg)
                    ) {
                        Text(
                            text = "Önbellek Boyutu Sınırı: $cacheSizeLimit MB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = cacheSizeLimit.toFloat(),
                            onValueChange = {
                                coroutineScope.launch { viewModel.preferences.setCacheSizeLimit(it.toInt()) }
                            },
                            valueRange = 100f..2000f,
                            steps = 19
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        Button(
                            onClick = {
                                viewModel.clearDownloadedCache()
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Tüm indirmeler ve önbellek temizlendi.")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = AppSizes.minTouchTarget)
                        ) {
                            Text(
                                text = "Tüm İndirmeleri ve Önbelleği Temizle",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            SettingsSection("Çeviri Önbelleği") {
                SettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg)
                    ) {
                        Text(
                            text = "Çevrilen sayfalar; yüklü kaynaklar (eklentiler ve Cihaz/Yerel) için cihazda saklanır ve uygulama yeniden açıldığında beklemeden gösterilir. Kayıtlar en fazla 24 saat tutulur; süresi dolanlar otomatik silinir. Tarayıcıdan (özel siteler) yapılan çeviriler saklanmaz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (translationCacheStats.isEmpty()) {
                        Text(
                            text = "Çeviri önbelleği şu an boş.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = AppSpacing.lg,
                                end = AppSpacing.lg,
                                bottom = AppSpacing.lg
                            )
                        )
                    } else {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        translationCacheStats.forEachIndexed { index, stat ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(horizontal = AppSpacing.lg)
                                )
                            }
                            SettingRow(
                                title = stat.sourceName.ifBlank { "Bilinmeyen Sağlayıcı" },
                                subtitle = "${stat.pageCount} sayfa • ${formatCacheBytes(stat.totalBytes)}",
                                leadingIcon = Icons.Default.Translate,
                                trailing = {
                                    TextButton(
                                        onClick = {
                                            viewModel.clearTranslationCache(stat.sourceName)
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("${stat.sourceName.ifBlank { "Sağlayıcı" }} çeviri önbelleği temizlendi.")
                                            }
                                        }
                                    ) {
                                        Text(
                                            text = "Temizle",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(AppSpacing.lg)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.clearTranslationCache(null)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Tüm çeviri önbelleği temizlendi.")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = AppSizes.minTouchTarget)
                            ) {
                                Text(
                                    text = "Tümünü Temizle (${formatCacheBytes(translationCacheStats.sumOf { it.totalBytes })})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection("Bildirim Ayarları") {
                SettingsCard {
                    SettingsToggleOption(
                        title = "Yeni Bölüm Bildirimleri",
                        description = "Kütüphanedeki serileri periyodik olarak arka planda kontrol eder.",
                        checked = notificationEnabled,
                        onCheckedChange = {
                            coroutineScope.launch { viewModel.preferences.setNotificationNewChapter(it) }
                        }
                    )
                }
            }

            SettingsSection("Hakkında") {
                SettingsCard {
                    SettingRow(
                        title = "Uygulama",
                        subtitle = "BabelRead",
                        leadingIcon = Icons.Default.Apps
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(
                        title = "Sürüm",
                        subtitle = BuildConfig.VERSION_NAME,
                        leadingIcon = Icons.Default.Build
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(
                        onClick = { uriHandler.openUri("https://github.com/voidrunnerdev/BabelRead") },
                        contentPadding = PaddingValues(horizontal = AppSpacing.lg),
                        modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(AppSizes.icon)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        Text(
                            text = "Kaynak Kodu (GitHub)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                SurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Text(
                            text = "Açık Kaynak Lisansları",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• Tachiyomi/Mihon eklenti API'si — Apache License 2.0\n" +
                                "• AndroidX & Jetpack Compose — Apache License 2.0\n" +
                                "• OkHttp — Apache License 2.0\n" +
                                "• Kotlinx Coroutines — Apache License 2.0\n" +
                                "• Coil — Apache License 2.0\n" +
                                "• ONNX Runtime — MIT License\n" +
                                "• RT-DETR-v2 balon tespiti (PaddleDetection) — Apache License 2.0\n" +
                                "• Google ML Kit (metin tanıma) — Google Şartları\n" +
                                "• Google Çeviri / DeepL / Gemini — ilgili sağlayıcı şartları",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        Text(
                            text = "Bu uygulama hiçbir manga içeriği veya eklenti deposu barındırmaz; tüm içerik kullanıcının eklediği kaynaklardan alınır. BabelRead bağımsız bir açık kaynak projedir; Tachiyomi, Mihon, Keiyoushi veya içerik sağlayıcılarıyla bağlantılı değildir. Eklenen depoların ve içeriklerin kullanım sorumluluğu kullanıcıya aittir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.xxl))
        }
    }

    if (modelImportErrorDialog != null) {
        AlertDialog(
            onDismissRequest = { modelImportErrorDialog = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.error,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(
                    text = "Model Yükleme Hatası",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = modelImportErrorDialog ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        text = "İpucu: Dosyanın telefonunuza eksiksiz indiğinden (genellikle 5-15 MB civarında) ve geçerli bir ONNX dosyası olduğundan emin olun.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { modelImportErrorDialog = null }) {
                    Text("Anladım")
                }
            }
        )
    }

    if (showAppLanguagePicker) {
        val options = buildList {
            add(LanguagePickerOption(code = AppLanguages.SYSTEM, title = "Sistem varsayılanı"))
            AppLanguages.popular.forEach { lang ->
                add(
                    LanguagePickerOption(
                        code = lang.code,
                        title = lang.nativeName,
                        translateTitle = false,
                    )
                )
            }
        }
        LanguagePickerDialog(
            title = "Dil Seçimi",
            options = options,
            selectedCode = uiLanguage,
            onSelect = {
                coroutineScope.launch { viewModel.preferences.setUiLanguage(it) }
                showAppLanguagePicker = false
            },
            onDismiss = { showAppLanguagePicker = false },
        )
    }

    if (showSourceLanguagePicker) {
        val options = buildList {
            add(LanguagePickerOption(code = TranslationLanguages.AUTO, title = "Otomatik", nativeName = "Manga bilgisinden algıla"))
            TranslationLanguages.sources.forEach { lang ->
                add(LanguagePickerOption(code = lang.code, title = lang.turkishName, nativeName = lang.nativeName))
            }
        }
        LanguagePickerDialog(
            title = "Kaynak Dil",
            options = options,
            selectedCode = translationSourceLanguage,
            recentCodes = recentTranslationLanguages,
            onSelect = {
                viewModel.setTranslationSourceLanguage(it)
                showSourceLanguagePicker = false
            },
            onDismiss = { showSourceLanguagePicker = false },
        )
    }

    if (showTargetLanguagePicker) {
        val options = TranslationLanguages.targets.map { lang ->
            LanguagePickerOption(code = lang.code, title = lang.turkishName, nativeName = lang.nativeName)
        }
        LanguagePickerDialog(
            title = "Hedef Dil",
            options = options,
            selectedCode = translationTargetLanguage,
            recentCodes = recentTranslationLanguages,
            onSelect = {
                viewModel.setTranslationTargetLanguage(it)
                showTargetLanguagePicker = false
            },
            onDismiss = { showTargetLanguagePicker = false },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        SectionHeader(title = title)
        content()
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    SurfaceCard(
        contentPadding = PaddingValues(0.dp),
        content = content
    )
}

@Composable
private fun SettingsSubHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SettingsLanguageRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    SettingRow(
        title = label,
        subtitle = value,
        trailing = {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        onClick = onClick
    )
}

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun ScrollableChoice(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedChoice(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (String) -> String = { it }
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            )
        }
    }
}

@Composable
fun SettingsToggleOption(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingRow(
        title = title,
        subtitle = description,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

private fun formatCacheBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1048576.0
    return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
