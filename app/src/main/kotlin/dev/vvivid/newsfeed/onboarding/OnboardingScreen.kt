package dev.vvivid.newsfeed.onboarding

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vvivid.newsfeed.R
import dev.vvivid.newsfeed.auth.BrowserSignInResult
import dev.vvivid.newsfeed.auth.DiscoveryProblem
import dev.vvivid.newsfeed.auth.ServerConfiguration
import dev.vvivid.newsfeed.auth.SessionEndReason
import dev.vvivid.newsfeed.auth.SignInException
import dev.vvivid.newsfeed.auth.SignInProblem
import dev.vvivid.newsfeed.auth.authorizationRequest
import dev.vvivid.newsfeed.auth.browserSignInResult
import dev.vvivid.newsfeed.ui.component.Notice
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import net.openid.appauth.AuthorizationService

/**
 * Onboarding with the browser sign-in wired in. AppAuth's [AuthorizationService] opens the sign-in
 * page in a Custom Tab; its activity returns the result, which [browserSignInResult] reads.
 */
@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // It keeps a connection to the browser's Custom Tabs service, so it is disposed with the screen.
    val authorizationService = remember(context) { AuthorizationService(context) }
    DisposableEffect(authorizationService) { onDispose { authorizationService.dispose() } }
    val signInLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onSignInResult(browserSignInResult(result.data))
        }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }
    BackHandler(enabled = uiState.step is OnboardingStep.ConfirmServer, onBack = viewModel::onUseDifferentServer)
    OnboardingScreen(
        uiState = uiState,
        onServerAddressChange = viewModel::onServerAddressChange,
        onContinue = viewModel::onContinue,
        onSignIn = {
            val server = viewModel.onSignInStart()
            if (server != null) {
                try {
                    signInLauncher.launch(authorizationService.getAuthorizationRequestIntent(authorizationRequest(server)))
                } catch (e: ActivityNotFoundException) {
                    viewModel.onSignInResult(BrowserSignInResult.Failed(SignInProblem.NoBrowser))
                } catch (e: SignInException) {
                    viewModel.onSignInResult(BrowserSignInResult.Failed(e.problem))
                }
            }
        },
        onUseDifferentServer = viewModel::onUseDifferentServer,
        modifier = modifier,
    )
}

/**
 * Onboarding's two steps, "Connect to your Newsfeed" and "Server found": renders [uiState] and
 * reports the user's input.
 */
