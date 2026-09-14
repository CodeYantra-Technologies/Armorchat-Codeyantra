package com.codeyantra.armorchat.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codeyantra.armorchat.R

@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 128.dp,
) {
    Image(
        painter = painterResource(id = R.drawable.armor_mark),
        contentDescription = stringResource(R.string.cd_brand_mark),
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
