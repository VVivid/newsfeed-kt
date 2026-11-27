package dev.vvivid.newsfeed.settings

import android.content.res.AssetManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import dev.vvivid.newsfeed.R
import dev.vvivid.newsfeed.ui.component.LoadingIndicator
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Open-source software the app includes. Every library in the release build is covered by one of
 * these entries; the full license texts ship as assets in `assets/licenses/`.
 */
internal val ThirdPartyNotices: List<ThirdPartyNotice> =
    listOf(
        ThirdPartyNotice(
            name =
                "AndroidX (Activity, AppCompat, Browser, Compose, Core, Lifecycle, Media3, Navigation, WorkManager " +
                    "and the libraries they use)",
            copyright = "The Android Open Source Project",
            license = License.Apache2,
        ),
        ThirdPartyNotice(name = "AppAuth for Android", copyright = "The AppAuth for Android Authors", license = License.Apache2),
        ThirdPartyNotice(name = "OkHttp and Okio", copyright = "Square, Inc.", license = License.Apache2),
        ThirdPartyNotice(
            name = "Kotlin standard library, kotlinx.coroutines and kotlinx.serialization",
            copyright = "JetBrains s.r.o. and Kotlin Programming Language contributors",
            license = License.Apache2,
        ),
        ThirdPartyNotice(
            name = "JetBrains Java Annotations, JSpecify and Guava",
            copyright = "JetBrains s.r.o., the JSpecify Authors and the Guava Authors",
            license = License.Apache2,
        ),
        ThirdPartyNotice(
            name = "commonmark-java and its GFM tables extension",
            copyright = "Atlassian Pty Ltd",
            license = License.CommonmarkBsd2,
        ),
        ThirdPartyNotice(name = "Source Serif 4", copyright = "Adobe", license = License.SourceSerifOfl),
        ThirdPartyNotice(name = "Mermaid (draws article diagrams)", copyright = "Knut Sveidqvist", license = License.MermaidMit),
    )

/** One component and the license it is used under. */
internal class ThirdPartyNotice(
    val name: String,
    val copyright: String,
    val license: License,
)

/** A license text shipped with the app, kept byte for byte as published. */
internal enum class License(
    val title: String,
    val assetPath: String,
) {
    Apache2("Apache License 2.0", "licenses/Apache-2.0.txt"),
    CommonmarkBsd2("BSD 2-Clause License (commonmark-java)", "licenses/commonmark-java-BSD-2-Clause.txt"),
    SourceSerifOfl("SIL Open Font License 1.1 (Source Serif 4)", "licenses/SourceSerif4-OFL.txt"),
    MermaidMit("MIT License (Mermaid)", "licenses/mermaid-MIT.txt"),
}

/** The notices and license texts. The texts are read from the app's assets, off the main thread. */
@Composable
fun LicensesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val assets = LocalContext.current.assets
    val licenseTexts by produceState<Map<License, String>?>(initialValue = null, assets) {
        value = withContext(Dispatchers.IO) { License.entries.associateWith { assets.readText(it.assetPath) } }
    }
    LicensesScreen(licenseTexts = licenseTexts, onBack = onBack, modifier = modifier)
}

private fun AssetManager.readText(path: String): String =
    try {
        open(path).bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        // Every license is packaged with the app, so this only happens in a broken build.
        ""
    }

/** Renders [ThirdPartyNotices], then each license text once [licenseTexts] has loaded. */
@Composable
internal fun LicensesScreen(
    licenseTexts: Map<License, String>?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, topBar = { LicensesTopBar(onBack) }) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(text = stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyLarge) }
            items(ThirdPartyNotices) { notice -> NoticeCard(notice) }
            if (licenseTexts == null) {
                item { LoadingIndicator(description = stringResource(R.string.licenses_loading), modifier = Modifier.padding(24.dp)) }
            } else {
                items(License.entries) { license -> LicenseText(license.title, licenseTexts[license].orEmpty()) }
            }
        }
    }
}

// TopAppBar is still experimental in Material 3.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.settings_licenses),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back_to_settings),
                )
            }
        },
    )
}

@Composable
private fun NoticeCard(notice: ThirdPartyNotice) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = notice.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.licenses_notice, notice.copyright, notice.license.title),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LicenseText(
    title: String,
    text: String,
) {
    Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
}

@PreviewLightDark
@Composable
private fun LicensesPreview() {
    NewsfeedTheme {
        LicensesScreen(licenseTexts = License.entries.associateWith { "License text." }, onBack = {})
    }
}
