/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.symbols

import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.Name

interface ConeOwnerSymbol : ConeSymbol

interface ConeCallableSymbol : ConeSymbol {
    val owner: ConeOwnerSymbol

    val name: Name
}

interface ConeVariableSymbol : ConeCallableSymbol

interface ConeFunctionSymbol : ConeCallableSymbol, ConeOwnerSymbol {
    val parameters: List<ConeKotlinType>
}