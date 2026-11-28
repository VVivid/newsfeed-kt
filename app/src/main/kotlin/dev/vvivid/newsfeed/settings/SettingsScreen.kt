package dev.vvivid.newsfeed.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import dev.vvivid.newsfeed.BuildConfig
import dev.vvivid.newsfeed.R
import dev.vvivid.newsfeed.ui.preview.PreviewSampleData
import dev.vvivid.newsfeed.ui.preview.PreviewSamples
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import dev.vvivid.newsfeed.ui.theme.ThemeMode
import dev.vvivid.newsfeed.ui.theme.isDynamicColorAvailable

/** Everything the Settings screen shows. */
data class SettingsUiState(
    /** The signed-in server, such as `https://news.example.com`. */
    val serverOrigin: String,
    /** Where the user signed in, such as `auth.example.com`. */
    val signInHost: String,
    val themeMode: ThemeMode,
    val useDynamicColor: Boolean,
    /** Whether this device has Material You colors (Android 12 and newer). */
    val isDynamicColorAvailable: Boolean,
    val appVersion: String,
    val isChangeServerDialogOpen: Boolean = false,
    val keepScreenOn: Boolean = true,
)

/**
 * Settings for the signed-in server. The appearance choices belong to the whole app, so they come
 * in and go out through parameters. [onSignOut] and [onChangeServer] (after confirmation) leave
 * the server.
 */
@Composable
fun SettingsRoute(
    serverOrigin: String,
    signInHost: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onChangeServer: () -> Unit,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    keepScreenOn: Boolean = true,
    onKeepScreenOnChange: (Boolean) -> Unit = {},
) {
    var isChangeServerDialogOpen by rememberSaveable { mutableStateOf(false) }
    SettingsScreen(
        uiState =
            SettingsUiState(
                serverOrigin = serverOrigin,
                signInHost = signInHost,
                themeMode = themeMode,
                useDynamicColor = useDynamicColor,
                isDynamicColorAvailable = isDynamicColorAvailable,
                appVersion = BuildConfig.VERSION_NAME,
                isChangeServerDialogOpen = isChangeServerDialogOpen,
                keepScreenOn = keepScreenOn,
            ),
        onBack = onBack,
        onThemeModeChange = onThemeModeChange,
        onDynamicColorChange = onDynamicColorChange,
        onKeepScreenOnChange = onKeepScreenOnChange,
        onSignOut = onSignOut,
        onChangeServerClick = { isChangeServerDialogOpen = true },
        onChangeServerDismiss = { isChangeServerDialogOpen = false },
        onChangeServerConfirm = {
            isChangeServerDialogOpen = false
            onChangeServer()
        },
        onOpenLicenses = onOpenLicenses,
        modifier = modifier,
    )
}

/** Renders [uiState] and reports the user's actions. */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onChangeServerClick: () -> Unit,
    onChangeServerDismiss: () -> Unit,
    onChangeServerConfirm: () -> Unit,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    onKeepScreenOnChange: (Boolean) -> Unit = {},
) {
    val serverAddress = displayServerAddress(uiState.serverOrigin)
    Scaffold(
        modifier = modifier,
        topBar = { SettingsTopBar(onBack) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeading(R.string.settings_server, isFirst = true)
            SettingsCard {
                InfoRow(icon = R.drawable.ic_server, text = serverAddress)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionRow(
                    icon = R.drawable.ic_swap,
                    text = stringResource(R.string.settings_change_server),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = onChangeServerClick,
                )
            }

            SectionHeading(R.string.settings_account)
            SettingsCard {
                InfoRow(
                    icon = R.drawable.ic_account,
                    text = stringResource(R.string.settings_signed_in),
                    supportingText = stringResource(R.string.settings_signed_in_via, uiState.signInHost),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionRow(
                    icon = R.drawable.ic_sign_out,
                    text = stringResource(R.string.settings_sign_out),
                    color = MaterialTheme.colorScheme.onSurface,
                    onClick = onSignOut,
                )
            }

            SectionHeading(R.string.settings_appearance)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ThemeSelector(uiState.themeMode, onThemeModeChange)
                    if (uiState.isDynamicColorAvailable) {
                        SettingSwitch(
                            title = R.string.settings_dynamic_color,
                            summary = R.string.settings_dynamic_color_summary,
                            checked = uiState.useDynamicColor,
                            onCheckedChange = onDynamicColorChange,
                        )
                    }
                    SettingSwitch(
                        title = R.string.settings_keep_screen_on,
                        summary = R.string.settings_keep_screen_on_summary,
                        checked = uiState.keepScreenOn,
                        onCheckedChange = onKeepScreenOnChange,
                    )
                }
            }

            SectionHeading(R.string.settings_about)
            SettingsCard {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = stringResource(R.string.settings_version), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = uiState.appVersion,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionRow(
                    icon = R.drawable.ic_info,
                    text = stringResource(R.string.settings_licenses),
                    color = MaterialTheme.colorScheme.onSurface,
                    onClick = onOpenLicenses,
                )
            }
        }
    }
    if (uiState.isChangeServerDialogOpen) {
        ChangeServerDialog(serverAddress, onConfirm = onChangeServerConfirm, onDismiss = onChangeServerDismiss)
    }
}

