package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.compose.AsyncImagePainter.State
import coil3.compose.rememberAsyncImagePainter
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.ehviewer.core.ui.component.CrystalCard
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.ui.tools.shouldCrop

@Composable
@NonRestartableComposable
fun requestOf(model: GalleryPreview) = with(LocalContext.current) {
    remember(model) { imageRequest(model) }
}

@Composable
fun EhPreviewCard(
    model: GalleryPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    var contentScale by remember(model) { mutableStateOf(ContentScale.Fit) }
    val request = requestOf(model)
    val painter = rememberAsyncImagePainter(
        model = request,
        transform = {
            if (it is State.Success && model is V2GalleryPreview) {
                with(model) {
                    it.copy(
                        painter = BitmapPainter(
                            (it.result.image as BitmapImage).bitmap.asImageBitmap(),
                            IntOffset(offsetX, 0),
                            IntSize(clipWidth - 1, clipHeight - 1),
                        ),
                    )
                }
            } else {
                it
            }
        },
        onState = {
            if (it is State.Success) {
                if (model is V2GalleryPreview) {
                    if (model.shouldCrop) {
                        contentScale = ContentScale.Crop
                    }
                } else {
                    if (it.result.image.shouldCrop) {
                        contentScale = ContentScale.Crop
                    }
                }
            }
        },
    )
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            ),
    ) {
        CrystalCard(
            onClick = onClick,
            onLongClick = onLongClick ?: {
                if (painter.state.value is State.Error) {
                    painter.restart()
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f), shape),
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
@NonRestartableComposable
fun EhPreviewItem(
    galleryPreview: GalleryPreview?,
    position: Int,
    onClick: () -> Unit,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(contentAlignment = Alignment.Center) {
        if (galleryPreview != null) {
            EhPreviewCard(
                model = galleryPreview,
                onClick = onClick,
                modifier = Modifier.aspectRatio(DEFAULT_RATIO),
                selected = selected,
                onLongClick = onLongClick,
            )
        } else {
            CrystalCard(
                onClick = onClick,
                onLongClick = onLongClick ?: onClick,
                modifier = Modifier.aspectRatio(DEFAULT_RATIO),
            ) {}
        }
    }
    Text(text = "${position + 1}")
}
