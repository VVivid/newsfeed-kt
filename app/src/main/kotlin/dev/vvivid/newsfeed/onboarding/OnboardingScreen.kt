package dev.vvivid.newsfeed.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vvivid.newsfeed.R
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme

@Composable
fun OnboardingRoute(
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OnboardingScreen(
        uiState = uiState,
        onServerAddressChange = viewModel::onServerAddressChange,
        onContinue = viewModel::onContinue,
        modifier = modifier,
    )
}

/** The "Connect to your Newsfeed" step: renders [uiState] and reports the user's input. */
@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onServerAddressChange: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 24.dp, top = 32.dp, end = 24.dp, bottom = 32.dp),
        ) {
            // Only this part scrolls, so Continue stays visible above the keyboard.
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                AppMark()
                Spacer(Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.onboarding_title),
                    modifier = Modifier.semantics { heading() },
                    // Headings use tertiary, the design's heading color (see ui/theme/Color.kt).
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.height(16.dp))
                Text(text = stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(28.dp))
                ServerAddressField(
                    address = uiState.serverAddress,
                    problem = (uiState.feedback as? AddressFeedback.Invalid)?.problem,
                    onAddressChange = onServerAddressChange,
                    onGo = onContinue,
                )
                SignInUnavailableMessage(feedback = uiState.feedback)
                Spacer(Modifier.height(20.dp))
                Notice(
                    icon = R.drawable.ic_lock,
                    text = stringResource(R.string.onboarding_https_notice),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_continue),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, lineHeight = 20.sp),
                )
            }
        }
    }
}

/** The "N" logo tile. Decorative, and kept at a fixed size because it is a logo, not text. */
@Composable
private fun AppMark() {
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val markSize = with(LocalDensity.current) { 32.dp.toSp() }
        Text(
            text = "N",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = markSize, lineHeight = markSize),
        )
    }
}

@Composable
private fun ServerAddressField(
    address: String,
    problem: ServerAddressProblem?,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
) {
    val errorMessage = problem?.let { stringResource(it.messageRes) }
    val fieldColor = MaterialTheme.colorScheme.surfaceContainerLowest
    OutlinedTextField(
        value = address,
        onValueChange = onAddressChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    if (errorMessage != null) {
                        // Read the specific problem instead of Material's generic "Invalid input",
                        // and announce it when it appears after Continue. Typing clears the error,
                        // so the field is not a live region while the user edits it.
                        error(errorMessage)
                        liveRegion = LiveRegionMode.Polite
                    }
                },
        label = { Text(stringResource(R.string.onboarding_address_label)) },
        placeholder = { Text(stringResource(R.string.onboarding_address_placeholder)) },
        prefix = { Text(stringResource(R.string.onboarding_address_prefix)) },
        supportingText = { Text(errorMessage ?: stringResource(R.string.onboarding_address_help)) },
        isError = errorMessage != null,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = fieldColor,
                unfocusedContainerColor = fieldColor,
                errorContainerColor = fieldColor,
            ),
    )
}

/**
 * The answer to a valid Continue. The polite live region exists even while empty, so TalkBack
 * reads the message when it appears.
 */
@Composable
private fun SignInUnavailableMessage(feedback: AddressFeedback) {
    Box(modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        if (feedback is AddressFeedback.SignInUnavailable) {
            Notice(
                icon = R.drawable.ic_info,
                text = stringResource(R.string.onboarding_sign_in_unavailable, feedback.origin),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun Notice(
    @DrawableRes icon: Int,
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@get:StringRes
private val ServerAddressProblem.messageRes: Int
    get() =
        when (this) {
            ServerAddressProblem.Empty -> R.string.server_address_error_empty
            ServerAddressProblem.HttpNotAllowed -> R.string.server_address_error_http
            ServerAddressProblem.UnsupportedScheme -> R.string.server_address_error_scheme
            ServerAddressProblem.UserInfoNotAllowed -> R.string.server_address_error_user_info
            ServerAddressProblem.InvalidHost -> R.string.server_address_error_host
            ServerAddressProblem.Ipv6NotSupported -> R.string.server_address_error_ipv6
            ServerAddressProblem.InvalidPort -> R.string.server_address_error_port
        }

@PreviewLightDark
@Composable
private fun OnboardingScreenPreview() {
    NewsfeedTheme {
        OnboardingScreen(uiState = OnboardingUiState(), onServerAddressChange = {}, onContinue = {})
    }
}

@Preview
@Composable
private fun OnboardingScreenInvalidAddressPreview() {
    NewsfeedTheme {
        OnboardingScreen(
            uiState =
                OnboardingUiState(
                    serverAddress = "http://news.example.com",
                    feedback = AddressFeedback.Invalid(ServerAddressProblem.HttpNotAllowed),
                ),
            onServerAddressChange = {},
            onContinue = {},
        )
    }
}

@Preview
@Composable
private fun OnboardingScreenSignInUnavailablePreview() {
    NewsfeedTheme {
        OnboardingScreen(
            uiState =
                OnboardingUiState(
                    serverAddress = "news.example.com",
                    feedback = AddressFeedback.SignInUnavailable("https://news.example.com"),
                ),
            onServerAddressChange = {},
            onContinue = {},
        )
    }
}
