package dev.spendcodex.ui.operation

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.CameraIcon
import dev.spendcodex.coreui.EditIcon
import dev.spendcodex.coreui.GalleryIcon
import dev.spendcodex.coreui.Hint
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.coreui.tap
import dev.spendcodex.data.receipts.ReceiptImages
import dev.spendcodex.data.receipts.ReceiptLimits
import dev.spendcodex.data.receipts.ReceiptPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReceiptsBlock(form: FormState, vm: OperationViewModel) {
    var viewing by remember(form.key) { mutableStateOf<ReceiptPhoto?>(null) }
    var choosingSource by rememberSaveable(form.key) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ReceiptLimits.PER_OPERATION), vm::onPhotosPicked)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), vm::onCameraResult)
    val enabled = !form.photoBusy && !form.busy && form.photos.size < ReceiptLimits.PER_OPERATION
    val cover = form.photos.firstOrNull()?.let { photoBitmap(it, vm, 256).value }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Texts.RECEIPTS + if (form.photos.isNotEmpty()) " · ${form.photos.size}" else "",
            style = AppTheme.type.body, modifier = Modifier.weight(1f),
        )
        ReceiptAttachButton(cover, enabled) {
            focus.clearFocus()
            choosingSource = true
        }
    }
    if (form.photos.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            form.photos.forEachIndexed { index, photo ->
                val bitmap = if (index == 0) cover else photoBitmap(photo, vm, 256).value
                Box(Modifier.size(88.dp, 112.dp).clip(RoundedCornerShape(14.dp)).background(AppTheme.colors.pressed)
                    .tap { viewing = photo }.testTag("receipt-${photo.receipt.id}"), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it, Texts.receiptPhoto(index + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: Text("…", style = AppTheme.type.caption)
                }
            }
        }
    }
    if (form.photoBusy) Hint(Texts.RECEIPT_PROCESSING, Modifier.testTag("receipt-hint"))
    if (form.photos.size >= ReceiptLimits.PER_OPERATION) Hint(Texts.RECEIPT_LIMIT)
    form.removedPhoto?.let {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint(Texts.RECEIPT_REMOVED, Modifier.weight(1f))
            TextAction(Texts.UNDO, vm::onUndoRemovePhoto, Modifier.testTag("receipt-undo"), enabled = !form.busy && !form.photoBusy && form.photos.size < ReceiptLimits.PER_OPERATION)
        }
    }
    form.photoMessage?.let { Hint(it, Modifier.testTag("receipt-error"), color = AppTheme.colors.error) }
    if (choosingSource && enabled) {
        ReceiptSourceSheet(
            onDismiss = { choosingSource = false },
            onCamera = {
                choosingSource = false
                vm.cameraTarget()?.let { uri ->
                    try { camera.launch(uri) }
                    catch (_: ActivityNotFoundException) { vm.onCameraResult(false); vm.photoError(Texts.RECEIPT_CAMERA_FAILED) }
                    catch (_: SecurityException) { vm.onCameraResult(false); vm.photoError(Texts.RECEIPT_CAMERA_FAILED) }
                }
            },
            onGallery = {
                choosingSource = false
                try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (_: ActivityNotFoundException) { vm.photoError(Texts.RECEIPT_FAILED) }
                catch (_: SecurityException) { vm.photoError(Texts.RECEIPT_FAILED) }
            },
        )
    }
    viewing?.let { photo ->
        ReceiptViewer(photo, vm, onClose = { viewing = null }, onRemove = { vm.onRemovePhoto(photo.receipt.id); viewing = null }, canRemove = !form.busy && !form.photoBusy)
    }
}

@Composable
private fun ReceiptAttachButton(cover: ImageBitmap?, enabled: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val label = Texts.RECEIPT_ATTACH
    Box(
        Modifier.size(54.dp).clip(shape).background(c.pressed)
            .border(1.dp, Brush.linearGradient(listOf(c.background.copy(alpha = 0.85f), c.border)), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.testTag("receipt-attach"),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(cover, null, Modifier.matchParentSize().blur(10.dp), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.matchParentSize().blur(8.dp).background(Brush.linearGradient(
                listOf(c.background, lerp(c.pressed, c.accent, 0.2f)),
            )))
        }
        Box(Modifier.matchParentSize().background(c.background.copy(alpha = if (cover == null) 0.24f else 0.78f)))
        EditIcon(if (enabled) c.foreground else c.tertiary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptSourceSheet(onDismiss: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    val c = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("receipt-source-sheet"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = c.background,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Text(Texts.RECEIPT_ATTACH, style = AppTheme.type.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReceiptSourceOption(Texts.RECEIPT_CAMERA, onCamera, Modifier.weight(1f).testTag("receipt-camera")) { CameraIcon(it) }
                ReceiptSourceOption(Texts.RECEIPT_GALLERY, onGallery, Modifier.weight(1f).testTag("receipt-gallery")) { GalleryIcon(it) }
            }
        }
    }
}

@Composable
private fun ReceiptSourceOption(label: String, onClick: () -> Unit, modifier: Modifier, icon: @Composable (Color) -> Unit) {
    val c = AppTheme.colors
    Column(
        modifier.heightIn(min = 88.dp).clip(RoundedCornerShape(16.dp)).background(c.pressed)
            .clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon(c.foreground)
        Text(label, style = AppTheme.type.secondary.copy(color = c.foreground))
    }
}

@Composable
private fun photoBitmap(photo: ReceiptPhoto, vm: OperationViewModel, maxSide: Int): State<ImageBitmap?> = produceState<ImageBitmap?>(null, photo.receipt.id, maxSide) {
    value = withContext(Dispatchers.IO) {
        try { vm.photoBytes(photo)?.let { ReceiptImages.thumbnail(it, maxSide)?.asImageBitmap() } }
        catch (_: Exception) { null }
        catch (_: OutOfMemoryError) { null }
    }
}

@Composable
private fun ReceiptViewer(photo: ReceiptPhoto, vm: OperationViewModel, onClose: () -> Unit, onRemove: () -> Unit, canRemove: Boolean) {
    val bitmap by photoBitmap(photo, vm, ReceiptLimits.MAX_LONG_SIDE)
    var scale by remember(photo.receipt.id) { mutableFloatStateOf(1f) }
    var offset by remember(photo.receipt.id) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(AppTheme.colors.background).systemBarsPadding().testTag("receipt-viewer")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction(Texts.AI_CLOSE, onClose, Modifier.testTag("receipt-close"))
                Spacer(Modifier.weight(1f))
                TextAction(Texts.DELETE, onRemove, Modifier.testTag("receipt-remove"), color = AppTheme.colors.error, enabled = canRemove)
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().transformable(transform)
                .pointerInput(photo.receipt.id) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 3f; offset = Offset.Zero }) }, contentAlignment = Alignment.Center) {
                bitmap?.let { image ->
                    Image(image, Texts.RECEIPTS, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    }, contentScale = ContentScale.Fit)
                } ?: Hint(Texts.RECEIPT_UNAVAILABLE)
            }
            Hint(Texts.RECEIPT_ZOOM)
            Hint("${photo.receipt.width} × ${photo.receipt.height} · ${photo.receipt.size / 1024} KB")
        }
    }
}
