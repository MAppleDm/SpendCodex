package dev.spendcodex.ui.operation

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.Hint
import dev.spendcodex.coreui.SectionCaption
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ReceiptLimits.PER_OPERATION), vm::onPhotosPicked)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), vm::onCameraResult)
    val enabled = !form.photoBusy && !form.busy && form.photos.size < ReceiptLimits.PER_OPERATION
    SectionCaption(Texts.RECEIPTS + if (form.photos.isNotEmpty()) " · ${form.photos.size}" else "")
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextAction(Texts.RECEIPT_CAMERA, {
            vm.cameraTarget()?.let { uri ->
                try { camera.launch(uri) }
                catch (_: ActivityNotFoundException) { vm.onCameraResult(false); vm.photoError(Texts.RECEIPT_CAMERA_FAILED) }
                catch (_: SecurityException) { vm.onCameraResult(false); vm.photoError(Texts.RECEIPT_CAMERA_FAILED) }
            }
        }, Modifier.testTag("receipt-camera"), enabled = enabled)
        TextAction(Texts.RECEIPT_GALLERY, {
            try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            catch (_: ActivityNotFoundException) { vm.photoError(Texts.RECEIPT_FAILED) }
            catch (_: SecurityException) { vm.photoError(Texts.RECEIPT_FAILED) }
        }, Modifier.testTag("receipt-gallery"), enabled = enabled)
    }
    if (form.photos.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            form.photos.forEachIndexed { index, photo ->
                val bitmap by photoBitmap(photo, vm, 256)
                Box(Modifier.size(88.dp, 112.dp).background(AppTheme.colors.pressed).tap { viewing = photo }.testTag("receipt-${photo.receipt.id}"), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it, Texts.receiptPhoto(index + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: Text("…", style = AppTheme.type.caption)
                }
            }
        }
    }
    Hint(if (form.photoBusy) Texts.RECEIPT_PROCESSING else Texts.RECEIPT_HINT, Modifier.testTag("receipt-hint"))
    if (form.photos.size >= ReceiptLimits.PER_OPERATION) Hint(Texts.RECEIPT_LIMIT)
    form.removedPhoto?.let {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint(Texts.RECEIPT_REMOVED, Modifier.weight(1f))
            TextAction(Texts.UNDO, vm::onUndoRemovePhoto, Modifier.testTag("receipt-undo"), enabled = !form.busy && !form.photoBusy && form.photos.size < ReceiptLimits.PER_OPERATION)
        }
    }
    form.photoMessage?.let { Hint(it, Modifier.testTag("receipt-error"), color = AppTheme.colors.error) }
    viewing?.let { photo ->
        ReceiptViewer(photo, vm, onClose = { viewing = null }, onRemove = { vm.onRemovePhoto(photo.receipt.id); viewing = null }, canRemove = !form.busy && !form.photoBusy)
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
