package com.example.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.CardEntity
import java.io.InputStream
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF as AndroidRectF
import android.graphics.Typeface as AndroidTypeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.media.ExifInterface
import android.graphics.Matrix

fun rotateImageIfRequired(context: android.content.Context, img: Bitmap, selectedImage: Uri): Bitmap {
    val input = context.contentResolver.openInputStream(selectedImage) ?: return img
    try {
        val ei = ExifInterface(input)
        val orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val rotationAngle = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (rotationAngle != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationAngle.toFloat())
            val rotatedBitmap = Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
            img.recycle()
            return rotatedBitmap
        }
    } catch (e: Exception) {
        android.util.Log.e("RotateImage", "Error rotating file", e)
    } finally {
        input.close()
    }
    return img
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: CardViewModel = viewModel()) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val cards by viewModel.cardsState.collectAsState()

    // Interactive Demo Card States
    var customName by remember { mutableStateOf("プロレベルのベーシスト 八幡海鈴") }
    var customSerial by remember { mutableStateOf("BD/W125-032 R") }
    var customTraits by remember { mutableStateOf("音樂, Ave Mujica") }
    
    // Choose dynamic card bitmap generated initially or dynamically
    var activeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Initializer sample card on startup
    LaunchedEffect(Unit) {
        activeBitmap = generateCardSampleBitmap(customName, customSerial, customTraits)
    }

    // Media picking handler
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    activeBitmap = bitmap
                    viewModel.scanCardImage(bitmap)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "無法加載所選圖片: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Camera capture state for holding the URI
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    // Camera capture launcher for full-resolution photos
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            val uri = photoUri
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        val rotatedBmp = rotateImageIfRequired(context, bitmap, uri)
                        activeBitmap = rotatedBmp
                        viewModel.scanCardImage(rotatedBmp)
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "讀取拍攝照片失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background // Light lavender background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Elegant Bold Typography Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "分析儀",
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "影像自動統計系統",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Header Action: circular analytics indicator
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEADDFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = "Analysis System Indicator",
                        tint = Color(0xFF21005D),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Stats Panel
            StatsSection(
                cards = cards,
                onExportExcel = {
                    viewModel.exportToExcel(context) { fileUri ->
                        if (fileUri != null) {
                            viewModel.triggerShareIntent(context, fileUri)
                        } else {
                            Toast.makeText(context, "導出 Excel 失敗，請確認資料。", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Scanning & Generator Center
                item {
                    ScannerPanel(
                        activeBitmap = activeBitmap,
                        isScanning = viewModel.isScanning,
                        scanError = viewModel.scanError,
                        lastScannedCard = viewModel.lastScannedCard,
                        customName = customName,
                        customSerial = customSerial,
                        customTraits = customTraits,
                        onCustomNameChange = { customName = it },
                        onCustomSerialChange = { customSerial = it },
                        onCustomTraitsChange = { customTraits = it },
                        onGenerateAndScan = {
                            val cardBmp = generateCardSampleBitmap(customName, customSerial, customTraits)
                            activeBitmap = cardBmp
                            viewModel.scanCardImage(cardBmp)
                        },
                        onTriggerGallery = { imagePickerLauncher.launch("image/*") },
                        onTriggerCamera = {
                            try {
                                val file = java.io.File.createTempFile("temp_card_", ".jpg", context.cacheDir)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                photoUri = uri
                                cameraLauncher.launch(uri)
                            } catch (e: Exception) {
                                Toast.makeText(context, "啟動相機失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDismissStates = { viewModel.dismissScanStates() },
                        isContinuousMode = viewModel.isContinuousMode,
                        onToggleContinuousMode = { viewModel.toggleContinuousMode() },
                        activeBackgroundScans = viewModel.activeBackgroundScans,
                        onRemoveScanTask = { viewModel.removeScanTask(it) }
                    )
                }

                // Database Inventory Section Header with filters
                item {
                    FilterSection(
                        searchQuery = viewModel.searchQuery,
                        onSearchChange = viewModel::onSearchQueryChanged,
                        sortOrder = viewModel.sortOrder,
                        onSortChange = viewModel::onSortOrderChanged,
                        onClearAll = {
                            viewModel.clearInventory()
                            Toast.makeText(context, "資料庫已清空", Toast.LENGTH_SHORT).show()
                        },
                        cardsCount = cards.size
                    )
                }

                // Saved Cards Items
                if (cards.isEmpty()) {
                    item {
                        EmptyWorkspace()
                    }
                } else {
                    items(cards, key = { it.id }) { card ->
                        CardInventoryRow(
                            card = card,
                            onIncrement = { viewModel.incrementCardQuantity(card) },
                            onDecrement = { viewModel.decrementCardQuantity(card) },
                            onDelete = { viewModel.deleteCard(card) }
                        )
                    }
                }

                // Contributions & Support (AI Studio credit section)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            color = Color(0xFFF8FAFC), // Slate 50 background
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Groups,
                                    contentDescription = "Contributors",
                                    tint = Color(0xFF6366F1), // Indigo 500
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = "特別貢獻與技術支持 (Contributors)",
                                        color = Color(0xFF1E293B), // Slate 800
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Powered & Co-created by Google AI Studio",
                                        color = Color(0xFF64748B), // Slate 500
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // Add standard bottom spacer
                item {
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }
    }
}

@Composable
fun StatsSection(cards: List<CardEntity>, onExportExcel: () -> Unit) {
    val totalUnique = cards.size
    val totalQuantity = cards.sumOf { it.quantity }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(170.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFD0BCFF) // Medium Purple theme card from tailwind design
        ),
        shape = RoundedCornerShape(24.dp), // rounded-3xl
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Label Row (當前總計張數, LIVE Badge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "當前總計張數",
                    color = Color(0xFF21005D),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                
                Surface(
                    color = Color.White.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = Color(0xFF21005D),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            // Big Count Row (60sp text size, tracking tight)
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = String.format("%,d", totalQuantity),
                    color = Color(0xFF21005D),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-2).sp,
                    modifier = Modifier
                        .testTag("total_cards_count")
                        .alignByBaseline()
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "張",
                    color = Color(0xFF21005D).copy(alpha = 0.7f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alignByBaseline()
                )
            }

            // Footer row with dynamic status badges & Export Button built-in
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Style dots row matching physical catalog look
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFACC15))) // Yellow indicator
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF4444))) // Red indicator
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "款式數: $totalUnique 款",
                        color = Color(0xFF21005D).copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("unique_cards_count")
                    )
                }

                // Deep Purple Export Excel Button
                Button(
                    onClick = onExportExcel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF21005D),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(50.dp), // Fully rounded pill button
                    modifier = Modifier
                        .height(36.dp)
                        .testTag("export_excel_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "匯出",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "匯出 Excel",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ScannerPanel(
    activeBitmap: Bitmap?,
    isScanning: Boolean,
    scanError: String?,
    lastScannedCard: CardEntity?,
    customName: String,
    customSerial: String,
    customTraits: String,
    onCustomNameChange: (String) -> Unit,
    onCustomSerialChange: (String) -> Unit,
    onCustomTraitsChange: (String) -> Unit,
    onGenerateAndScan: () -> Unit,
    onTriggerGallery: () -> Unit,
    onTriggerCamera: () -> Unit,
    onDismissStates: () -> Unit,
    isContinuousMode: Boolean,
    onToggleContinuousMode: () -> Unit,
    activeBackgroundScans: List<BackgroundScanTask>,
    onRemoveScanTask: (BackgroundScanTask) -> Unit
) {
    var showCustomDesignPanel by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = BorderStroke(1.dp, Color(0xFFCAC4D0)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Section Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "卡牌智慧識別核心",
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
                
                AssistChip(
                    onClick = { showCustomDesignPanel = !showCustomDesignPanel },
                    label = {
                        Text(
                            text = if (showCustomDesignPanel) "隱藏自訂卡牌" else "自訂測試卡牌",
                            fontSize = 11.sp,
                            color = Color(0xFF49454F),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (showCustomDesignPanel) Icons.Default.ExpandLess else Icons.Default.Palette,
                            contentDescription = "Palette",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF6750A4)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pro / Premium continuous batch mode unlock indicator & switcher
            Surface(
                color = Color(0xFFEEF2FF), // Indigo 50 background
                border = BorderStroke(1.dp, Color(0xFFC7D2FE)), // Indigo 200 border
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "快速連拍連續掃描",
                                color = Color(0xFF3730A3), // Indigo 800
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = Color(0xFFFACC15), // Gold golden badge
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "👑 VIP專享",
                                    color = Color(0xFF78350F),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                color = Color(0xFF22C55E),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "測試號免付費",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "無需停留在結果對話框！直接連續拍攝複數卡片，AI 將以多執行緒背景掃描並直接增添統計！",
                            color = Color(0xFF4338CA), // Indigo 700
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    
                    Switch(
                        checked = isContinuousMode,
                        onCheckedChange = { onToggleContinuousMode() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF4F46E5),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFFE2E8F0)
                        ),
                        modifier = Modifier.testTag("continuous_mode_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedVisibility(
                visible = showCustomDesignPanel,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "🎨 模擬真實卡牌色彩規則",
                        color = Color(0xFF1E293B),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "1. 紅底白字標記為卡牌『名稱』\n2. 黃底黑字標記為卡牌『序號與屬性』",
                        color = Color(0xFF475569),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    OutlinedTextField(
                        value = customName,
                        onValueChange = onCustomNameChange,
                        label = { Text("卡牌名稱 (紅底白字)", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFE53935),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF334155),
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .testTag("custom_name_input"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = customSerial,
                        onValueChange = onCustomSerialChange,
                        label = { Text("卡片序號 (黃底黑字)", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFACC15),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF334155),
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .testTag("custom_serial_input"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = customTraits,
                        onValueChange = onCustomTraitsChange,
                        label = { Text("特色屬性 (黃底黑字，以逗號區隔)", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFACC15),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF334155),
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("custom_traits_input"),
                        singleLine = true
                    )

                    Button(
                        onClick = onGenerateAndScan,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6750A4)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("generate_scan_button")
                    ) {
                        Icon(Icons.Default.AutoMode, contentDescription = "Simulate", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("套用色彩配置並送出 AI 辨識", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dynamic Image Preview Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (activeBitmap != null) {
                    Image(
                        bitmap = activeBitmap.asImageBitmap(),
                        contentDescription = "選取或動態產生的卡牌預覽",
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Photo,
                            contentDescription = "無預覽",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "尚未載入或產生卡片影像",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Scanning Progress Overlay
                if (isScanning && !isContinuousMode) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.75f))
                            .clickable(enabled = false) {},
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF21005D))
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                "AI 正在檢查色彩特徵及條碼...",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                            Text(
                                "判讀中：紅底白字 (名稱) ｜ 黃底黑字 (序號)",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scan trigger Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onTriggerGallery,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("gallery_import_button"),
                    border = BorderStroke(1.dp, Color(0xFF6750A4)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6750A4)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("相簿選取", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Button(
                    onClick = onTriggerCamera,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("camera_capture_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Camera", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("相機拍照", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // Continuous Background Tasks List (Renderer inside panel)
            if (isContinuousMode && activeBackgroundScans.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔄 背景連拍辨識佇列 (${activeBackgroundScans.size} 件)",
                            color = Color(0xFF0F172A),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                        
                        Text(
                            text = "清除已完成",
                            color = Color(0xFF4F46E5),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    // Remove success or failure tasks from queue
                                    activeBackgroundScans.filter { it.isSuccess != null }.forEach { onRemoveScanTask(it) }
                                }
                                .padding(4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        activeBackgroundScans.forEach { task ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = when (task.isSuccess) {
                                    true -> Color(0xFFE8F5E9)   // light green
                                    false -> Color(0xFFFFEBEE)  // light red
                                    else -> Color.White
                                },
                                border = BorderStroke(
                                    1.dp,
                                    when (task.isSuccess) {
                                        true -> Color(0xFFA5D6A7)
                                        false -> Color(0xFFEF9A9A)
                                        else -> Color(0xFFE2E8F0)
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Image(
                                            bitmap = task.bitmap.asImageBitmap(),
                                            contentDescription = "Thumbnail",
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Column {
                                            Text(
                                                text = task.namePlaceholder,
                                                color = Color(0xFF0F172A),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = task.status,
                                                color = when (task.isSuccess) {
                                                    true -> Color(0xFF2E7D32)
                                                    false -> Color(0xFFC62828)
                                                    else -> Color(0xFF64748B)
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (task.isSuccess == null) {
                                        CircularProgressIndicator(
                                            color = Color(0xFF4F46E5),
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear Task Row",
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { onRemoveScanTask(task) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Results Popup Alert after recognition completes (In standard mode only)
            if (!isContinuousMode && (lastScannedCard != null || scanError != null)) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (scanError != null) Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
                    border = BorderStroke(1.dp, if (scanError != null) Color(0xFFEF9A9A) else Color(0xFFA5D6A7))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (scanError != null) "❌ 掃描失敗" else "🎉 辨識統計完成",
                                fontWeight = FontWeight.ExtraBold,
                                color = if (scanError != null) Color(0xFFC62828) else Color(0xFF2E7D32),
                                fontSize = 14.sp
                            )
                            
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = Color(0xFF64748B),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onDismissStates() }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        if (scanError != null) {
                            Text(
                                text = scanError,
                                color = Color(0xFFC62828),
                                fontSize = 12.sp
                            )
                        } else if (lastScannedCard != null) {
                            Column {
                                Text(
                                    text = "已統計卡片資訊：",
                                    color = Color(0xFF475569),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Mirroring the red banner style
                                    Surface(
                                        color = Color(0xFFE53935),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.padding(end = 6.dp)
                                    ) {
                                        Text(
                                            text = "名稱",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = lastScannedCard.name,
                                        color = Color(0xFF0F172A),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Mirroring the yellow banner style
                                    Surface(
                                        color = Color(0xFFFFEB3B),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.padding(end = 6.dp)
                                    ) {
                                        Text(
                                            text = "序號",
                                            color = Color.Black,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = lastScannedCard.serialNumber,
                                        color = Color(0xFF0F172A),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (lastScannedCard.traits != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "⚡ 色彩屬性: ${lastScannedCard.traits}",
                                        color = Color(0xFF475569),
                                        fontSize = 11.sp
                                    )
                                }
                                
                                Divider(color = Color(0xFFE2E8F0), modifier = Modifier.padding(vertical = 8.dp))
                                
                                Text(
                                    text = "📊 目前累計持有數量: ${lastScannedCard.quantity} 張",
                                    color = Color(0xFF0F172A),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
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
fun FilterSection(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortOrder: String,
    onSortChange: (String) -> Unit,
    onClearAll: () -> Unit,
    cardsCount: Int
) {
    var expandedSort by remember { mutableStateOf(false) }
    var showConfirmClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "識別紀錄清單 ($cardsCount 款)",
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            if (cardsCount > 0) {
                Text(
                    text = "清空全部資料",
                    color = Color(0xFFE53935),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { showConfirmClear = true }
                        .padding(8.dp)
                        .testTag("clear_all_button")
                )
            }
        }

        // Search Input in beautiful light layout
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("搜尋卡牌名稱、序號、色彩屬性...", fontSize = 13.sp, color = Color(0xFF64748B)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF64748B), modifier = Modifier.size(18.dp)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6750A4),
                unfocusedBorderColor = Color(0xFFCAC4D0),
                focusedTextColor = Color(0xFF0F172A),
                unfocusedTextColor = Color(0xFF0F172A),
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_filter_input"),
            singleLine = true,
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Sorting Row with Custom Pill Chip Design
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    onClick = { expandedSort = true },
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort", tint = Color(0xFF49454F), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when (sortOrder) {
                                "date_desc" -> "排序：最近掃描為先"
                                "date_asc" -> "排序：掃描時間遞增"
                                "qty_desc" -> "排序：持有張數遞減"
                                "name" -> "排序：按名稱字母 A-Z"
                                "serial" -> "排序：按序號編碼"
                                else -> "排序學型"
                            },
                            color = Color(0xFF49454F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Dropdown", tint = Color(0xFF49454F), modifier = Modifier.size(16.dp))
                    }
                }

                DropdownMenu(
                    expanded = expandedSort,
                    onDismissRequest = { expandedSort = false },
                    modifier = Modifier.background(Color.White)
                ) {
                    DropdownMenuItem(
                        text = { Text("最近掃描為先", color = Color(0xFF0F172A), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                        onClick = { onSortChange("date_desc"); expandedSort = false }
                    )
                    DropdownMenuItem(
                        text = { Text("掃描時間由舊至新", color = Color(0xFF0F172A), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                        onClick = { onSortChange("date_asc"); expandedSort = false }
                    )
                    DropdownMenuItem(
                        text = { Text("持有數量多至少", color = Color(0xFF0F172A), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                        onClick = { onSortChange("qty_desc"); expandedSort = false }
                    )
                    DropdownMenuItem(
                        text = { Text("名稱優先 (A-Z)", color = Color(0xFF0F172A), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                        onClick = { onSortChange("name"); expandedSort = false }
                    )
                    DropdownMenuItem(
                        text = { Text("序號編碼排序", color = Color(0xFF0F172A), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                        onClick = { onSortChange("serial"); expandedSort = false }
                    )
                }
            }
        }

        // Delete Database Confirmation Alert Dialog
        if (showConfirmClear) {
            AlertDialog(
                onDismissRequest = { showConfirmClear = false },
                title = { Text("警告：清空全部資料？", color = Color(0xFF0F172A), fontWeight = FontWeight.ExtraBold) },
                text = { Text("此操作將永久清空所有已統計的卡牌庫存紀錄，此動作無法復原。", color = Color(0xFF334155)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onClearAll()
                            showConfirmClear = false
                        }
                    ) {
                        Text("確認清除全部", color = Color(0xFFE53935), fontWeight = FontWeight.ExtraBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmClear = false }) {
                        Text("取消", color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color.White
            )
        }
    }
}

@Composable
fun CardInventoryRow(
    card: CardEntity,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_item_${card.id}"),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = BorderStroke(1.dp, Color(0xFFCAC4D0)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // RED Background White Text for CARD NAME (紅色底白字為名稱) - Mirroring ws physical design
                Row(
                    modifier = Modifier.padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFE53935), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "名稱",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = card.name,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // YELLOW Background Black Text for SERIAL NUMBER (黃色底黑字為序號) - Mirroring ws physical design
                Row(
                    modifier = Modifier.padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFFACC15), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "序號",
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = card.serialNumber,
                        color = Color(0xFF334155),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                // Render Traits if present (黃底黑字色彩屬性)
                if (!card.traits.isNullOrEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        card.traits.split(",").forEach { trait ->
                            val cleanTrait = trait.trim()
                            if (cleanTrait.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFFFACC15).copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                        .border(BorderStroke(1.dp, Color(0xFFFACC15).copy(alpha = 0.6f)), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = cleanTrait,
                                        color = Color(0xFF854D0E), // high contrast dark golden brown
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quantity adjusters with a light theme gray background capsule
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .background(Color(0xFFF1F5F9), RoundedCornerShape(20.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                IconButton(
                    onClick = onDecrement,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("decrement_qty_${card.id}")
                ) {
                    Icon(
                        imageVector = if (card.quantity > 1) Icons.Default.Remove else Icons.Default.Delete,
                        contentDescription = "減少",
                        tint = if (card.quantity > 1) Color(0xFF475569) else Color(0xFFE53935),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Text(
                    text = card.quantity.toString(),
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .testTag("qty_val_${card.id}")
                )

                IconButton(
                    onClick = onIncrement,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("increment_qty_${card.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "增加",
                        tint = Color(0xFF0F172A),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyWorkspace() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Inventory2,
            contentDescription = "Empty",
            tint = Color(0xFF94A3B8),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "庫存內尚無卡牌紀錄",
            color = Color(0xFF475569),
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp
        )
        Text(
            text = "請利用上方相機、相簿或自訂卡牌點擊辨識\n卡片將自動統計張數並保存於此！",
            color = Color(0xFF64748B),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// Dynamically draws card with "Red background white text" for Name, and "Yellow background black text" for Serial number
fun generateCardSampleBitmap(
    name: String,
    serialNumber: String,
    traits: String
): Bitmap {
    val width = 450
    val height = 630
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    
    // Fill dynamic card base background
    val bgPaint = AndroidPaint().apply {
        color = AndroidColor.parseColor("#1C1E24")
        style = AndroidPaint.Style.FILL
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    
    // Draw card border
    val borderPaint = AndroidPaint().apply {
        color = AndroidColor.parseColor("#3498DB") // nice blue outline
        strokeWidth = 10f
        style = AndroidPaint.Style.STROKE
    }
    canvas.drawRect(5f, 5f, width.toFloat() - 5f, height.toFloat() - 5f, borderPaint)
    
    // Character art placeholder shadow circle
    val circlePaint = AndroidPaint().apply {
        color = AndroidColor.parseColor("#2C3E50")
        style = AndroidPaint.Style.FILL
    }
    canvas.drawCircle(width / 2f, height / 2.5f, 130f, circlePaint)

    // Character label
    val labelPaint = AndroidPaint().apply {
        color = AndroidColor.WHITE
        textSize = 24f
        isAntiAlias = true
        typeface = AndroidTypeface.create(AndroidTypeface.DEFAULT, AndroidTypeface.BOLD)
        textAlign = AndroidPaint.Align.CENTER
    }
    canvas.drawText("【 WEISS SCHWARZ 】", width / 2f, height / 2.5f - 20f, labelPaint)
    
    val subtitlePaint = AndroidPaint().apply {
        color = AndroidColor.parseColor("#BDC3C7")
        textSize = 18f
        isAntiAlias = true
        textAlign = AndroidPaint.Align.CENTER
    }
    canvas.drawText("CHARACTER GRAPHIC", width / 2f, height / 2.5f + 25f, subtitlePaint)
    
    // --- 1. NAME LABEL: 紅色底白字 (Red background, white text) ---
    val redPaint = AndroidPaint().apply {
        color = AndroidColor.parseColor("#E53935") // Red
        style = AndroidPaint.Style.FILL
    }
    // Name banner position
    val nameRect = AndroidRectF(25f, height - 210f, width - 25f, height - 150f)
    canvas.drawRoundRect(nameRect, 8f, 8f, redPaint)
    
    val nameTextPaint = AndroidPaint().apply {
        color = AndroidColor.WHITE
        textSize = 22f
        isAntiAlias = true
        typeface = AndroidTypeface.create(AndroidTypeface.DEFAULT, AndroidTypeface.BOLD)
    }
    canvas.drawText(name, 45f, height - 172f, nameTextPaint)
    
    // --- 2. SERIAL LABEL: 黃色底黑字 (Yellow background, black text) ---
    val yellowPaint = AndroidPaint().apply {
        color = AndroidColor.parseColor("#FFEB3B") // Yellow
        style = AndroidPaint.Style.FILL
    }
    // Serial banner position
    val serialRect = AndroidRectF(25f, height - 140f, width - 25f, height - 95f)
    canvas.drawRoundRect(serialRect, 8f, 8f, yellowPaint)
    
    val blackTextPaint = AndroidPaint().apply {
        color = AndroidColor.BLACK
        textSize = 20f
        isAntiAlias = true
        typeface = AndroidTypeface.create(AndroidTypeface.DEFAULT, AndroidTypeface.BOLD)
    }
    canvas.drawText("ID: $serialNumber", 45f, height - 113f, blackTextPaint)
    
    // Traits capsules below serial using same elements
    if (traits.isNotEmpty()) {
        val traitList = traits.split(",").map { it.trim() }
        var startX = 25f
        
        traitList.forEach { trait ->
            if (trait.isNotEmpty()) {
                val textLen = trait.length
                val capsuleWidth = textLen * 24f + 30f
                val traitRect = AndroidRectF(startX, height - 85f, startX + capsuleWidth, height - 45f)
                canvas.drawRoundRect(traitRect, 18f, 18f, yellowPaint)
                canvas.drawText(trait, startX + 15f, height - 58f, blackTextPaint)
                startX += capsuleWidth + 12f
            }
        }
    }
    
    // Footer watermark
    val footerPaint = AndroidPaint().apply {
        color = AndroidColor.DKGRAY
        textSize = 14f
        isAntiAlias = true
    }
    canvas.drawText("Card Emulator Dynamic Renderer v1.0", 30f, height - 15f, footerPaint)
    
    return bitmap
}
