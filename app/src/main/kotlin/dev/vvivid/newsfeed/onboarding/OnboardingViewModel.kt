package dev.vvivid.newsfeed.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvivid.newsfeed.auth.BrowserAuthorization
import dev.vvivid.newsfeed.auth.BrowserSignInResult
import dev.vvivid.newsfeed.auth.DiscoveryException
import dev.vvivid.newsfeed.auth.DiscoveryProblem
import dev.vvivid.newsfeed.auth.ServerConfiguration
import dev.vvivid.newsfeed.auth.SessionEndReason
import dev.vvivid.newsfeed.auth.SignInException
import dev.vvivid.newsfeed.auth.SignInProblem
import dev.vvivid.newsfeed.auth.SignInService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Everything the onboarding screen shows. */
data class OnboardingUiState(
    val serverAddress: String = "",
    val step: OnboardingStep = OnboardingStep.EnterAddress(),
    /** Why the previous session ended, shown on the address step until the user continues. */
    val sessionEndReason: SessionEndReason? = null,
)

/** The two steps of onboarding. */
sealed interface OnboardingStep {
    /**
     * "Connect to your Newsfeed": entering the server address.
     *
     * @property isChecking the server is being asked how to sign in.
     */
    data class EnterAddress(
        val feedback: AddressFeedback = AddressFeedback.None,
        val isChecking: Boolean = false,
    ) : OnboardingStep

    /**
     * "Server found": the user checks what [server] reported before signing in on its sign-in page.
     *
     * @property isFinishingSignIn the sign-in page sent the user back and the app is getting the
     *   session's tokens.
     * @property problem why the last attempt to sign in did not finish.
     */
    data class ConfirmServer(
        val server: ServerConfiguration,
        val isFinishingSignIn: Boolean = false,
        val problem: SignInProblem? = null,
    ) : OnboardingStep
}

/** The answer to the last Continue. Editing the address clears it. */
sealed interface AddressFeedback {
    data object None : AddressFeedback

    data class Invalid(
        val problem: ServerAddressProblem,
    ) : AddressFeedback

    /** The address is valid, but the server there cannot be used to sign in: [problem] at [host]. */
    data class ServerNotUsable(
        val problem: DiscoveryProblem,
        val host: String,
    ) : AddressFeedback
}

/**
 * Onboarding: check the address, discover the server, then sign in.
 *
 * 1. [onContinue] validates the address and asks [signInService] how to sign in to that server.
 * 2. On success the step becomes [OnboardingStep.ConfirmServer]; the user signs in or goes back.
 * 3. Sign in opens the server's sign-in page in the browser ([onSignInStart]). Its result comes back
 *    through [onSignInResult], and [SignInService.finishSignIn] starts the session. The app then
 *    leaves onboarding.
 *
 * The address and a discovered server are kept in [savedState], so that a sign-in finishing after
 * Android stopped the app in the background still knows which server it belongs to.
 *
 * @param lastServerOrigin the server signed in to before, offered as the address.
 * @param sessionEndReason why the previous session ended, if it ended without the user signing out.
 */
