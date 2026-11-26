package dev.vvivid.newsfeed.onboarding

import androidx.lifecycle.SavedStateHandle
import dev.vvivid.newsfeed.MainDispatcherRule
import dev.vvivid.newsfeed.auth.BrowserAuthorization
import dev.vvivid.newsfeed.auth.BrowserSignInResult
import dev.vvivid.newsfeed.auth.DiscoveryException
import dev.vvivid.newsfeed.auth.DiscoveryProblem
import dev.vvivid.newsfeed.auth.SessionEndReason
import dev.vvivid.newsfeed.auth.SignInException
import dev.vvivid.newsfeed.auth.SignInProblem
import dev.vvivid.newsfeed.auth.testServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val server = testServer()
    private val signInService = FakeSignInService(server)
    private val savedState = SavedStateHandle()

    /** What the sign-in page returns for [server]. Not a data class: tests compare this instance. */
    private val authorization =
        BrowserAuthorization(
            code = "test-code",
            codeVerifier = "test-verifier",
            nonce = "test-nonce",
            tokenEndpoint = server.tokenEndpoint,
            resource = server.apiResource,
        )

    @Test
    fun `starts empty, or with the last server's address`() {
        assertEquals(OnboardingUiState(), viewModel().uiState.value)
        assertEquals("news.example.com:8443", viewModel(lastServerOrigin = "https://news.example.com:8443").uiState.value.serverAddress)
    }

    @Test
    fun `continue with an invalid address reports the problem, keeps the input and asks no server`() {
        val viewModel = viewModel()
        viewModel.onServerAddressChange("http://news.example.com")

        viewModel.onContinue()

        assertEquals(
            OnboardingUiState(
                serverAddress = "http://news.example.com",
                step = OnboardingStep.EnterAddress(AddressFeedback.Invalid(ServerAddressProblem.HttpNotAllowed)),
            ),
            viewModel.uiState.value,
        )
        assertTrue(signInService.discoveredOrigins.isEmpty())
    }

    @Test
    fun `continue with a valid address checks the server, then asks to confirm it`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onServerAddressChange("News.Example.com/feed")

            viewModel.onContinue()
            assertEquals(OnboardingStep.EnterAddress(isChecking = true), viewModel.uiState.value.step)
            advanceUntilIdle()

            assertEquals(listOf("https://news.example.com"), signInService.discoveredOrigins)
            assertEquals(OnboardingStep.ConfirmServer(server), viewModel.uiState.value.step)
        }

    @Test
    fun `a server that cannot be used is reported with its host`() =
        runTest {
            signInService.discoveryFailure = DiscoveryException(DiscoveryProblem.NotNewsfeedServer, "news.example.com")
            val viewModel = viewModel()
            viewModel.onServerAddressChange("news.example.com")

            viewModel.onContinue()
            advanceUntilIdle()

            assertEquals(
                OnboardingStep.EnterAddress(AddressFeedback.ServerNotUsable(DiscoveryProblem.NotNewsfeedServer, "news.example.com")),
                viewModel.uiState.value.step,
            )
        }

    @Test
    fun `editing the address while the server is checked drops the check`() =
        runTest {
            signInService.heldDiscovery = CompletableDeferred()
            val viewModel = viewModel()
            viewModel.onServerAddressChange("news.example.com")
            viewModel.onContinue()
            advanceUntilIdle()

            viewModel.onServerAddressChange("news.example.org")
            signInService.heldDiscovery?.complete(Unit)
            advanceUntilIdle()

            assertEquals(OnboardingUiState(serverAddress = "news.example.org"), viewModel.uiState.value)
        }

    @Test
    fun `a different server can be chosen after the check`() =
        runTest {
            val viewModel = confirmingViewModel()

            viewModel.onUseDifferentServer()

            assertEquals(OnboardingUiState(serverAddress = "news.example.com"), viewModel.uiState.value)
        }

    @Test
    fun `a cancelled sign-in says so, and signing in again clears the message`() =
        runTest {
            val viewModel = confirmingViewModel()
            assertEquals(server, viewModel.onSignInStart())

            viewModel.onSignInResult(BrowserSignInResult.Failed(SignInProblem.Cancelled))
            assertEquals(SignInProblem.Cancelled, viewModel.confirmStep().problem)

            assertEquals(server, viewModel.onSignInStart())
            assertNull(viewModel.confirmStep().problem)
        }

    @Test
    fun `a second Sign in while the sign-in page is open does nothing`() =
        runTest {
            val viewModel = confirmingViewModel()
            assertEquals(server, viewModel.onSignInStart())

            assertNull(viewModel.onSignInStart())

            // The page closed without a result; once the screen is back in front, Sign in works again.
            viewModel.onScreenResumed()
            assertEquals(server, viewModel.onSignInStart())
        }

    @Test
    fun `an authorized sign-in is finished for the confirmed server`() =
        runTest {
            val viewModel = confirmingViewModel()
            viewModel.onSignInStart()

            viewModel.onSignInResult(BrowserSignInResult.Authorized(authorization))
            assertTrue(viewModel.confirmStep().isFinishingSignIn)
            // A second result while finishing is ignored.
            viewModel.onSignInResult(BrowserSignInResult.Failed(SignInProblem.Cancelled))
            advanceUntilIdle()

            assertEquals(listOf(server to authorization), signInService.finishedSignIns)
        }

    @Test
    fun `a sign-in that cannot be finished says why and can be tried again`() =
        runTest {
            signInService.signInFailure = SignInException(SignInProblem.SignInServerUnreachable)
            val viewModel = confirmingViewModel()

            viewModel.onSignInResult(BrowserSignInResult.Authorized(authorization))
            advanceUntilIdle()

            assertEquals(
                OnboardingStep.ConfirmServer(server, problem = SignInProblem.SignInServerUnreachable),
                viewModel.uiState.value.step,
            )
            assertEquals(server, viewModel.onSignInStart())
        }

    @Test
    fun `why the last session ended shows until the user continues`() =
        runTest {
            val viewModel = viewModel(lastServerOrigin = "https://news.example.com", sessionEndReason = SessionEndReason.Expired)
            assertEquals(SessionEndReason.Expired, viewModel.uiState.value.sessionEndReason)

            viewModel.onContinue()

            assertNull(viewModel.uiState.value.sessionEndReason)
        }

    @Test
    fun `a sign-in that returns after Android stopped the app still knows its server`() =
        runTest {
            confirmingViewModel()

            // A new view model with the same saved state, as after the process was recreated.
            val restored = viewModel()

            assertEquals(
                OnboardingUiState(serverAddress = "news.example.com", step = OnboardingStep.ConfirmServer(server)),
                restored.uiState.value,
            )
        }

    private fun viewModel(
        lastServerOrigin: String? = null,
        sessionEndReason: SessionEndReason? = null,
    ) = OnboardingViewModel(savedState, signInService, lastServerOrigin, sessionEndReason)

    private fun TestScope.confirmingViewModel(): OnboardingViewModel {
        val viewModel = viewModel()
        viewModel.onServerAddressChange("news.example.com")
        viewModel.onContinue()
        advanceUntilIdle()
        return viewModel
    }

    private fun OnboardingViewModel.confirmStep(): OnboardingStep.ConfirmServer = uiState.value.step as OnboardingStep.ConfirmServer
}
