/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.valtimoplugins.coworker

import com.ritense.resource.domain.MetadataType
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimoplugins.coworker.service.CoworkerDocumentResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.util.Base64

class CoworkerDocumentResolverTest : BaseTest() {
    private val storageService = mock<TemporaryResourceStorageService>()
    private val resolver = CoworkerDocumentResolver(storageService, MAX_SIZE)

    private fun stubResource(
        resourceId: String,
        content: ByteArray,
        metadata: Map<String, Any> = emptyMap(),
    ) {
        whenever(storageService.getResourceContentAsInputStream(resourceId))
            .thenReturn(ByteArrayInputStream(content))
        whenever(storageService.getResourceMetadata(resourceId)).thenReturn(metadata)
    }

    @Test
    fun `reads a document into a base64 attachment`() {
        stubResource(
            "res-1",
            "factuur".toByteArray(),
            mapOf(
                MetadataType.FILE_NAME.key to "factuur.pdf",
                MetadataType.CONTENT_TYPE.key to "application/pdf",
            ),
        )

        val documents = resolver.resolve("res-1")

        assertThat(documents).hasSize(1)
        val document = documents!!.first()
        assertThat(document.fileName).isEqualTo("factuur.pdf")
        assertThat(document.contentType).isEqualTo("application/pdf")
        assertThat(String(Base64.getDecoder().decode(document.content))).isEqualTo("factuur")
    }

    @Test
    fun `returns null without a resource id and never touches storage`() {
        assertThat(resolver.resolve(null)).isNull()
        assertThat(resolver.resolve("  ")).isNull()

        verify(storageService, never()).getResourceContentAsInputStream(any())
    }

    @Test
    fun `falls back to the resource id and a generic content type when metadata is missing`() {
        stubResource("res-2", "data".toByteArray())

        val document = resolver.resolve("res-2")!!.first()

        assertThat(document.fileName).isEqualTo("res-2")
        assertThat(document.contentType).isEqualTo("application/octet-stream")
    }

    @Test
    fun `still attaches the document when metadata cannot be read`() {
        whenever(storageService.getResourceContentAsInputStream("res-3"))
            .thenReturn(ByteArrayInputStream("data".toByteArray()))
        whenever(storageService.getResourceMetadata("res-3")).thenThrow(RuntimeException("no metadata"))

        val document = resolver.resolve("res-3")!!.first()

        assertThat(document.fileName).isEqualTo("res-3")
    }

    @Test
    fun `rejects a document larger than the maximum`() {
        stubResource("res-4", ByteArray(MAX_SIZE.toInt() + 1))

        val ex = runCatching { resolver.resolve("res-4") }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex).hasMessageContaining("max-document-size")
    }

    @Test
    fun `accepts a document of exactly the maximum size`() {
        stubResource("res-5", ByteArray(MAX_SIZE.toInt()))

        assertThat(resolver.resolve("res-5")).hasSize(1)
    }

    @Test
    fun `rejects an empty document`() {
        stubResource("res-6", ByteArray(0))

        val ex = runCatching { resolver.resolve("res-6") }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex).hasMessageContaining("empty")
    }

    @Test
    fun `reports the resource id when the document cannot be read`() {
        whenever(storageService.getResourceContentAsInputStream("res-7"))
            .thenThrow(RuntimeException("gone"))

        val ex = runCatching { resolver.resolve("res-7") }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex).hasMessageContaining("res-7")
    }

    private companion object {
        const val MAX_SIZE = 32L
    }
}
