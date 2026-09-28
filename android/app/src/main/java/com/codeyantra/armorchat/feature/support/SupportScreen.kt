package com.codeyantra.armorchat.feature.support

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codeyantra.armorchat.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.support_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.support_body),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.support_developer_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.support_developer_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    val url = stringResource(R.string.support_developer_url)
                    val actionLabel = stringResource(R.string.support_developer_action)
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            }.onFailure {
                                Toast.makeText(context, "No app found to open link", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Favorite,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(actionLabel)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.brand_byline),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
