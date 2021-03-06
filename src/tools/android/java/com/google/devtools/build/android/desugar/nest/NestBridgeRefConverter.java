// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.android.desugar.nest;

import static com.google.devtools.build.android.desugar.langmodel.LangModelHelper.isCrossMateRefInNest;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.android.desugar.langmodel.ClassMemberRecord;
import com.google.devtools.build.android.desugar.langmodel.FieldInstrVisitor;
import com.google.devtools.build.android.desugar.langmodel.FieldKey;
import com.google.devtools.build.android.desugar.langmodel.LangModelHelper;
import com.google.devtools.build.android.desugar.langmodel.MemberUseKind;
import com.google.devtools.build.android.desugar.langmodel.MethodInstrVisitor;
import com.google.devtools.build.android.desugar.langmodel.MethodKey;
import javax.annotation.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A visitor that convert direct access to field/method with access through bridges. */
public final class NestBridgeRefConverter extends MethodVisitor {

  private final MethodKey enclosingMethodKey;
  private final ClassMemberRecord bridgeOrigins;
  private final FieldAccessToBridgeRedirector directFieldAccessReplacer;
  private final MethodToBridgeRedirector methodToBridgeRedirector;

  NestBridgeRefConverter(
      @Nullable MethodVisitor methodVisitor, MethodKey methodKey, ClassMemberRecord bridgeOrigins) {
    super(Opcodes.ASM7, methodVisitor);
    this.enclosingMethodKey = methodKey;
    this.bridgeOrigins = bridgeOrigins;
    directFieldAccessReplacer = new FieldAccessToBridgeRedirector();
    methodToBridgeRedirector = new MethodToBridgeRedirector();
  }

