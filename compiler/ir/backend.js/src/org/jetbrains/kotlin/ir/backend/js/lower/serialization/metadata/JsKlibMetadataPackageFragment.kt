/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.serialization.metadata

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.SourceFile
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.serialization.deserialization.*
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.storage.getValue
import org.jetbrains.kotlin.utils.JsMetadataVersion

class JsKlibMetadataPackageFragment(
    fqName: FqName,
    storageManager: StorageManager,
    module: ModuleDescriptor,
    proto: ProtoBuf.PackageFragment,
    header: JsKlibMetadataProtoBuf.Header,
    metadataVersion: JsMetadataVersion,
    configuration: DeserializationConfiguration
) : DeserializedPackageFragmentImpl(
    fqName, storageManager, module, proto, metadataVersion, JsContainerSource(fqName, header, configuration)
) {
    val fileMap: Map<Int, FileHolder> =
        proto.getExtension(JsKlibMetadataProtoBuf.packageFragmentFiles).fileList.withIndex().associate { (index, file) ->
            (if (file.hasId()) file.id else index) to FileHolder(file.annotationList)
        }

    private lateinit var annotationDeserializer: AnnotationDeserializer

    override fun initialize(components: DeserializationComponents) {
        super.initialize(components)
        this.annotationDeserializer = AnnotationDeserializer(components.moduleDescriptor, components.notFoundClasses)
    }

    fun getContainingFileAnnotations(descriptor: DeclarationDescriptor): List<AnnotationDescriptor> {
        if (DescriptorUtils.getParentOfType(descriptor, PackageFragmentDescriptor::class.java) != this) {
            throw IllegalArgumentException("Provided descriptor $descriptor does not belong to this package $this")
        }
        val fileId = descriptor.extractFileId()

        return fileId?.let { fileMap[it] }?.annotations.orEmpty()
    }

    inner class FileHolder(private val annotationsProto: List<ProtoBuf.Annotation>) {
        val annotations: List<AnnotationDescriptor> by storageManager.createLazyValue {
            annotationsProto.map { annotationDeserializer.deserializeAnnotation(it, nameResolver) }
        }
    }

    class JsContainerSource(
        private val fqName: FqName,
        header: JsKlibMetadataProtoBuf.Header,
        configuration: DeserializationConfiguration
    ) : DeserializedContainerSource {
        val annotations: List<ClassId> =
            if (header.annotationCount == 0) emptyList()
            else NameResolverImpl(header.strings, header.qualifiedNames).let { nameResolver ->
                // TODO: read arguments of module annotations
                header.annotationList.map { annotation -> nameResolver.getClassId(annotation.id) }
            }

        // TODO
        override fun getContainingFile(): SourceFile = SourceFile.NO_SOURCE_FILE

        // This is null because we look for incompatible libraries in dependencies in the beginning of the compilation anyway,
        // and refuse to compile against them completely
        override val incompatibility: IncompatibleVersionErrorData<*>?
            get() = null

        override val isPreReleaseInvisible: Boolean =
            configuration.reportErrorsOnPreReleaseDependencies && (header.flags and 1) != 0

        override val presentableString: String
            get() = "Package '$fqName'"
    }
}
