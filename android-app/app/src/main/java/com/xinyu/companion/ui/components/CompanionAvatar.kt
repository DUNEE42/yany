package com.xinyu.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.xinyu.companion.domain.CompanionProfile
import java.io.File

@Composable
fun CompanionAvatar(
    name: String,
    avatarPath: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    prominent: Boolean = true,
) {
    val glyph = remember(name) {
        name.trim().firstOrNull()?.toString() ?: CompanionProfile.DEFAULT_NAME
    }
    val avatarFile = remember(avatarPath) {
        avatarPath?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile && it.length() > 0 }
    }
    val glyphSize: TextUnit = remember(size) {
        when {
            size >= 56.dp -> 24.sp
            size >= 40.dp -> 19.sp
            else -> 12.sp
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarFile != null) {
            SubcomposeAsyncImage(
                model = avatarFile,
                contentDescription = "${name.ifBlank { CompanionProfile.DEFAULT_NAME }}的头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    MonogramAvatar(glyph = glyph, fontSize = glyphSize, prominent = prominent)
                },
                error = {
                    MonogramAvatar(glyph = glyph, fontSize = glyphSize, prominent = prominent)
                },
            )
        } else {
            MonogramAvatar(glyph = glyph, fontSize = glyphSize, prominent = prominent)
        }
    }
}

@Composable
private fun MonogramAvatar(
    glyph: String,
    fontSize: TextUnit,
    prominent: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    if (prominent) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            colorScheme.primaryContainer,
                            colorScheme.primary,
                            colorScheme.secondary.copy(alpha = 0.85f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
            )
        }
    }
}
