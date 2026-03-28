package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun ApplicationsSection(
    onNavigateToWhitelistApps: () -> Unit,
    onNavigateToAppManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_apps),
            icon = Icons.Default.PhoneAndroid,
            description = stringResource(R.string.settings_category_apps_desc)
        )
        SettingsCard(onClick = onNavigateToWhitelistApps) {
            SettingItem(
                icon = Icons.Default.AppBlocking,
                title = stringResource(R.string.settings_whitelist_apps),
                desc = stringResource(R.string.settings_whitelist_apps_desc)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(onClick = onNavigateToAppManagement) {
            SettingItem(
                icon = Icons.Default.Apps,
                title = stringResource(R.string.app_management_title),
                desc = stringResource(R.string.app_management_desc)
            )
        }
    }
}
