/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.serialization.metadata

import org.jetbrains.kotlin.utils.JsMetadataVersion

internal class PackagesWithHeaderMetadata(
    val header: ByteArray,
    val packages: List<ByteArray>,
    val metadataVersion: JsMetadataVersion
)
