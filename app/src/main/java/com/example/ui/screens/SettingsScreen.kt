package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.BuildConfig
import com.example.i18n.AppLanguages
import com.example.i18n.Text
import com.example.i18n.TranslationLanguages
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.LanguagePickerOption
import com.example.viewmodel.MangaViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BabelRead Ayarları", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF161616),
                    titleContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. UYGULAMA DİLİ
            SettingsCategoryHeader(title = "Dil", icon = Icons.Default.Language)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsLanguageRow(
                        label = "Uygulama Dili",
                        value = if (uiLanguage == AppLanguages.SYSTEM) {
                            "Sistem varsayılanı"
                        } else {
                            AppLanguages.resolve(uiLanguage).nativeName
                        },
                        onClick = { showAppLanguagePicker = true }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Telefonunuzun dili kullanılır. Desteklenmeyen bir dilde uygulama İngilizce açılır.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            // 2. YAPAY ZEKA & MODEL DURUMU
            SettingsCategoryHeader(title = "Yapay Zeka & Balon Algılama Motoru", icon = Icons.Default.Psychology)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Yapay Zeka ile Konuşma Balonu Tespiti", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Manga sayfalarındaki konuşma balonları ve metin bloklarını sinir ağıyla algılar.", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = yoloAiEnabled,
                            onCheckedChange = { coroutineScope.launch { viewModel.preferences.setYoloAiEnabled(it) } }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Motor:", color = Color.Gray, fontSize = 12.sp)
                        Text("RT-DETR-v2 Comic AI (Gömülü)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Durum:", color = Color.Gray, fontSize = 12.sp)
                        Text(
                            text = if (isYoloLoaded) "Aktif & Entegre (RT-DETR-v2 INT8)" else "Model Bekleniyor (Opsiyonel)",
                            color = if (isYoloLoaded) Color(0xFF00E676) else Color(0xFFFFB74D),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Dedektör Durumu:", color = Color.Gray, fontSize = 12.sp)
                        Text(
                            text = if (isYoloLoaded) yoloInfo["status"] ?: "Aktif & Yüklendi" else yoloInfo["status"] ?: "Model Bekleniyor",
                            color = if (isYoloLoaded) Color(0xFF00E676) else Color(0xFFFFB74D),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Model Dosya Boyutu:", color = Color.Gray, fontSize = 12.sp)
                        Text(
                            text = yoloInfo["file_size"] ?: "0 bayt",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }

                    if ((yoloInfo["last_error"] ?: "Hata Yok") != "Hata Yok") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF331111)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Model Yükleme Tanı / Hata:", color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(yoloInfo["last_error"] ?: "", color = Color.White, fontSize = 11.sp, lineHeight = 14.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "RT-DETR-v2 & Akıllı Kutu Prensibi:",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• Balon & Metin Tespiti: Konuşma balonunu (balloon) ve balon içi metin bölgesini (text_bubble) transformer yapay zekasıyla keskin olarak saptar.\n• Metin Merkezli Yerleşim: Türkçe çeviri, balonun kuyruğuna kaymadan orijinal metnin merkezine hizalanır.\n• Ses Efekti (SFX) Filtresi: Çizim üzerindeki dış ve arka plan ses efektlerini (text_free) otomatik olarak eler.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isYoloLoaded) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isYoloLoaded) "Yeni detector.onnx Dosyası Yükle" else "Harici detector.onnx Yükle",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    if (modelImportErrorDialog != null) {
                        AlertDialog(
                            onDismissRequest = { modelImportErrorDialog = null },
                            title = { Text("Model Yükleme Hatası", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    Text(
                                        text = modelImportErrorDialog ?: "",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        lineHeight = 17.sp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "İpucu: Dosyanın telefonunuza eksiksiz indiğinden (genellikle 5-15 MB civarında) ve geçerli bir ONNX dosyası olduğundan emin olun.",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                }
                            },
                            confirmButton = {
                                Button(onClick = { modelImportErrorDialog = null }) {
                                    Text("Anladım")
                                }
                            },
                            containerColor = Color(0xFF242424)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Algılama Hassasiyeti (Confidence): %${(confidenceThreshold * 100).toInt()}", color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = confidenceThreshold,
                        onValueChange = { coroutineScope.launch { viewModel.preferences.setConfidenceThreshold(it) } },
                        valueRange = 0.20f..0.80f,
                        steps = 12,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. OKUYUCU AYARLARI
            SettingsCategoryHeader(title = "Okuyucu Ayarları", icon = Icons.Default.MenuBook)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Varsayılan Okuma Yönü", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    val modes = listOf("Dikey", "Yatay")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        modes.forEach { m ->
                            val isSel = readingMode == m
                            Button(
                                onClick = { coroutineScope.launch { viewModel.preferences.setReadingMode(m) } },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                                )
                            ) {
                                Text(m, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Görsel Sayfa Kalitesi", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    val qualities = listOf("Düşük", "Orta", "Yüksek")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        qualities.forEach { q ->
                            val isSel = pageQuality == q
                            Button(
                                onClick = { coroutineScope.launch { viewModel.preferences.setPageQuality(q) } },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(q, color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsToggleOption(
                        title = "Ekran Parlaklığı Kilidi (Maksimum)",
                        description = "Okuyucu açıkken ekran parlaklığını en üst düzeye sabitler.",
                        checked = brightnessLock,
                        onCheckedChange = { coroutineScope.launch { viewModel.preferences.setBrightnessLock(it) } }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleOption(
                        title = "Ekranı Açık Tut",
                        description = "Manga okurken cihaz ekranının otomatik uykuya geçmesini engeller.",
                        checked = keepScreenOn,
                        onCheckedChange = { coroutineScope.launch { viewModel.preferences.setKeepScreenOn(it) } }
                    )
                }
            }

            // 3. ÇEVİRİ MOTORU VE DİL AYARLARI
            SettingsCategoryHeader(title = "Çeviri Motoru & Baloncuk Stili", icon = Icons.Default.Translate)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Öncelikli Çeviri Motoru", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        TextButton(
                            onClick = { viewModel.testSelectedTranslationEngine() },
                            enabled = !engineTestRunning,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                if (engineTestRunning) "Test ediliyor…" else "Test Et",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val engines = listOf("Otomatik", "Gemini", "DeepL", "ML Kit", "Google")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        engines.forEach { e ->
                            val isSel = translationEngine == e
                            Button(
                                onClick = { coroutineScope.launch { viewModel.preferences.setTranslationEngine(e) } },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                                ),
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                Text(e, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
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
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(engineNotice, color = Color(0xFFFFB74D), fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Çeviri Dilleri", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsLanguageRow(
                        label = "Kaynak Dil",
                        value = sourceLanguageLabel,
                        onClick = { showSourceLanguagePicker = true }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsLanguageRow(
                        label = "Hedef Dil",
                        value = targetLanguageLabel,
                        onClick = { showTargetLanguagePicker = true }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Balon & Yazı Stili", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    val styles = listOf("Doğal Balon", "Alt Yazı (Şeffaf)", "Koyu Zemin")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        styles.forEach { st ->
                            val isSel = translationBubbleStyle == st
                            Button(
                                onClick = { coroutineScope.launch { viewModel.preferences.setTranslationBubbleStyle(st) } },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(st, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Balon Opaklığı: %$translationOpacity", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Slider(
                        value = translationOpacity.toFloat(),
                        onValueChange = { coroutineScope.launch { viewModel.preferences.setTranslationOpacity(it.toInt()) } },
                        valueRange = 20f..100f,
                        steps = 15,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsToggleOption(
                        title = "Otomatik Çeviri Modu",
                        description = "Sayfa her açıldığında seçili hedef dile çeviriyi otomatik başlatır.",
                        checked = autoTranslate,
                        onCheckedChange = { coroutineScope.launch { viewModel.preferences.setAutoTranslate(it) } }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Gemini Modeli", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    val geminiModels = listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-3.5-flash-lite")
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
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        geminiModels.forEach { m ->
                            val isSel = geminiModel == m
                            Button(
                                onClick = {
                                    geminiModelInput = m
                                    coroutineScope.launch { viewModel.preferences.setGeminiModel(m) }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(m.removePrefix("gemini-"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = geminiModelInput,
                        onValueChange = { geminiModelInput = it },
                        label = { Text("Model Adı (Örn: gemini-flash-latest)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))
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
                    OutlinedTextField(
                        value = geminiKeyInput,
                        onValueChange = { geminiKeyInput = it },
                        label = { Text("Gemini API Anahtarı") },
                        placeholder = { Text("Gemini API anahtarını girin...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))
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
                    OutlinedTextField(
                        value = deeplKeyInput,
                        onValueChange = { deeplKeyInput = it },
                        label = { Text("DeepL API Anahtarı (İsteğe Bağlı)") },
                        placeholder = { Text("DeepL anahtarını girin...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )
                    if (apiKeyStorageError) {
                        Text(
                            "API anahtarı Android Keystore ile şifrelenemedi ve kaydedilmedi.",
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 4. DEPOLAMA AYARLARI
            SettingsCategoryHeader(title = "Depolama Ayarları", icon = Icons.Default.Storage)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("İndirme Konumu", color = Color.LightGray, fontSize = 12.sp)
                    Text(downloadLocation, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Önbellek Boyutu Sınırı: $cacheSizeLimit MB", color = Color.White, fontSize = 14.sp)
                    Slider(
                        value = cacheSizeLimit.toFloat(),
                        onValueChange = { coroutineScope.launch { viewModel.preferences.setCacheSizeLimit(it.toInt()) } },
                        valueRange = 100f..2000f,
                        steps = 19
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            viewModel.clearDownloadedCache()
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Tüm indirmeler ve önbellek temizlendi.")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Tüm İndirmeleri ve Önbelleği Temizle", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 5. ÇEVİRİ ÖNBELLEĞİ
            SettingsCategoryHeader(title = "Çeviri Önbelleği", icon = Icons.Default.Translate)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Çevrilen sayfalar; yüklü kaynaklar (eklentiler ve Cihaz/Yerel) için cihazda saklanır ve uygulama yeniden açıldığında beklemeden gösterilir. Kayıtlar en fazla 24 saat tutulur; süresi dolanlar otomatik silinir. Tarayıcıdan (özel siteler) yapılan çeviriler saklanmaz.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (translationCacheStats.isEmpty()) {
                        Text("Çeviri önbelleği şu an boş.", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        translationCacheStats.forEach { stat ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stat.sourceName.ifBlank { "Bilinmeyen Sağlayıcı" },
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "${stat.pageCount} sayfa • ${formatCacheBytes(stat.totalBytes)}",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.clearTranslationCache(stat.sourceName)
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("${stat.sourceName.ifBlank { "Sağlayıcı" }} çeviri önbelleği temizlendi.")
                                        }
                                    }
                                ) {
                                    Text("Temizle", fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                viewModel.clearTranslationCache(null)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Tüm çeviri önbelleği temizlendi.")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Tümünü Temizle (${formatCacheBytes(translationCacheStats.sumOf { it.totalBytes })})",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 6. BİLDİRİM AYARLARI
            SettingsCategoryHeader(title = "Bildirim Ayarları", icon = Icons.Default.Notifications)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsToggleOption(
                        title = "Yeni Bölüm Bildirimleri",
                        description = "Kütüphanedeki serileri periyodik olarak arka planda kontrol eder.",
                        checked = notificationEnabled,
                        onCheckedChange = { coroutineScope.launch { viewModel.preferences.setNotificationNewChapter(it) } }
                    )
                }
            }

            // 7. HAKKINDA
            SettingsCategoryHeader(title = "Hakkında", icon = Icons.Default.Info)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Uygulama", color = Color.Gray, fontSize = 12.sp)
                        Text("BabelRead", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sürüm", color = Color.Gray, fontSize = 12.sp)
                        Text(BuildConfig.VERSION_NAME, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = { uriHandler.openUri("https://github.com/voidrunnerdev/BabelRead") },
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Kaynak Kodu (GitHub)", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Açık Kaynak Lisansları", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "• Tachiyomi/Mihon eklenti API'si — Apache License 2.0\n" +
                                    "• AndroidX & Jetpack Compose — Apache License 2.0\n" +
                                    "• OkHttp — Apache License 2.0\n" +
                                    "• Kotlinx Coroutines — Apache License 2.0\n" +
                                    "• Coil — Apache License 2.0\n" +
                                    "• ONNX Runtime — MIT License\n" +
                                    "• RT-DETR-v2 balon tespiti (PaddleDetection) — Apache License 2.0\n" +
                                    "• Google ML Kit (metin tanıma) — Google Şartları\n" +
                                    "• Google Çeviri / DeepL / Gemini — ilgili sağlayıcı şartları",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Bu uygulama hiçbir manga içeriği veya eklenti deposu barındırmaz; tüm içerik kullanıcının eklediği kaynaklardan alınır. BabelRead bağımsız bir açık kaynak projedir; Tachiyomi, Mihon, Keiyoushi veya içerik sağlayıcılarıyla bağlantılı değildir. Eklenen depoların ve içeriklerin kullanım sorumluluğu kullanıcıya aittir.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
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
fun SettingsCategoryHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun SettingsLanguageRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2C2C2C))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(18.dp)
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(description, color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun formatCacheBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1048576.0
    return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
