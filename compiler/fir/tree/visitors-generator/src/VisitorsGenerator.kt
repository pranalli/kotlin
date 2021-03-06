/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.fir.visitors.generator

import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.local.CoreLocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import org.jetbrains.kotlin.psi.KtFile
import java.io.File


val FIR_ELEMENT_CLASS_NAME = DataCollector.NameWithTypeParameters("FirElement")
const val VISITOR_PACKAGE = "org.jetbrains.kotlin.fir.visitors"
const val SIMPLE_VISITOR_NAME = "FirVisitor"
const val UNIT_VISITOR_NAME = "FirVisitorVoid"
const val PARAMETRIC_TRANSFORMER_NAME = "FirTransformer"
const val TRANSFORMER_RESULT_NAME = "CompositeTransformResult"

const val BASE_TRANSFORMED_TYPE_ANNOTATION_NAME = "BaseTransformedType"
const val VISITED_SUPERTYPE_ANNOTATION_NAME = "VisitedSupertype"

const val WARNING_GENERATED_FILE =
    """/** This file generated by :compiler:fir:tree:generateVisitors. DO NOT MODIFY MANUALLY! */"""

fun main(args: Array<String>) {

    val rootPath = File(args[0])
    val output = File(args[1])

    val packageDirectory = output.resolve(VISITOR_PACKAGE.replace('.', '/'))
    packageDirectory.mkdirs()

    withPsiSetup {
        val psiManager = PsiManager.getInstance(project)
        val vfm = VirtualFileManager.getInstance()


        val dataCollector = DataCollector()

        rootPath.walkTopDown()
            .filter {
                it.extension == "kt"
            }.map {
                (vfm.getFileSystem(StandardFileSystems.FILE_PROTOCOL) as CoreLocalFileSystem).findFileByIoFile(it)
            }.flatMap {
                SingleRootFileViewProvider(psiManager, it!!).allFiles.asSequence()
            }
            .filterIsInstance<KtFile>()
            .forEach(dataCollector::readFile)


        val data = dataCollector.computeResult()


        SimpleVisitorGenerator(data).runGenerator(packageDirectory.resolve("${SIMPLE_VISITOR_NAME}Generated.kt"))
        UnitVisitorGenerator(data).runGenerator(packageDirectory.resolve("${UNIT_VISITOR_NAME}Generated.kt"))
        ParametricTransformerGenerator(data).runGenerator(packageDirectory.resolve("${PARAMETRIC_TRANSFORMER_NAME}Generated.kt"))
    }
}

fun AbstractVisitorGenerator.runGenerator(file: File) {
    file.writeText(StringUtil.convertLineSeparators(this.generate(), System.lineSeparator()))
}


val String.classNameWithoutFir get() = this.removePrefix("Fir")


fun DataCollector.ReferencesData.walkHierarchyTopDown(
    from: DataCollector.NameWithTypeParameters,
    l: (p: DataCollector.NameWithTypeParameters, e: DataCollector.NameWithTypeParameters) -> Unit
) {
    val referents = back[from] ?: return
    for (referent in referents) {
        l(from, referent)
        walkHierarchyTopDown(referent, l)
    }
}


