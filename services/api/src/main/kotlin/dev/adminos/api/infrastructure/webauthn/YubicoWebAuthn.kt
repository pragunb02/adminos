package dev.adminos.api.infrastructure.webauthn

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.AuthenticatorTransport
import com.yubico.webauthn.data.ByteArray as WebAuthnBytes
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import com.yubico.webauthn.exception.AssertionFailedException
import com.yubico.webauthn.exception.RegistrationFailedException
import dev.adminos.api.domain.identity.Ceremony
import dev.adminos.api.domain.identity.NewPasskeyCredential
import dev.adminos.api.domain.identity.Passkey
import dev.adminos.api.domain.identity.PasskeyException
import dev.adminos.api.domain.identity.PasskeyUse
import dev.adminos.api.domain.identity.PasskeyUser
import dev.adminos.api.domain.identity.WebAuthnCeremonies
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.util.Optional

/**
 * WebAuthn through Yubico's java-webauthn-server (AOS-617; BSD-2-Clause, 2.10.1 of 2026-10-09). The library checks
 * the challenge, the origin (exactly [appUrl]'s), the RP ID hash, the user-presence and user-verification flags,
 * the signature, and the signature counter. Its own models stay in this adapter.
 *
 * - RP ID = [appUrl]'s host (e.g. `adminos.tail1234.ts.net`), origin = [appUrl]'s origin; no subdomains, no other ports.
 * - User verification is **required** (biometric or PIN): a passkey alone, without its unlock, is not a factor.
 * - Attestation is not requested ("none"): we trust the owner's choice of authenticator, not a vendor list.
 */
class YubicoWebAuthn(appUrl: String, private val clock: Clock = Clock.systemUTC()) : WebAuthnCeremonies {

    private val uri = try { URI(appUrl.trim()) } catch (e: java.net.URISyntaxException) { throw IllegalArgumentException("APP_URL is not a URL", e) }
    val rpId: String = requireNotNull(uri.host) { "APP_URL needs a host for passkeys: $appUrl" }.lowercase()
    val origin: String = buildString {
        append(uri.scheme.lowercase()).append("://").append(rpId)
        if (uri.port != -1 && !((uri.scheme == "https" && uri.port == 443) || (uri.scheme == "http" && uri.port == 80))) append(':').append(uri.port)
    }