  @Override
  public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
    FieldKey fieldKey = FieldKey.create(owner, name, descriptor);
    MemberUseKind useKind = MemberUseKind.fromValue(opcode);
    if (isCrossMateRefInNest(fieldKey, enclosingMethodKey)
        && bridgeOrigins.findAllMemberUseKind(fieldKey).contains(useKind)) {
      fieldKey.accept(useKind, directFieldAccessReplacer, mv);
      return;
    }
    super.visitFieldInsn(opcode, owner, name, descriptor);
  }

  @Override
  public void visitMethodInsn(
      int opcode, String owner, String name, String descriptor, boolean isInterface) {
    MethodKey methodKey = MethodKey.create(owner, name, descriptor);
    MemberUseKind useKind = MemberUseKind.fromValue(opcode);
    if ((isInterface || isCrossMateRefInNest(methodKey, enclosingMethodKey))
        && bridgeOrigins.findAllMemberUseKind(methodKey).contains(useKind)) {
      methodKey.accept(useKind, isInterface, methodToBridgeRedirector, mv);
      return;
    }
    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
  }

  /** A visitor that re-directs the invocation of a method to that of the method's bridge. */
  static class MethodToBridgeRedirector
      implements MethodInstrVisitor<MethodKey, MethodKey, MethodVisitor> {

    @Override
    public MethodKey visitInvokeVirtual(MethodKey methodKey, MethodVisitor mv) {
      MethodKey bridgeMethodKey = methodKey.bridgeOfClassInstanceMethod();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          bridgeMethodKey.owner(),
          bridgeMethodKey.name(),
          bridgeMethodKey.descriptor(),
          /* isInterface= */ false);
      return methodKey;
    }

    @Override
    public MethodKey visitInvokeSpecial(MethodKey methodKey, MethodVisitor mv) {
      MethodKey bridgeMethodKey = methodKey.bridgeOfClassInstanceMethod();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          bridgeMethodKey.owner(),
          bridgeMethodKey.name(),
          bridgeMethodKey.descriptor(),
          /* isInterface= */ false);
      return methodKey;
    }

    @Override
    public MethodKey visitConstructorInvokeSpecial(MethodKey methodKey, MethodVisitor mv) {
      MethodKey constructorBridge = methodKey.bridgeOfConstructor();
      mv.visitInsn(Opcodes.ACONST_NULL);
      mv.visitMethodInsn(
          Opcodes.INVOKESPECIAL,
          constructorBridge.owner(),
          constructorBridge.name(),
          constructorBridge.descriptor(),
          /* isInterface= */ false);
      return methodKey;
    }

    @Override
    public MethodKey visitInterfaceInvokeSpecial(MethodKey methodKey, MethodVisitor mv) {
      MethodKey methodBridge = methodKey.substituteOfInterfaceInstanceMethod();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          methodBridge.owner(),
          methodBridge.name(),
          methodBridge.descriptor(),
          /* isInterface= */ true);
      return methodKey;
    }

    @Override
    public MethodKey visitInvokeStatic(MethodKey methodKey, MethodVisitor mv) {
      final MethodKey bridgeMethodKey;
      bridgeMethodKey = methodKey.bridgeOfClassStaticMethod();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          bridgeMethodKey.owner(),
          bridgeMethodKey.name(),
          bridgeMethodKey.descriptor(),
          /* isInterface= */ false);
      return methodKey;
    }

    @Override
    public MethodKey visitInterfaceInvokeStatic(MethodKey methodKey, MethodVisitor mv) {
      final MethodKey methodBridge;
      methodBridge = methodKey.substituteOfInterfaceStaticMethod();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          methodBridge.owner(),
          methodBridge.name(),
          methodBridge.descriptor(),
          /* isInterface= */ true);
      return methodKey;
    }

    @Override
    public MethodKey visitInvokeInterface(MethodKey methodKey, MethodVisitor mv) {
      final MethodKey methodBridge;
      methodBridge = methodKey.substituteOfInterfaceInstanceMethod();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          methodBridge.owner(),
          methodBridge.name(),
          methodBridge.descriptor(),
          /* isInterface= */ true);
      return methodKey;
    }

    @Override
    public MethodKey visitInvokeDynamic(MethodKey methodKey, MethodVisitor mv) {
      throw new UnsupportedOperationException();
    }
  }

  /** A visitor that re-directs field access with bridge method invocation. */
  static class FieldAccessToBridgeRedirector
      implements FieldInstrVisitor<MethodKey, FieldKey, MethodVisitor> {

    @Override
    public MethodKey visitGetStatic(FieldKey fieldKey, MethodVisitor mv) {
      MethodKey bridgeMethodKey = fieldKey.bridgeOfStaticRead();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          bridgeMethodKey.owner(),
          bridgeMethodKey.name(),
          bridgeMethodKey.descriptor(),
          false);
      return bridgeMethodKey;
    }

    @Override
    public MethodKey visitPutStatic(FieldKey fieldKey, MethodVisitor mv) {
      MethodKey bridgeMethodKey = fieldKey.bridgeOfStaticWrite();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          bridgeMethodKey.owner(),
          bridgeMethodKey.name(),
          bridgeMethodKey.descriptor(),
          false);
      // The bridge method for an instance field writer pushes the new field value to its invoker
      // operand stack, we emit a pop instruction to be consistent with putfield instruction which
      // consumes the updated field value on the operand stack.
      mv.visitInsn(
          LangModelHelper.getTypeSizeAlignedPopOpcode(ImmutableList.of(fieldKey.getFieldType())));
      return bridgeMethodKey;
    }

    @Override
    public MethodKey visitGetField(FieldKey fieldKey, MethodVisitor mv) {
      MethodKey bridgeMethodKey = fieldKey.bridgeOfInstanceRead();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          bridgeMethodKey.owner(),
          bridgeMethodKey.name(),
          bridgeMethodKey.descriptor(),
          false);
      return bridgeMethodKey;
    }

    @Override
    public MethodKey visitPutField(FieldKey fieldKey, MethodVisitor mv) {
      MethodKey bridgeMethodKey = fieldKey.bridgeOfInstanceWrite();
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          bridgeMethodKey.owner(),
          bridgeMethodKey.name(),
          bridgeMethodKey.descriptor(),
          /* isInterface= */ false);
      // The bridge method for an instance field writer pushes the new field value to its invoker
      // operand stack, we emit a pop instruction to be consistent with putfield instruction which
      // consumes the updated field value on the operand stack.
      mv.visitInsn(
          LangModelHelper.getTypeSizeAlignedPopOpcode(ImmutableList.of(fieldKey.getFieldType())));
      return bridgeMethodKey;
    }
  }
}
