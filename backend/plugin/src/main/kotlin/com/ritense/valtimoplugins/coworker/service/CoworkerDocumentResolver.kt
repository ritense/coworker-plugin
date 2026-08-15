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

package com.ritense.valtimoplugins.coworker.service

import com.ritense.resource.domain.MetadataType
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimoplugins.coworker.domain.DocumentData
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Base64

/**
 * Turns a Valtimo temporary-resource id into the [DocumentData] attachment sent
 * along with a chat-request.
 *
 * A resource id is how files travel between plugin actions in Valtimo: a form
 * upload or the Documenten API plugin's download action puts one in a process
 * variable, which is then passed to this plugin as `pv:resourceId`. The file's
 * bytes are base64-encoded into the CloudEvent, so the whole document goes over
 * RabbitMQ as one message — hence [maxDocumentSizeBytes].
 */
open class CoworkerDocumentResolver(
    private val temporaryResourceStorageService: TemporaryResourceStorageService,
    private val maxDocumentSizeBytes: Long,
) {
    /**
     * Reads the resource behind [resourceId] as a single-element document list, or
     * `null` when no resource id was configured (the common case — a document is
     * optional).
     */
    open fun resolve(resourceId: String?): List<DocumentData>? {
        if (resourceId.isNullOrBlank()) return null

        val content = readContent(resourceId)
        require(content.isNotEmpty()) { "Document '$resourceId' is empty" }
        // readContent reads one byte past the limit so oversized files are rejected
        // here instead of being pulled into memory in full.
        require(content.size <= maxDocumentSizeBytes) {
            "Document '$resourceId' is larger than the maximum of $maxDocumentSizeBytes bytes " +
                "(valtimo.coworker.max-document-size)"
        }

        val metadata = readMetadata(resourceId)
        val document =
            DocumentData(
                fileName = metadata[MetadataType.FILE_NAME.key]?.toString()?.takeIf { it.isNotBlank() } ?: resourceId,
                contentType =
                    metadata[MetadataType.CONTENT_TYPE.key]?.toString()?.takeIf { it.isNotBlank() }
                        ?: DEFAULT_CONTENT_TYPE,
                content = Base64.getEncoder().encodeToString(content),
            )

        logger.debug {
            "Attaching document '${document.fileName}' (${document.contentType}, ${content.size} bytes) " +
                "from resource '$resourceId'"
        }
        return listOf(document)
    }

    private fun readContent(resourceId: String): ByteArray =
        try {
            temporaryResourceStorageService.getResourceContentAsInputStream(resourceId).use {
                it.readNBytes(readLimit())
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not read document for resource id '$resourceId'", e)
        }

    private fun readMetadata(resourceId: String): Map<String, Any> =
        try {
            temporaryResourceStorageService.getResourceMetadata(resourceId)
        } catch (e: Exception) {
            // Metadata is only used for labelling; a document without it is still usable.
            logger.warn(e) { "Could not read metadata for resource id '$resourceId'" }
            emptyMap()
        }

    /** One byte past the maximum, so an oversized document is detectable. */
    private fun readLimit(): Int = (maxDocumentSizeBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
