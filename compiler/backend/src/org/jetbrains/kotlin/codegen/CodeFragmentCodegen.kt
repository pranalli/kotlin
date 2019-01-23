/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen

import com.intellij.openapi.util.Key
import org.jetbrains.kotlin.codegen.CalculatedCodeFragmentCodegenInfo.CalculatedParameter
import org.jetbrains.kotlin.codegen.CodeFragmentCodegenInfo.IParameter
import org.jetbrains.kotlin.codegen.binding.MutableClosure
import org.jetbrains.kotlin.codegen.context.*
import org.jetbrains.kotlin.codegen.context.LocalLookup.*
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.codegen.state.KotlinTypeMapper
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOrigin.Companion.NO_ORIGIN
import org.jetbrains.kotlin.resolve.jvm.diagnostics.OtherOrigin
import org.jetbrains.org.objectweb.asm.Opcodes.*
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension.Context as InCo

class CodeFragmentCodegenInfo(
    val classDescriptor: ClassDescriptor,
    val methodDescriptor: FunctionDescriptor,
    val parameters: List<IParameter>,
    val interceptor: Interceptor
) {
    val classType: Type = Type.getObjectType(classDescriptor.name.asString())

    interface IParameter {
        val descriptor: DeclarationDescriptor
    }

    interface Interceptor {
        fun generateReference(expression: KtExpression, typeMapper: KotlinTypeMapper): StackValue?
        fun generateOuterThis(containingClass: ClassDescriptor, typeMapper: KotlinTypeMapper): StackValue?
        fun generateExtensionThis(callable: CallableDescriptor, typeMapper: KotlinTypeMapper): StackValue?
    }
}

private class CalculatedCodeFragmentCodegenInfo(val parameters: List<CalculatedParameter>, val returnAsmType: Type) {
    data class CalculatedParameter(
        val parameter: IParameter,
        val asmType: Type,
        val parameterDescriptor: ValueParameterDescriptor,
        val index: Int
    )
}

private class CodeFragmentContext(
    typeMapper: KotlinTypeMapper,
    contextDescriptor: ClassDescriptor,
    parentContext: CodegenContext<*>?,
    private val calculatedInfo: CalculatedCodeFragmentCodegenInfo
) : ScriptLikeContext(typeMapper, contextDescriptor, parentContext) {
    private val localLookup = object : LocalLookup {
        override fun isLocal(descriptor: DeclarationDescriptor?): Boolean {
            return calculatedInfo.parameters.any { descriptor == it.parameter.descriptor || descriptor == it.parameterDescriptor }
        }
    }

    override fun getOuterReceiverExpression(prefix: StackValue?, thisOrOuterClass: ClassDescriptor): StackValue {
        for ((parameter, asmType, _, index) in calculatedInfo.parameters) {
            if (parameter.descriptor == thisOrOuterClass) {
                return StackValue.local(index, asmType, thisOrOuterClass.defaultType)
            }
        }

        throw IllegalStateException("Can not generate outer receiver value for $thisOrOuterClass")
    }

    override fun captureVariable(closure: MutableClosure, target: DeclarationDescriptor): StackValue? {
        var index = 0
        @Suppress("UseWithIndex")
        for ((parameter, _, parameterDescriptor) in calculatedInfo.parameters) {
            if (parameter.descriptor == target) {
                // Value is already captured
                closure.captureVariables[parameterDescriptor]?.let { return it.innerValue }

                // Capture new value
                val closureAsmType = typeMapper.mapType(closure.closureClass)
                return LocalLookupCase.VAR.innerValue(parameterDescriptor, localLookup, state, closure, closureAsmType)
            }
            index++
        }

        return null
    }
}