@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onServerAddressChange: (String) -> Unit,
    onContinue: () -> Unit,
    onSignIn: () -> Unit,
    onUseDifferentServer: () -> Unit,
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
            val step = uiState.step
            // Only this part scrolls, so the buttons stay visible above the keyboard.
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                AppMark()
                Spacer(Modifier.height(28.dp))
                when (step) {
                    is OnboardingStep.EnterAddress -> {
                        EnterAddressContent(uiState.serverAddress, step, uiState.sessionEndReason, onServerAddressChange, onContinue)
                    }

                    is OnboardingStep.ConfirmServer -> {
                        ConfirmServerContent(step)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when (step) {
                is OnboardingStep.EnterAddress -> {
                    PrimaryButton(
                        text = stringResource(if (step.isChecking) R.string.onboarding_checking else R.string.onboarding_continue),
                        isBusy = step.isChecking,
                        onClick = onContinue,
                    )
                }

                is OnboardingStep.ConfirmServer -> {
                    PrimaryButton(
                        text = stringResource(if (step.isFinishingSignIn) R.string.onboarding_signing_in else R.string.onboarding_sign_in),
                        isBusy = step.isFinishingSignIn,
                        onClick = onSignIn,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onUseDifferentServer,
                        enabled = !step.isFinishingSignIn,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.onboarding_use_different_server))
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EnterAddressContent(
    address: String,
    step: OnboardingStep.EnterAddress,
    sessionEndReason: SessionEndReason?,
    onAddressChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Title(R.string.onboarding_title)
    Spacer(Modifier.height(16.dp))
    Text(text = stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge)
    if (sessionEndReason != null) {
        Spacer(Modifier.height(16.dp))
        Notice(
            icon = R.drawable.ic_info,
            text = stringResource(sessionEndReason.messageRes),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    Spacer(Modifier.height(28.dp))
    val feedback = step.feedback
    ServerAddressField(
        address = address,
        problem = (feedback as? AddressFeedback.Invalid)?.problem,
        onAddressChange = onAddressChange,
        onGo = onContinue,
    )
    ProblemMessage(
        text = (feedback as? AddressFeedback.ServerNotUsable)?.let { stringResource(it.problem.messageRes, it.host) },
    )
    Spacer(Modifier.height(20.dp))
    Notice(
        icon = R.drawable.ic_lock,
        text = stringResource(R.string.onboarding_https_notice),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

/** "Server found": what the server reported, to check before signing in. */
@Composable
private fun ColumnScope.ConfirmServerContent(step: OnboardingStep.ConfirmServer) {
    Title(R.string.onboarding_server_found_title)
    Spacer(Modifier.height(16.dp))
    Text(text = stringResource(R.string.onboarding_server_found_intro), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(24.dp))
    ServerDetails(step.server)
    ProblemMessage(text = step.problem?.let { stringResource(it.messageRes) })
}

@Composable
private fun Title(
    @StringRes text: Int,
) {
    Text(
        text = stringResource(text),
        modifier = Modifier.semantics { heading() },
        // Headings use tertiary, the design's heading color (see ui/theme/Color.kt).
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.headlineLarge,
    )
}

@Composable
private fun ServerDetails(server: ServerConfiguration) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            DetailRow(icon = R.drawable.ic_server, label = stringResource(R.string.onboarding_server_label), value = server.origin)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailRow(
                icon = R.drawable.ic_shield,
                label = stringResource(R.string.onboarding_sign_in_page_label),
                value = server.signInHost,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailRow(
                icon = R.drawable.ic_access,
                label = stringResource(R.string.onboarding_access_label),
                value = stringResource(R.string.onboarding_access_value),
            )
        }
    }
}

/** A labelled value. TalkBack reads the label and the value together. */
@Composable
private fun DetailRow(
    @DrawableRes icon: Int,
    label: String,
    value: String,
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
            Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Why the last step failed, or nothing. The polite live region exists even while empty, so TalkBack
 * reads the message when it appears.
 */
@Composable
private fun ProblemMessage(text: String?) {
    Box(modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        if (text != null) {
            Notice(
                icon = R.drawable.ic_info,
                text = text,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** The step's main button. While [isBusy] it shows progress and does not respond. */
@Composable
private fun PrimaryButton(
    text: String,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, lineHeight = 20.sp))
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

/** Each message takes the host that failed as its argument. */
@get:StringRes
private val DiscoveryProblem.messageRes: Int
    get() =
        when (this) {
            DiscoveryProblem.Unreachable -> R.string.discovery_error_unreachable
            DiscoveryProblem.SecureConnectionFailed -> R.string.discovery_error_secure_connection
            DiscoveryProblem.NotNewsfeedServer -> R.string.discovery_error_not_newsfeed
            DiscoveryProblem.ServerError -> R.string.discovery_error_server_error
            DiscoveryProblem.UnsupportedSignIn -> R.string.discovery_error_unsupported_sign_in
        }

@get:StringRes
private val SignInProblem.messageRes: Int
    get() =
        when (this) {
            SignInProblem.Cancelled -> R.string.sign_in_error_cancelled
            SignInProblem.AccessDenied -> R.string.sign_in_error_access_denied
            SignInProblem.NoBrowser -> R.string.sign_in_error_no_browser
            SignInProblem.SignInServerUnreachable -> R.string.sign_in_error_unreachable
            SignInProblem.Rejected -> R.string.sign_in_error_rejected
            SignInProblem.ReadAccessNotGranted -> R.string.sign_in_error_no_read_access
            SignInProblem.Failed -> R.string.sign_in_error_failed
        }

@get:StringRes
private val SessionEndReason.messageRes: Int
    get() =
        when (this) {
            SessionEndReason.Expired -> R.string.onboarding_session_expired
            SessionEndReason.NotAccepted -> R.string.onboarding_session_not_accepted
        }

private val PreviewServer =
    ServerConfiguration(
        origin = "https://news.example.com",
        apiResource = "https://news.example.com/api/v1",
        issuer = "https://auth.example.com/realms/example",
        authorizationEndpoint = "https://auth.example.com/realms/example/protocol/openid-connect/auth",
        tokenEndpoint = "https://auth.example.com/realms/example/protocol/openid-connect/token",
    )

@Composable
private fun OnboardingScreenPreview(uiState: OnboardingUiState) {
    NewsfeedTheme {
        OnboardingScreen(
            uiState = uiState,
            onServerAddressChange = {},
            onContinue = {},
            onSignIn = {},
            onUseDifferentServer = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun OnboardingEnterAddressPreview() {
    OnboardingScreenPreview(OnboardingUiState())
}

@PreviewLightDark
@Composable
private fun OnboardingInvalidAddressPreview() {
    OnboardingScreenPreview(
        OnboardingUiState(
            serverAddress = "http://news.example.com",
            step = OnboardingStep.EnterAddress(AddressFeedback.Invalid(ServerAddressProblem.HttpNotAllowed)),
        ),
    )
}

@PreviewLightDark
@Composable
private fun OnboardingServerNotUsablePreview() {
    OnboardingScreenPreview(
        OnboardingUiState(
            serverAddress = "news.example.com",
            step = OnboardingStep.EnterAddress(AddressFeedback.ServerNotUsable(DiscoveryProblem.NotNewsfeedServer, "news.example.com")),
            sessionEndReason = SessionEndReason.Expired,
        ),
    )
}

@PreviewLightDark
@Composable
private fun OnboardingServerFoundPreview() {
    OnboardingScreenPreview(OnboardingUiState(serverAddress = "news.example.com", step = OnboardingStep.ConfirmServer(PreviewServer)))
}

@PreviewLightDark
@Composable
private fun OnboardingSignInCancelledPreview() {
    OnboardingScreenPreview(
        OnboardingUiState(
            serverAddress = "news.example.com",
            step = OnboardingStep.ConfirmServer(PreviewServer, problem = SignInProblem.Cancelled),
        ),
    )
}