class OnboardingViewModel(
    private val savedState: SavedStateHandle,
    private val signInService: SignInService,
    lastServerOrigin: String? = null,
    sessionEndReason: SessionEndReason? = null,
) : ViewModel() {
    private val _uiState: MutableStateFlow<OnboardingUiState>
    val uiState: StateFlow<OnboardingUiState>

    /** The running server check. Editing the address cancels it. */
    private var checkJob: Job? = null

    /**
     * Whether the sign-in page is open, so that a second tap on Sign in does not open another.
     * Not saved: after Android restarts the app, no page is open for this view model.
     */
    private var isSignInPageOpen = false

    init {
        val savedServer = savedServer()
        val address = savedState.get<String>(KEY_ADDRESS) ?: lastServerOrigin?.removePrefix("https://").orEmpty()
        val step = if (savedServer != null) OnboardingStep.ConfirmServer(savedServer) else OnboardingStep.EnterAddress()
        _uiState = MutableStateFlow(OnboardingUiState(address, step, sessionEndReason))
        uiState = _uiState.asStateFlow()
    }

    fun onServerAddressChange(address: String) {
        // Feedback and a running check described the previous address, so they no longer apply.
        checkJob?.cancel()
        savedState[KEY_ADDRESS] = address
        _uiState.update { it.copy(serverAddress = address, step = OnboardingStep.EnterAddress()) }
    }

    fun onContinue() {
        val state = _uiState.value
        val step = state.step as? OnboardingStep.EnterAddress ?: return
        if (step.isChecking) return
        when (val result = normalizeServerOrigin(state.serverAddress)) {
            is ServerOriginResult.Invalid -> {
                _uiState.update { it.copy(step = OnboardingStep.EnterAddress(AddressFeedback.Invalid(result.problem))) }
            }

            is ServerOriginResult.Valid -> {
                checkServer(result.origin)
            }
        }
    }

    private fun checkServer(origin: String) {
        _uiState.update { it.copy(step = OnboardingStep.EnterAddress(isChecking = true), sessionEndReason = null) }
        checkJob =
            viewModelScope.launch {
                val step =
                    try {
                        val server = signInService.discover(origin)
                        savedState[KEY_SERVER] = Json.encodeToString(ServerConfiguration.serializer(), server)
                        OnboardingStep.ConfirmServer(server)
                    } catch (e: DiscoveryException) {
                        OnboardingStep.EnterAddress(AddressFeedback.ServerNotUsable(e.problem, e.host))
                    }
                _uiState.update { it.copy(step = step) }
            }
    }

    /** "Use a different server", or Back, on the confirmation step. */
    fun onUseDifferentServer() {
        val step = _uiState.value.step as? OnboardingStep.ConfirmServer ?: return
        if (step.isFinishingSignIn) return
        savedState.remove<String>(KEY_SERVER)
        _uiState.update { it.copy(step = OnboardingStep.EnterAddress()) }
    }

    /**
     * Sign in was tapped. Returns the server whose sign-in page to open, or null when signing in
     * cannot start now.
     */
    fun onSignInStart(): ServerConfiguration? {
        val step = _uiState.value.step as? OnboardingStep.ConfirmServer ?: return null
        if (step.isFinishingSignIn || isSignInPageOpen) return null
        isSignInPageOpen = true
        _uiState.update { it.copy(step = step.copy(problem = null)) }
        return step.server
    }

    /**
     * The screen is in front again. Android delivers the sign-in page's result before that, so a
     * page still counted as open closed without one.
     */
    fun onScreenResumed() {
        isSignInPageOpen = false
    }

    /** The sign-in page returned [result]. */
    fun onSignInResult(result: BrowserSignInResult) {
        isSignInPageOpen = false
        val step = _uiState.value.step as? OnboardingStep.ConfirmServer ?: return
        if (step.isFinishingSignIn) return
        when (result) {
            is BrowserSignInResult.Failed -> _uiState.update { it.copy(step = step.copy(problem = result.problem)) }
            is BrowserSignInResult.Authorized -> finishSignIn(step, result.authorization)
        }
    }

    private fun finishSignIn(
        step: OnboardingStep.ConfirmServer,
        authorization: BrowserAuthorization,
    ) {
        _uiState.update { it.copy(step = step.copy(isFinishingSignIn = true, problem = null)) }
        viewModelScope.launch {
            try {
                signInService.finishSignIn(step.server, authorization)
                // Signed in: the app leaves onboarding, and this view model goes with it.
            } catch (e: SignInException) {
                _uiState.update { it.copy(step = step.copy(isFinishingSignIn = false, problem = e.problem)) }
            }
        }
    }

    /** The server discovered before Android stopped the app, if the confirmation step was shown. */
    private fun savedServer(): ServerConfiguration? {
        val json = savedState.get<String>(KEY_SERVER) ?: return null
        return try {
            Json.decodeFromString(ServerConfiguration.serializer(), json)
        } catch (e: IllegalArgumentException) {
            // Saved by an app version that stored it differently: discover the server again.
            null
        }
    }

    private companion object {
        const val KEY_ADDRESS = "server_address"

        /** The discovered server, as JSON, while the confirmation step is shown. */
        const val KEY_SERVER = "discovered_server"
    }
}
