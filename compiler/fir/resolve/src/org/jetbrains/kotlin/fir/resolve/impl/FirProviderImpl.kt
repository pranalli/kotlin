/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.impl

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.FirProvider
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.ConeSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.FirSymbolOwner
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class FirProviderImpl(val session: FirSession) : FirProvider {

    override fun getFirClassifierBySymbol(symbol: ConeSymbol): FirNamedDeclaration? {
        return when (symbol) {
            is FirBasedSymbol<*> -> symbol.fir as? FirNamedDeclaration
            is ConeClassLikeSymbol -> getFirClassifierByFqName(symbol.classId)
            else -> error("!")
        }
    }

    override fun getSymbolByFqName(classId: ClassId): ConeSymbol? {
        return (getFirClassifierByFqName(classId) as? FirSymbolOwner<*>)?.symbol
    }

    override fun getCallableSymbols(ownerId: ClassId, name: Name): List<ConeSymbol> {
        val firClassifier = getFirClassifierByFqName(ownerId) ?: return emptyList()

        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getFirClassifierContainerFile(fqName: ClassId): FirFile {
        return classifierContainerFileMap[fqName] ?: error("Couldn't find container for $fqName")
    }

    fun recordFile(file: FirFile) {
        val packageName = file.packageFqName
        fileMap.merge(packageName, listOf(file)) { a, b -> a + b }

        file.acceptChildren(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {}

            var containerFqName: FqName = FqName.ROOT

            override fun visitRegularClass(regularClass: FirRegularClass) {
                val fqName = containerFqName.child(regularClass.name)
                val classId = ClassId(packageName, fqName, false)
                classifierMap[classId] = regularClass
                classifierContainerFileMap[classId] = file

                containerFqName = fqName
                regularClass.acceptChildren(this)
                containerFqName = fqName.parent()
            }

            override fun visitTypeAlias(typeAlias: FirTypeAlias) {
                val fqName = containerFqName.child(typeAlias.name)
                val classId = ClassId(packageName, fqName, false)
                classifierMap[classId] = typeAlias
                classifierContainerFileMap[classId] = file
            }

            override fun visitCallableMember(callableMember: FirCallableMember) {
                val memberId = Pair(ClassId(packageName, containerFqName, false), callableMember.name)
                callableMap.merge(memberId, listOf(callableMember)) { a, b -> a + b }
            }
        })
    }

    private val fileMap = mutableMapOf<FqName, List<FirFile>>()
    private val classifierMap = mutableMapOf<ClassId, FirMemberDeclaration>()
    private val classifierContainerFileMap = mutableMapOf<ClassId, FirFile>()
    private val callableMap = mutableMapOf<Pair<ClassId, Name>, List<FirNamedDeclaration>>()

    override fun getFirFilesByPackage(fqName: FqName): List<FirFile> {
        return fileMap[fqName].orEmpty()
    }

    override fun getFirClassifierByFqName(fqName: ClassId): FirMemberDeclaration? {
        return classifierMap[fqName]
    }

}