class CodeFragmentCodegen private constructor(
    private val codeFragment: KtCodeFragment,
    private val info: CodeFragmentCodegenInfo,
    private val calculatedInfo: CalculatedCodeFragmentCodegenInfo,
    private val classContext: CodeFragmentContext,
    state: GenerationState,
    builder: ClassBuilder
) : MemberCodegen<KtCodeFragment>(state, null, classContext, codeFragment, builder) {
    private val methodDescriptor = info.methodDescriptor

    override fun generateDeclaration() {
        v.defineClass(
            codeFragment,
            state.classFileVersion,
            ACC_PUBLIC or ACC_SUPER,
            info.classType.internalName,
            null,
            "java/lang/Object",
            emptyArray()
        )
    }

    override fun generateBody() {
        genConstructor()
        genMethod(classContext.intoFunction(methodDescriptor))
    }

    override fun generateKotlinMetadataAnnotation() {
        writeSyntheticClassMetadata(v, state)
    }

    private fun genConstructor() {
        val mv = v.newMethod(NO_ORIGIN, ACC_PUBLIC, "<init>", "()V", null, null)

        if (state.classBuilderMode.generateBodies) {
            mv.visitCode()

            val iv = InstructionAdapter(mv)
            iv.load(0, info.classType)
            iv.invokespecial("java/lang/Object", "<init>", "()V", false)
            iv.areturn(Type.VOID_TYPE)
        }

        mv.visitMaxs(-1, -1)
        mv.visitEnd()
    }

    private fun genMethod(methodContext: MethodContext) {
        val returnType = calculatedInfo.returnAsmType
        val parameters = calculatedInfo.parameters

        val methodDesc = Type.getMethodDescriptor(returnType, *parameters.map { it.asmType }.toTypedArray())

        val mv = v.newMethod(
            OtherOrigin(codeFragment, methodContext.functionDescriptor),
            ACC_PUBLIC or ACC_STATIC,
            methodDescriptor.name.asString(), methodDesc,
            null, null
        )

        if (state.classBuilderMode.generateBodies) {
            mv.visitCode()

            val frameMap = FrameMap()
            parameters.forEach { (parameter, asmType) -> frameMap.enter(parameter.descriptor, asmType) }

            val codegen = object : ExpressionCodegen(mv, frameMap, returnType, methodContext, state, this) {
                override fun generateThisOrOuter(calleeContainingClass: ClassDescriptor, isSuper: Boolean): StackValue {
                    info.interceptor.generateOuterThis(calleeContainingClass, typeMapper)?.let { return it }
                    return super.generateThisOrOuter(calleeContainingClass, isSuper)
                }

                override fun findCapturedValue(descriptor: DeclarationDescriptor): StackValue? {
                    for ((_, asmType, parameterDescriptor, index) in parameters) {
                        if (parameterDescriptor == descriptor) {
                            return StackValue.local(index, asmType, parameterDescriptor.type)
                        }
                    }
                    return super.findCapturedValue(descriptor)
                }

                override fun generateExtensionReceiver(descriptor: CallableDescriptor): StackValue {
                    info.interceptor.generateExtensionThis(descriptor, typeMapper)?.let { return it }
                    return super.generateExtensionReceiver(descriptor)
                }

                override fun visitNonIntrinsicSimpleNameExpression(
                    expression: KtSimpleNameExpression, receiver: StackValue,
                    descriptor: DeclarationDescriptor, resolvedCall: ResolvedCall<*>?, isSyntheticField: Boolean
                ): StackValue {
                    info.interceptor.generateReference(expression, typeMapper)?.let { return it }
                    return super.visitNonIntrinsicSimpleNameExpression(expression, receiver, descriptor, resolvedCall, isSyntheticField)
                }

                override fun visitThisExpression(expression: KtThisExpression, receiver: StackValue?): StackValue {
                    info.interceptor.generateReference(expression, typeMapper)?.let { return it }
                    return super.visitThisExpression(expression, receiver)
                }
            }

            codegen.gen(codeFragment.getContentElement(), returnType)

            val iv = InstructionAdapter(mv)
            iv.areturn(returnType)

            parameters.asReversed().forEach { (parameter) -> frameMap.leave(parameter.descriptor) }
        }

        mv.visitMaxs(-1, -1)
        mv.visitEnd()
    }

    companion object {
        private val INFO_USERDATA_KEY = Key.create<CodeFragmentCodegenInfo>("CODE_FRAGMENT_CODEGEN_INFO")

        fun setCodeFragmentInfo(codeFragment: KtCodeFragment, info: CodeFragmentCodegenInfo) {
            codeFragment.putUserData(INFO_USERDATA_KEY, info)
        }

        @JvmStatic
        fun getCodeFragmentInfo(codeFragment: KtCodeFragment): CodeFragmentCodegenInfo {
            return codeFragment.getUserData(INFO_USERDATA_KEY) ?: error("Codegen info user data is not set")
        }

        @JvmStatic
        fun createCodegen(
            declaration: KtCodeFragment,
            state: GenerationState,
            parentContext: CodegenContext<*>
        ): CodeFragmentCodegen {
            val info = getCodeFragmentInfo(declaration)
            val classDescriptor = info.classDescriptor
            val builder = state.factory.newVisitor(OtherOrigin(declaration, classDescriptor), info.classType, declaration.containingFile)
            val calculatedInfo = calculateInfo(info, state.typeMapper)
            val classContext = CodeFragmentContext(state.typeMapper, classDescriptor, parentContext, calculatedInfo)
            return CodeFragmentCodegen(declaration, info, calculatedInfo, classContext, state, builder)
        }

        private fun calculateInfo(info: CodeFragmentCodegenInfo, typeMapper: KotlinTypeMapper): CalculatedCodeFragmentCodegenInfo {
            val methodSignature = typeMapper.mapSignatureSkipGeneric(info.methodDescriptor)
            require(info.parameters.size == methodSignature.valueParameters.size)
            require(info.parameters.size == info.methodDescriptor.valueParameters.size)

            var stackIndex = 0
            var parameterIndex = 0
            val parameters = mutableListOf<CalculatedParameter>()

            @Suppress("UseWithIndex")
            for ((infoParameter, asmParameter) in info.parameters.zip(methodSignature.valueParameters)) {
                val asmType = getSharedTypeIfApplicable(infoParameter.descriptor, typeMapper) ?: asmParameter.asmType
                val parameterDescriptor = info.methodDescriptor.valueParameters[parameterIndex]
                parameters += CalculatedParameter(infoParameter, asmType, parameterDescriptor, stackIndex)
                stackIndex += if (asmType == Type.DOUBLE_TYPE || asmType == Type.LONG_TYPE) 2 else 1
                parameterIndex++
            }

            return CalculatedCodeFragmentCodegenInfo(parameters, methodSignature.returnType)
        }

        fun getSharedTypeIfApplicable(descriptor: DeclarationDescriptor, typeMapper: KotlinTypeMapper): Type? {
            return when (descriptor) {
                is LocalVariableDescriptor -> typeMapper.getSharedVarType(descriptor)
                else -> null
            }
        }
    }
}
