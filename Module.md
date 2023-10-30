# Module EUDI SD-JWT

`eudi-lib-jvm-sdjwt-kt` offers a DSL (domain-specific language) for defining how a set of claims should be made selectively
disclosable.

Library implements [SD-JWT draft5](https://www.ietf.org/archive/id/draft-ietf-oauth-selective-disclosure-jwt-05.html)
is implemented in Kotlin, targeting JVM.

Library's SD-JWT DSL leverages the DSL provided by
[KotlinX Serialization](https://github.com/Kotlin/kotlinx.serialization) library for defining JSON elements

## eu.europa.ec.eudi.sdjwt

### Use cases supported

- [Issuance](#issuance): As an Issuer use the library to issue a SD-JWT
- [Holder Verification](#holder-verification): As Holder verify a SD-JWT issued by an Issuer
- [Presentation Verification](#presentation-verification): As a Verifier verify SD-JWT in simple or in Enveloped Format
- [Recreate initial claims](#recreate-original-claims): Given a SD-JWT recreate the original claims

#### Issuance

To issue a SD-JWT, an `Issuer` should have:

- Decided on how the issued claims will be selectively disclosed (check [DSL examples](#dsl-examples))
- Whether to use decoy digests or not
- An appropriate signing key pair
- optionally, decided if and how will include holder's public key to the SD-JWT

In the example bellow, Issuer decides to issue an SD-JWT as follows:

- Includes in plain standard JWT claims (`sub`,`iss`, `iat`, `exp`)
- Makes selectively disclosable a claim named `address` using structured disclosure. This allows to individually
  disclose every subclaim of `address`
- Uses his RSA key pair to sign the SD-JWT

```kotlin
import com.nimbusds.jose.crypto.RSASigner
import com.nimbusds.jose.jwk.RSAKey
import eu.europa.ec.eudi.sdjwt.*

val issuerKeyPair: RSAKey = TODO("Omitted")
val sdJwtSpec = sdJwt {
  plain {
      sub("6c5c0a49-b589-431d-bae7-219122a9ec2c")
      iss("https://example.com/issuer")
      iat(1516239022)
      exp(1735689661)
  }
  structured("address") {
    sd {
      put("street_address", "Schulstr. 12")
      put("locality", "Schulpforta")
      put("region", "Sachsen-Anhalt")
      put("country", "DE")
    }
  }
}
val issuer = SdJwtIssuer.nimbus(signer = RSASigner(issuerKeyPair), signAlgorithm = JWSAlgorithm.RS256)
val sdJwt: String = issuer.issue(sdJwtSpec).getOrThrow().serialize()
```

Please check [KeyBindingTest](src/test/kotlin/eu/europa/ec/eudi/sdjwt/KeyBindingTest.kt) for a more advanced
issuance scenario, including adding to the SD-JWT, holder public key, to leverage key binding.

#### Holder Verification

In this case, the SD-JWT is expected to be in serialized form.

`Holder` must know:

- the public key of the `Issuer` and the algorithm used by the Issuer to sign the SD-JWT

```kotlin
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.*
import eu.europa.ec.eudi.sdjwt.*

val unverifiedSdJwt: String = "..."
val issuerPubKey: ECPublicKey = TODO("Omitted")
val jwtSignatureVerifier = ECDSAVerifier(issuerPubKey).asJwtVerifier()

val issued: SdJwt.Issuance<JwtAndClaims> =
  SdJwtVerifier.verifyIssuance(
    jwtSignatureVerifier = jwtSignatureVerifier,
    unverifiedSdJwt = unverifiedSdJwt
  ).getOrThrow()
val (jwtAndClaims, disclosures) = issued
```

#### Presentation Verification

##### In simple (not enveloped) format

In this case, the SD-JWT is expected to be in Combined Presentation format.
Verifier should know the public key of the Issuer and the algorithm used by the Issuer
to sign the SD-JWT. Also, if verification includes Key Binding, the Verifier must also
know a how the public key of the Holder was included in the SD-JWT and which algorithm
the Holder used to sign the `Key Binding JWT`

```kotlin
import eu.europa.ec.eudi.sdjwt.*
import com.nimbusds.jose.jwk.*
import com.nimbusds.jose.crypto.ECDSAVerifier

val unverifiedSdJwt: String = "..."
val issuerPubKey: ECPublicKey = TODO("Omitted")
val jwtSignatureVerifier = ECDSAVerifier(issuerPubKey).asJwtVerifier()

//
// The following demonstrates verification of presentation
// without Key Binding JWT
//
val sdJwt: SdJwt.Presentation<JwtAndClaims, JwtAndClaims> =
    SdJwtVerifier.verifyPresentation(
      jwtSignatureVerifier = jwtSignatureVerifier,
      keyBindingVerifier = KeyBindingVerifier.MustNotBePresent,
      unverifiedSdJwt = unverifiedSdJwt
    ).getOrThrow()

```

Please check [KeyBindingTest](src/test/kotlin/eu/europa/ec/eudi/sdjwt/KeyBindingTest.kt) for a more advanced
presentation scenario which includes key binding

##### In enveloped format

In this case, the SD-JWT is expected to be in envelope format.
Verifier should know
- the public key of the Issuer and the algorithm used by the Issuer to sign the SD-JWT.
- the public key and the signing algorithm used by the Holder to sign the envelope JWT, since the envelope acts
  like a proof of possession (replacing the key binding JWT)


```kotlin
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.*
import eu.europa.ec.eudi.sdjwt.*
import java.time.Clock
import java.time.Duration

val unverifiedEnvelopeJwt: String = "..."
// This is the SD-JWT issuer's key
val issuerPubKey: ECPublicKey = TODO("Omitted")
val jwtSignatureVerifier = ECDSAVerifier(issuerPubKey).asJwtVerifier()
// This is the envelope JWT issuer (the holder) key 
val holderPubKey: ECPublicKey = TODO("Omitted")
val envelopeJwtVerifier = ECDSAVerifier(holderPubKey).asJwtVerifier()

val sdJwt : SdJwt.Presentation<JwtAndClaims, Nothing> = 
    SdJwtVerifier.verifyEnvelopedPresentation(
        sdJwtSignatureVerifier = jwtSignatureVerifier,
        envelopeJwtVerifier = envelopeJwtVerifier,
        clock = Clock.systemDefaultZone(),
        iatOffset = Duration.ofSeconds(10),
        expectedAudience = "verifier's client id",
        unverifiedEnvelopeJwt
    ).getOrThrow()
```

#### Recreate original claims

Given an `SdJwt`, either issuance or presentation, the original claims used to produce the SD-JWT can be
recreated. This includes the claims that are always disclosed (included in the JWT part of the SD-JWT) having
the digests replaced by selectively disclosable claims found in disclosures.

```kotlin

val iss = "Issuer"
val issuerKeyPair: RSAKey = TODO("Omitted")
val sdJwt: SdJwt.Issuance<NimbusSignedJWT> =
  signedSdJwt(signer = RSASigner(issuerKeyPair), signAlgorithm = JWSAlgorithm.RS256) {
        plain {
            sub("6c5c0a49-b589-431d-bae7-219122a9ec2c")
            iss("https://example.com/issuer")
            iat(1516239022)
            exp(1735689661)
        }
        structured("address") {
            sd {
                put("street_address", "Schulstr. 12")
                put("locality", "Schulpforta")
                put("region", "Sachsen-Anhalt")
                put("country", "DE")
            }
        }
    }
val claims: Claims = sdJwt.recreateClaims { jwt -> jwt.asClaims() }
```

The claims contents would be

```json
{
  "sub": "6c5c0a49-b589-431d-bae7-219122a9ec2c",
  "iss": "https://example.com/issuer",
  "iat": 1516239022,
  "exp": 1735689661,
  "address": {
    "street_address": "Schulstr. 12",
    "locality": "Schulpforta",
    "region": "Sachsen-Anhalt",
    "country": "DE"
  }
}
```