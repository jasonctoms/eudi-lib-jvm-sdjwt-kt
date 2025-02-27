/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.europa.ec.eudi.sdjwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.jwk.AsymmetricJWK
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.SignedJWT
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StandardSerializationTest {

    private val issuer by lazy {
        val issuerKey = ECKeyGenerator(Curve.P_256).generate()
        SdJwtIssuer.nimbus(signer = ECDSASigner(issuerKey), signAlgorithm = JWSAlgorithm.ES256)
    }

    private val keyBindingSigner: KeyBindingSigner by lazy {
        object : KeyBindingSigner {
            val holderKey = ECKeyGenerator(Curve.P_256).generate()
            private val signer = ECDSASigner(holderKey)
            override val signAlgorithm: JWSAlgorithm = JWSAlgorithm.ES256
            override val publicKey: AsymmetricJWK = holderKey.toPublicJWK()
            override fun getJCAContext(): JCAContext = signer.jcaContext
            override fun sign(p0: JWSHeader?, p1: ByteArray?): Base64URL = signer.sign(p0, p1)
        }
    }

    @Test
    fun `An SD-JWT without disclosures or KBJWT should end in a single ~`() {
        val sdJwtSpec = sdJwt {
            plain {
                put("foo", "bar")
            }
        }
        val sdJwt = issuer.issue(sdJwtSpec).getOrThrow()
        val expected =
            buildString {
                append(sdJwt.jwt.serialize())
                append("~")
            }
        val actual = sdJwt.serialize()
        assertEquals(expected, actual)
    }

    @Test
    fun `An SD-JWT with disclosures and without KBJWT should end in a single ~`() {
        val sdJwtSpec = sdJwt {
            sd {
                put("foo", "bar")
            }
        }
        val sdJwt = issuer.issue(sdJwtSpec).getOrThrow()
        val expected =
            buildString {
                append(sdJwt.jwt.serialize())
                append("~")
                for (d in sdJwt.disclosures) {
                    append(d.value)
                    append("~")
                }
            }
        val actual = sdJwt.serialize()
        assertEquals(expected, actual)
    }

    @Test
    fun `An SD-JWT without disclosures with KBJWT should not end in ~`() {
        val sdJwtSpec = sdJwt {
            plain {
                put("foo", "bar")
            }
        }
        val issuedSdJwt = issuer.issue(sdJwtSpec).getOrThrow()
        val sdJwt = issuedSdJwt.present()
        assertNotNull(sdJwt)

        val (pSdJwt, kbJwt) = sdJwt.serializedAndKeyBinding(
            { it.serialize() },
            HashAlgorithm.SHA_256,
            keyBindingSigner,
        ) {}
        val actual = sdJwt.serializeWithKeyBinding(HashAlgorithm.SHA_256, keyBindingSigner) {}
        assertTrue { actual.startsWith(pSdJwt) }
        assertTrue { actual.count { it == '~' } == 1 }
        val (pSdJwt1, disclosures, kbJwt1) = StandardSerialization.parse(actual)
        assertTrue { disclosures.isEmpty() }

        // Cannot use string equality due to differences in signatures
        assertEquals(SignedJWT.parse(pSdJwt).jwtClaimsSet, SignedJWT.parse(pSdJwt1).jwtClaimsSet)
        assertNotNull(kbJwt1)
        assertEquals(SignedJWT.parse(kbJwt).jwtClaimsSet, SignedJWT.parse(kbJwt1).jwtClaimsSet)
    }

    @Test
    fun `An SD-JWT with disclosures and KBJWT should not end in ~`() {
        val sdJwtSpec = sdJwt {
            sd {
                put("foo", "bar")
            }
        }
        val issuedSdJwt = issuer.issue(sdJwtSpec).getOrThrow()
        val sdJwt = issuedSdJwt.present()
        assertNotNull(sdJwt)

        val (pSdJwt, kbJwt) = sdJwt.serializedAndKeyBinding(
            { it.serialize() },
            HashAlgorithm.SHA_256,
            keyBindingSigner,
        ) {}
        val actual = sdJwt.serializeWithKeyBinding(HashAlgorithm.SHA_256, keyBindingSigner) {}
        assertTrue { actual.startsWith(pSdJwt) }
        assertTrue { actual.count { it == '~' } == 2 }
        val (pSdJwt1, disclosures, kbJwt1) = StandardSerialization.parse(actual)
        assertEquals(1, disclosures.size)

        // Cannot use string equality due to differences in signatures
        assertEquals(SignedJWT.parse(pSdJwt).jwtClaimsSet, SignedJWT.parse(pSdJwt1).jwtClaimsSet)
        assertNotNull(kbJwt1)
        assertEquals(SignedJWT.parse(kbJwt).jwtClaimsSet, SignedJWT.parse(kbJwt1).jwtClaimsSet)
    }
}