/** The origin without its `https://` scheme, as people type it. */
private fun displayServerAddress(origin: String): String = origin.removePrefix("https://")

// TopAppBar is still experimental in Material 3.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.settings_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back_to_news),
                )
            }
        },
    )
}

@Composable
private fun SectionHeading(
    @StringRes text: Int,
    isFirst: Boolean = false,
) {
    Text(
        text = stringResource(text),
        modifier = Modifier.padding(start = 4.dp, top = if (isFirst) 8.dp else 16.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

/** A fact with an optional second line. TalkBack reads both lines together. */
@Composable
private fun InfoRow(
    @DrawableRes icon: Int,
    text: String,
    supportingText: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    @DrawableRes icon: Int,
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = color)
        Text(text = text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ThemeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
        val colors =
            SegmentedButtonDefaults.colors(
                activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    colors = colors,
                    label = { Text(stringResource(mode.labelRes)) },
                )
            }
        }
    }
}

@get:StringRes
private val ThemeMode.labelRes: Int
    get() =
        when (this) {
            ThemeMode.System -> R.string.settings_theme_system
            ThemeMode.Light -> R.string.settings_theme_light
            ThemeMode.Dark -> R.string.settings_theme_dark
        }

/** The whole row toggles the switch, so it is one target for touch and for TalkBack. */
@Composable
private fun SettingSwitch(
    @StringRes title: Int,
    @StringRes summary: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(summary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChangeServerDialog(
    serverAddress: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.settings_change_server)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        icon = { Icon(painter = painterResource(R.drawable.ic_swap), contentDescription = null) },
        title = { Text(stringResource(R.string.settings_change_server_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_change_server_message, serverAddress))
                Text(stringResource(R.string.settings_change_server_server_kept))
            }
        },
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.tertiary,
        textContentColor = MaterialTheme.colorScheme.onSurface,
    )
}

@OptIn(PreviewSampleData::class)
@Composable
private fun SettingsScreenPreview(
    themeMode: ThemeMode = ThemeMode.System,
    isDynamicColorAvailable: Boolean = true,
    isChangeServerDialogOpen: Boolean = false,
) {
    NewsfeedTheme {
        SettingsScreen(
            uiState =
                SettingsUiState(
                    serverOrigin = PreviewSamples.serverOrigin,
                    signInHost = PreviewSamples.signInHost,
                    themeMode = themeMode,
                    useDynamicColor = false,
                    isDynamicColorAvailable = isDynamicColorAvailable,
                    appVersion = BuildConfig.VERSION_NAME,
                    isChangeServerDialogOpen = isChangeServerDialogOpen,
                ),
            onBack = {},
            onThemeModeChange = {},
            onDynamicColorChange = {},
            onSignOut = {},
            onChangeServerClick = {},
            onChangeServerDismiss = {},
            onChangeServerConfirm = {},
            onOpenLicenses = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun SettingsPreview() {
    SettingsScreenPreview()
}

@PreviewLightDark
@Composable
private fun SettingsWithoutDynamicColorPreview() {
    SettingsScreenPreview(themeMode = ThemeMode.Dark, isDynamicColorAvailable = false)
}

@PreviewLightDark
@Composable
private fun SettingsChangeServerDialogPreview() {
    SettingsScreenPreview(isChangeServerDialogOpen = true)
}
