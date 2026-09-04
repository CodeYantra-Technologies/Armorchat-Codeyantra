package com.codeyantra.armorchat.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.ui.components.BrandMark

@Composable
fun WelcomeScreen(
    onCreateIdentity: () -> Unit,
    onRestoreIdentity: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            BrandMark(size = 88.dp, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.welcome_line_one),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.onBackground,
            )
            Text(
                text = stringResource(R.string.welcome_line_two),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.onBackground,
            )
            Text(
                text = stringResource(R.string.welcome_line_three),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCreateIdentity,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.onBackground,
                        contentColor = colors.background,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.action_create_identity),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                OutlinedButton(
                    onClick = onRestoreIdentity,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.action_restore_identity),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.brand_secured),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