    private fun relyingParty(user: PasskeyUser, passkeys: List<Passkey>): RelyingParty = RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(rpId).name("AdminOS").build())
        .credentialRepository(OneUserCredentials(user, passkeys))
        .origins(setOf(origin))
        .allowOriginPort(false)
        .allowOriginSubdomain(false)
        .clock(clock)
        .build()

    override fun startRegistration(user: PasskeyUser, existing: List<Passkey>): Ceremony {
        val options = relyingParty(user, existing).startRegistration(
            StartRegistrationOptions.builder()
                .user(UserIdentity.builder().name(user.name).displayName(user.displayName).id(WebAuthnBytes(user.handle)).build())
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                    .residentKey(ResidentKeyRequirement.PREFERRED)
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .build())
                .timeout(TIMEOUT_MS)
                .build()
        )
        return Ceremony(options.toCredentialsCreateJson(), options.toJson())
    }

    override fun finishRegistration(user: PasskeyUser, existing: List<Passkey>, requestJson: String, responseJson: String): NewPasskeyCredential {
        val request = try { PublicKeyCredentialCreationOptions.fromJson(requestJson) } catch (e: IOException) { throw PasskeyException.Rejected("unreadable request") }
        val response = try { PublicKeyCredential.parseRegistrationResponseJson(responseJson) } catch (e: IOException) { throw PasskeyException.Rejected("unreadable response") }
        val result = try {
            relyingParty(user, existing).finishRegistration(FinishRegistrationOptions.builder().request(request).response(response).build())
        } catch (e: RegistrationFailedException) {
            throw PasskeyException.Rejected(e.message.orEmpty().take(120))
        } catch (e: IllegalArgumentException) {
            throw PasskeyException.Rejected("malformed response")
        }
        if (!result.isUserVerified) throw PasskeyException.Rejected("user verification is required")
        if (existing.any { it.credentialId == result.keyId.id.base64Url }) throw PasskeyException.Rejected("this passkey is already registered")
        return NewPasskeyCredential(
            credentialId = result.keyId.id.base64Url,
            publicKeyCose = result.publicKeyCose.base64Url,
            signatureCount = result.signatureCount,
            transports = result.keyId.transports.map { t -> t.map { it.id }.toSet() }.orElse(emptySet())
        )
    }

    override fun startAuthentication(user: PasskeyUser, passkeys: List<Passkey>): Ceremony {
        val request = relyingParty(user, passkeys).startAssertion(
            StartAssertionOptions.builder()
                .userHandle(WebAuthnBytes(user.handle))
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(TIMEOUT_MS)
                .build()
        )
        return Ceremony(request.toCredentialsGetJson(), request.toJson())
    }

    override fun finishAuthentication(user: PasskeyUser, passkeys: List<Passkey>, requestJson: String, responseJson: String): PasskeyUse {
        val request = try { AssertionRequest.fromJson(requestJson) } catch (e: IOException) { throw PasskeyException.Rejected("unreadable request") }
        val response = try { PublicKeyCredential.parseAssertionResponseJson(responseJson) } catch (e: IOException) { throw PasskeyException.Rejected("unreadable response") }
        val result = try {
            relyingParty(user, passkeys).finishAssertion(FinishAssertionOptions.builder().request(request).response(response).build())
        } catch (e: AssertionFailedException) {
            throw PasskeyException.Rejected(e.message.orEmpty().take(120))
        } catch (e: IllegalArgumentException) {
            throw PasskeyException.Rejected("malformed response")
        }
        if (!result.isSuccess) throw PasskeyException.Rejected("the signature didn't verify")
        if (!result.isUserVerified) throw PasskeyException.Rejected("user verification is required")
        if (!result.isSignatureCounterValid) throw PasskeyException.Rejected("the signature counter went backwards (a cloned authenticator?)")
        return PasskeyUse(result.credentialId.base64Url, result.signatureCount)
    }

    /** The library's view of our single user's passkeys, for one ceremony. */
    private class OneUserCredentials(private val user: PasskeyUser, private val passkeys: List<Passkey>) : CredentialRepository {
        private val handle = WebAuthnBytes(user.handle)

        private fun registered(p: Passkey) = RegisteredCredential.builder()
            .credentialId(WebAuthnBytes.fromBase64Url(p.credentialId))
            .userHandle(handle)
            .publicKeyCose(WebAuthnBytes.fromBase64Url(p.publicKeyCose))
            .signatureCount(p.signatureCount)
            .build()

        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> =
            if (username != user.name) emptySet() else passkeys.map { p ->
                PublicKeyCredentialDescriptor.builder().id(WebAuthnBytes.fromBase64Url(p.credentialId))
                    .transports(p.transports.map { AuthenticatorTransport.of(it) }.toSet()).build()
            }.toSet()

        override fun getUserHandleForUsername(username: String): Optional<WebAuthnBytes> =
            if (username == user.name) Optional.of(handle) else Optional.empty()

        override fun getUsernameForUserHandle(userHandle: WebAuthnBytes): Optional<String> =
            if (userHandle == handle) Optional.of(user.name) else Optional.empty()

        override fun lookup(credentialId: WebAuthnBytes, userHandle: WebAuthnBytes): Optional<RegisteredCredential> =
            if (userHandle != handle) Optional.empty()
            else Optional.ofNullable(passkeys.firstOrNull { it.credentialId == credentialId.base64Url }?.let(::registered))

        override fun lookupAll(credentialId: WebAuthnBytes): Set<RegisteredCredential> =
            passkeys.filter { it.credentialId == credentialId.base64Url }.map(::registered).toSet()
    }

    companion object {
        private const val TIMEOUT_MS = 120_000L
    }
}
