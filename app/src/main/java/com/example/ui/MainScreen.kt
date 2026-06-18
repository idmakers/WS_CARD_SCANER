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
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import kotlinx.coroutines.delay
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
    
    // In-app camera and continuous auto-simulation states
    var isCustomCameraOpen by remember { mutableStateOf(false) }
    var isSimAutoPlaying by remember { mutableStateOf(false) }
    var simSerialIndex by remember { mutableStateOf(1) }
    
    // Initializer sample card on startup
    LaunchedEffect(Unit) {
        activeBitmap = generateCardSampleBitmap(customName, customSerial, customTraits)
    }

    // Auto-continuous simulation scanning loop (Runs directly on browser emulator/development env)
    LaunchedEffect(isSimAutoPlaying) {
        if (isSimAutoPlaying) {
            while (isSimAutoPlaying) {
                if (!viewModel.isTester && viewModel.remainingScans <= 0) {
                    isSimAutoPlaying = false
                    Toast.makeText(context, "餘額不足，模擬連拍已停止！", Toast.LENGTH_LONG).show()
                    break
                }
                
                val nameWithSuffix = if (customName.isBlank()) "超級噴火龍" else "$customName #$simSerialIndex"
                val serialWithSuffix = if (customSerial.isBlank()) "001-025" else "$customSerial-S$simSerialIndex"
                val traitsWithSuffix = if (customTraits.isBlank()) "火屬性, 一擊" else customTraits
                
                val cardBmp = generateCardSampleBitmap(nameWithSuffix, serialWithSuffix, traitsWithSuffix)
                activeBitmap = cardBmp
                viewModel.scanCardImage(cardBmp, forceContinuous = true) // Automatically processes continuous task, cuts remaining counts
                
                simSerialIndex += 1
                delay(3000) // Trigger next scan every 3 seconds
            }
        }
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

    if (isCustomCameraOpen) {
        InAppCameraDialog(
            remainingScans = viewModel.remainingScans,
            isTester = viewModel.isTester,
            onPhotoCaptured = { bitmap ->
                activeBitmap = bitmap
                viewModel.scanCardImage(bitmap, forceContinuous = true) // Automatically streams to continuous processing in background
            },
            onClose = {
                isCustomCameraOpen = false
            }
        )
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
                            isCustomCameraOpen = true
                        },
                        onDismissStates = { viewModel.dismissScanStates() },
                        isContinuousMode = viewModel.isContinuousMode,
                        onToggleContinuousMode = { viewModel.toggleContinuousMode() },
                        activeBackgroundScans = viewModel.activeBackgroundScans,
                        onRemoveScanTask = { viewModel.removeScanTask(it) },
                        remainingScans = viewModel.remainingScans,
                        isTester = viewModel.isTester,
                        isSimAutoPlaying = isSimAutoPlaying,
                        onToggleSimAutoPlay = { isSimAutoPlaying = !isSimAutoPlaying }
                    )
                }

                // Billing / Membership / Token Shop Center
                item {
                    BillingStorePanel(
                        remainingScans = viewModel.remainingScans,
                        subscriptionLevel = viewModel.subscriptionLevel,
                        purchasedTokenCount = viewModel.purchasedTokenCount,
                        userEmail = viewModel.userEmail,
                        isTester = viewModel.isTester,
                        onUpdateEmail = { viewModel.updateUserEmail(it) },
                        onBuyToken = { viewModel.buyTokenPackage() },
                        onSubscribeTier1 = { viewModel.subscribeTier1() },
                        onSubscribeTier2 = { viewModel.subscribeTier2() },
                        onResetBilling = { viewModel.cancelSubscriptionAndReset() }
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
    onRemoveScanTask: (BackgroundScanTask) -> Unit,
    remainingScans: Int,
    isTester: Boolean = false,
    isSimAutoPlaying: Boolean = false,
    onToggleSimAutoPlay: () -> Unit = {}
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

            // Elegant small status row of scanning credits
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.OfflineBolt,
                        contentDescription = "Fast continuous scanning enabled",
                        tint = Color(0xFF6366F1),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "極速連拍 / 模擬連拍：已整合就緒",
                        color = Color(0xFF4338CA),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = if (isTester) Color(0xFF8B5CF6) else (if (remainingScans > 0) Color(0xFF10B981) else Color(0xFFEF4444)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (isTester) "⚡ 開發帳號：無限次" else "🔋 剩餘辨識額度: $remainingScans 次",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onGenerateAndScan,
                            enabled = !isSimAutoPlaying,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6750A4)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("generate_scan_button")
                        ) {
                            Icon(Icons.Default.AutoMode, contentDescription = "Simulate", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("單次模擬辨識", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                        }

                        Button(
                            onClick = onToggleSimAutoPlay,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSimAutoPlaying) Color(0xFFEF4444) else Color(0xFF10B981)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("toggle_sim_auto_button")
                        ) {
                            Icon(
                                imageVector = if (isSimAutoPlaying) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                                contentDescription = "Sim Auto Play",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isSimAutoPlaying) "停止自動模擬" else "⚡ 啟動模擬連拍",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
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
                if (isScanning) {
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
            if (activeBackgroundScans.isNotEmpty()) {
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
            if (lastScannedCard != null || scanError != null) {
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
fun BillingStorePanel(
    remainingScans: Int,
    subscriptionLevel: Int,
    purchasedTokenCount: Int,
    userEmail: String,
    isTester: Boolean,
    onUpdateEmail: (String) -> Unit,
    onBuyToken: () -> Unit,
    onSubscribeTier1: () -> Unit,
    onSubscribeTier2: () -> Unit,
    onResetBilling: () -> Unit
) {
    val context = LocalContext.current
    var emailInput by remember(userEmail) { mutableStateOf(userEmail) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("billing_store_panel"),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Wallet,
                    contentDescription = "Wallet Icon",
                    tint = Color(0xFF6366F1), // Indigo
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "會員商城與額度儲值",
                    color = Color(0xFF1E293B),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    letterSpacing = (-0.3).sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // User Email Account Binder (Dynamic Test Verification input)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = "綁定會員/測試帳號電子信箱：",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { newValue ->
                            emailInput = newValue
                            onUpdateEmail(newValue)
                        },
                        placeholder = { Text("請輸入電子郵件...", fontSize = 12.sp, color = Color.Gray) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF1E293B),
                            unfocusedTextColor = Color(0xFF1E293B),
                            focusedContainerColor = Color(0xFFF8FAFC),
                            unfocusedContainerColor = Color(0xFFF8FAFC),
                            focusedBorderColor = Color(0xFF6366F1),
                            unfocusedBorderColor = Color(0xFFE2E8F0)
                        )
                    )
                    
                    if (emailInput.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onUpdateEmail(emailInput)
                                if (isTester) {
                                    Toast.makeText(context, "🎉 測試帳號 idmakers@gmail.com 啟用！無限連拍已解鎖！", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "帳號已綁定！", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isTester) Color(0xFF8B5CF6) else Color(0xFF6366F1)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(
                                text = if (isTester) "⚡ 開發認證" else "儲存帳號",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // Show tester dynamic success status indicator
                if (isTester) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = Color(0xFFF5F3FF),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color(0xFFDDD6FE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Active Tester",
                                tint = Color(0xFF8B5CF6),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "開發者測試帳號驗證成功！享無限連拍免扣額保護 (免點數免金流)",
                                color = Color(0xFF6D28D9),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Current Status Summary Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = if (isTester) {
                                listOf(Color(0xFFF5F3FF), Color(0xFFEDE9FE)) // Light violet gradient for developers
                            } else {
                                listOf(Color(0xFFF1F5F9), Color(0xFFE2E8F0))
                            }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "當前帳戶狀態",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                text = when {
                                    isTester -> "⚡ 專屬開發者測試帳戶"
                                    subscriptionLevel == 1 -> "⭐ 第一級付費會員"
                                    subscriptionLevel == 2 -> "👑 第二級鑽石會員"
                                    else -> "👤 一般試用帳號"
                                },
                                color = if (isTester) Color(0xFF6D28D9) else Color(0xFF0F172A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (!isTester && purchasedTokenCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = Color(0xFFEEF2FF),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "已購代幣 ×$purchasedTokenCount",
                                        color = Color(0xFF4F46E5),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "剩餘連拍量",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isTester) "無限次數" else "$remainingScans 次",
                            color = if (isTester) Color(0xFF8B5CF6) else (if (remainingScans > 0) Color(0xFF10B981) else Color(0xFFEF4444)),
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "請選擇儲值或訂閱方案 (免綁卡一鍵模擬支付)：",
                color = Color(0xFF475569),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            // Dynamic Billing Grid Options
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Token Package: NT$30 -> 100 scans
                BillingStoreItem(
                    title = "代幣儲值包",
                    desc = "單次增量（最適合輕度與短時使用）",
                    quotaText = "增加 100 次連續連拍",
                    priceText = "NT$ 30 元",
                    borderColor = Color(0xFFE2E8F0),
                    buttonColor = Color(0xFF475569),
                    badgeText = "💰 最彈性",
                    onClick = {
                        onBuyToken()
                    }
                )

                // Tier 1 Membership: NT$99/month -> 1000 scans
                BillingStoreItem(
                    title = "【第一級】付費大師會員",
                    desc = "月費制（狂熱玩家首選，全面提升效率）",
                    quotaText = "立即獲得 1,000 次連拍額度",
                    priceText = "NT$ 99 元 /月",
                    borderColor = Color(0xFFC7D2FE),
                    buttonColor = Color(0xFF4F46E5),
                    badgeText = "⭐ 第一級付費",
                    isSubscribed = subscriptionLevel == 1,
                    onClick = {
                        onSubscribeTier1()
                    }
                )

                // Tier 2 Membership: NT$199/month -> 10000 scans
                BillingStoreItem(
                    title = "【第二級】卡牌鑽石豪客",
                    desc = "最高規（統計工作室、全能實卡收藏家必備）",
                    quotaText = "立即注入 10,000 次連拍額度",
                    priceText = "NT$ 199 元 /月",
                    borderColor = Color(0xFFFDE047),
                    buttonColor = Color(0xFFCA8A04),
                    badgeText = "👑 卡牌鑽石",
                    isSubscribed = subscriptionLevel == 2,
                    onClick = {
                        onSubscribeTier2()
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Reset and restore demo action button
            TextButton(
                onClick = onResetBilling,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF94A3B8)),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset State",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "重置為全新 50 次試用帳戶",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun BillingStoreItem(
    title: String,
    desc: String,
    quotaText: String,
    priceText: String,
    borderColor: Color,
    buttonColor: Color,
    badgeText: String,
    isSubscribed: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (isSubscribed) 2.dp else 1.dp, if (isSubscribed) buttonColor else borderColor),
        color = if (isSubscribed) buttonColor.copy(alpha = 0.03f) else Color.White
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = buttonColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = buttonColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = desc,
                    color = Color(0xFF64748B),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                Text(
                    text = quotaText,
                    color = buttonColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = priceText,
                    color = Color(0xFF1E293B),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSubscribed) Color(0xFF10B981) else buttonColor,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isSubscribed) Icons.Default.Check else Icons.Default.ShoppingCart,
                            contentDescription = "Buy",
                            modifier = Modifier.size(12.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isSubscribed) "已訂閱" else "立即模擬製單",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun InAppCameraDialog(
    remainingScans: Int,
    isTester: Boolean,
    onPhotoCaptured: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    
    // Permission Handling
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    
    // Shutter capture engine
    val imageCapture = remember { ImageCapture.Builder().build() }
    
    // Auto shutter timing control
    var isAutoShutterActive by remember { mutableStateOf(false) }
    var secondsToNextAutoShutter by remember { mutableStateOf(3) }
    
    // Screen Flash effect
    var triggerFlash by remember { mutableStateOf(false) }
    
    // Auto Capture thread loop
    LaunchedEffect(isAutoShutterActive) {
        if (isAutoShutterActive) {
            while (isAutoShutterActive) {
                if (!isTester && remainingScans <= 0) {
                    isAutoShutterActive = false
                    Toast.makeText(context, "額度不足，自動拍照已停止！", Toast.LENGTH_LONG).show()
                    break
                }
                
                secondsToNextAutoShutter = 3
                while (secondsToNextAutoShutter > 0 && isAutoShutterActive) {
                    delay(1000)
                    secondsToNextAutoShutter -= 1
                }
                
                if (isAutoShutterActive) {
                    // Flash feedback
                    triggerFlash = true
                    
                    // Actually take picture
                    try {
                        val file = java.io.File.createTempFile("camera_raw_", ".jpg", context.cacheDir)
                        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                        imageCapture.takePicture(
                            outputFileOptions,
                            androidx.core.content.ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    try {
                                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                                        if (bitmap != null) {
                                            val rotated = rotateImageIfRequired(context, bitmap, Uri.fromFile(file))
                                            onPhotoCaptured(rotated)
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("Camera", "Failed to decode background picture", e)
                                    }
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    android.util.Log.e("Camera", "Failed capture", exception)
                                }
                            }
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("Camera", "Failed output", e)
                    }
                    
                    delay(500) // minor rest
                    triggerFlash = false
                }
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (cameraPermissionState.status.isGranted) {
                    // Live Viewfinder
                    CameraPreviewView(
                        imageCapture = imageCapture,
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Flash White Overlay on trigger
                    if (triggerFlash) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.8f))
                        )
                    }
                    
                    // Camera HUD Controls overlay
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Header bar with Info & Close
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FlashOn,
                                        contentDescription = "Active info",
                                        tint = Color.Yellow,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isTester) "⚡ 開發認證：無限連拍中" else "🔋 剩餘次數: $remainingScans 次",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Camera",
                                    tint = Color.White
                                )
                            }
                        }

                        // Dynamic auto capture countdown indicator
                        if (isAutoShutterActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .background(Color(0xFF8B5CF6).copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        progress = { secondsToNextAutoShutter.toFloat() / 3f },
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "⏱️ $secondsToNextAutoShutter 秒後自動連拍拍照...",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }

                        // Footer HUD dashboard
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Black.copy(alpha = 0.75f)
                            ),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(bottom = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "指向卡片以極速批次辨識 (不退回主畫面直接快拍)",
                                    color = Color.LightGray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Auto capture switch toggle
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
                                            isAutoShutterActive = !isAutoShutterActive
                                        }
                                    ) {
                                        Switch(
                                            checked = isAutoShutterActive,
                                            onCheckedChange = { isAutoShutterActive = it },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.White,
                                                checkedTrackColor = Color(0xFF10B981)
                                            ),
                                            modifier = Modifier.scale(0.85f)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isAutoShutterActive) "⚡ 自動連拍：開" else "⏱️ 自動連拍：關",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // Large Capture Shutter Button
                                    FloatingActionButton(
                                        onClick = {
                                            if (!isTester && remainingScans <= 0) {
                                                Toast.makeText(context, "額度已用完，無法拍照！", Toast.LENGTH_SHORT).show()
                                            } else {
                                                triggerFlash = true
                                                try {
                                                    val file = java.io.File.createTempFile("cam_manual_", ".jpg", context.cacheDir)
                                                    val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                                                    imageCapture.takePicture(
                                                        outputFileOptions,
                                                        androidx.core.content.ContextCompat.getMainExecutor(context),
                                                        object : ImageCapture.OnImageSavedCallback {
                                                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                                try {
                                                                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                                                                    if (bitmap != null) {
                                                                        val rotated = rotateImageIfRequired(context, bitmap, Uri.fromFile(file))
                                                                        onPhotoCaptured(rotated)
                                                                        Toast.makeText(context, "已新增至掃描佇列！", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                } catch (e: Exception) {
                                                                    Toast.makeText(context, "讀取影像異常", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                            override fun onError(exception: ImageCaptureException) {
                                                                Toast.makeText(context, "拍照失敗: ${exception.message}", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    )
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "拍照建立檔案失敗", Toast.LENGTH_SHORT).show()
                                                }
                                                
                                                // Turn off flash after 250ms
                                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                    triggerFlash = false
                                                }, 250)
                                            }
                                        },
                                        containerColor = Color.White,
                                        contentColor = Color.Black,
                                        shape = CircleShape,
                                        modifier = Modifier.size(54.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = "Manual Shoot",
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }

                                    // Exit Confirmation Button
                                    Button(
                                        onClick = onClose,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFEF4444)
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("完成/關閉", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Request Permission Screen
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Camera Permission Required",
                            tint = Color.LightGray,
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "需要啟用相機鏡頭權限",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "卡牌極速連續連拍模組需要使用直接相機取景預覽。這樣您就可以手持卡牌，在不關閉或跳出相機的情況下進行不限張數的連續快速連掃。",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { cameraPermissionState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6366F1)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = "Authorize")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("立即對本 APP 進行相機授權", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = onClose) {
                            Text("暫時取消並返回", color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewView(
    imageCapture: ImageCapture,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                }
            } catch (e: Exception) {
                android.util.Log.e("CameraPreviewView", "Error unbinding on dispose", e)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (exc: Exception) {
                    android.util.Log.e("CameraPreviewView", "Use case binding failed", exc)
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = modifier
    )
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
