/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.scopes.impl

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirNamedDeclaration
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.scopes.FirPosition
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.symbols.ConeSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

class FirClassDeclaredMemberScope(
    val klass: FirClass,
    val classId: ClassId,
    private val session: FirSession
) : FirScope {
    private val declarationsByName: Map<Name, List<FirNamedDeclaration>> =
        klass.declarations.filterIsInstance<FirNamedDeclaration>().groupBy { it.name }

    override fun processClassifiersByName(
        name: Name,
        position: FirPosition,
        processor: (ConeSymbol) -> Boolean
    ): Boolean {
        val declarations = declarationsByName[name] ?: return true
        if (declarations.isEmpty()) return true
        val provider = FirSymbolProvider.getInstance(session)
        for (declaration in declarations) {
            val symbols = provider.getCallableSymbols(classId, declaration.name)
            for (symbol in symbols) {
                if (!processor(symbol)) {
                    return false
                }
            }
        }
        return true
    }
}