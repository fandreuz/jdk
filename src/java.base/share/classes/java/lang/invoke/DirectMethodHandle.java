/*
 * Copyright (c) 2008, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.invoke;

import jdk.internal.misc.CDS;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.AOTSafeClassInitializer;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;
import sun.invoke.util.ValueConversions;
import sun.invoke.util.VerifyAccess;
import sun.invoke.util.Wrapper;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

import static java.lang.invoke.LambdaForm.*;
import static java.lang.invoke.LambdaForm.Kind.*;
import static java.lang.invoke.MethodHandleNatives.Constants.*;
import static java.lang.invoke.MethodHandleStatics.UNSAFE;
import static java.lang.invoke.MethodHandleStatics.newInternalError;
import static java.lang.invoke.MethodTypeForm.*;

/**
 * The flavor of method handle which implements a constant reference
 * to a class member.
 * @author jrose
 */
@AOTSafeClassInitializer
sealed class DirectMethodHandle extends MethodHandle {
    final MemberName member;
    final boolean crackable;

    // Constructors and factory methods in this class *must* be package scoped or private.
    private DirectMethodHandle(MethodType mtype, LambdaForm form, MemberName member, boolean crackable) {
        super(mtype, form);
        if (!member.isResolved())  throw new InternalError();

        if (member.getDeclaringClass().isInterface() &&
            member.getReferenceKind() == REF_invokeInterface &&
            member.isMethod() && !member.isAbstract()) {
            // Check for corner case: invokeinterface of Object method
            MemberName m = new MemberName(Object.class, member.getName(), member.getMethodType(), member.getReferenceKind());
            m = MemberName.getFactory().resolveOrNull(m.getReferenceKind(), m, null, LM_TRUSTED);
            if (m != null && m.isPublic()) {
                assert(member.getReferenceKind() == m.getReferenceKind());  // else this.form is wrong
                member = m;
            }
        }

        this.member = member;
        this.crackable = crackable;
    }

    // Factory methods:
    static DirectMethodHandle make(byte refKind, Class<?> refc, MemberName member, Class<?> callerClass) {
        MethodType mtype = member.getMethodOrFieldType();
        if (!member.isStatic()) {
            if (!member.getDeclaringClass().isAssignableFrom(refc) || member.isConstructor())
                throw new InternalError(member.toString());
            mtype = mtype.insertParameterTypes(0, refc);
        }
        if (!member.isField()) {
            // refKind reflects the original type of lookup via findSpecial or
            // findVirtual etc.
            return switch (refKind) {
                case REF_invokeSpecial -> {
                    member = member.asSpecial();
                    // if caller is an interface we need to adapt to get the
                    // receiver check inserted
                    if (callerClass == null) {
                        throw new InternalError("callerClass must not be null for REF_invokeSpecial");
                    }
                    LambdaForm lform = preparedLambdaForm(member, callerClass.isInterface());
                    yield new Special(mtype, lform, member, true, callerClass);
                }
                case REF_invokeInterface -> {
                    // for interfaces we always need the receiver typecheck,
                    // so we always pass 'true' to ensure we adapt if needed
                    // to include the REF_invokeSpecial case
                    LambdaForm lform = preparedLambdaForm(member, true);
                    yield new Interface(mtype, lform, member, true, refc);
                }
                default -> {
                    LambdaForm lform = preparedLambdaForm(member);
                    yield new DirectMethodHandle(mtype, lform, member, true);
                }
            };
        } else {
            LambdaForm lform = preparedFieldLambdaForm(member);
            if (member.isStatic()) {
                long offset = MethodHandleNatives.staticFieldOffset(member);
                Object base = MethodHandleNatives.staticFieldBase(member);
                return new StaticAccessor(mtype, lform, member, true, base, offset);
            } else {
                long offset = MethodHandleNatives.objectFieldOffset(member);
                assert(offset == (int)offset);
                return new Accessor(mtype, lform, member, true, (int)offset);
            }
        }
    }
    static DirectMethodHandle make(Class<?> refc, MemberName member) {
        byte refKind = member.getReferenceKind();
        if (refKind == REF_invokeSpecial)
            refKind =  REF_invokeVirtual;
        return make(refKind, refc, member, null /* no callerClass context */);
    }
    static DirectMethodHandle make(MemberName member) {
        if (member.isConstructor())
            return makeAllocator(member.getDeclaringClass(), member);
        return make(member.getDeclaringClass(), member);
    }
    static DirectMethodHandle makeAllocator(Class<?> instanceClass, MemberName ctor) {
        assert(ctor.isConstructor() && ctor.getName().equals("<init>"));
        ctor = ctor.asConstructor();
        assert(ctor.isConstructor() && ctor.getReferenceKind() == REF_newInvokeSpecial) : ctor;
        MethodType mtype = ctor.getMethodType().changeReturnType(instanceClass);
        LambdaForm lform = preparedLambdaForm(ctor);
        MemberName init = ctor.asSpecial();
        assert(init.getMethodType().returnType() == void.class);
        return new Constructor(mtype, lform, ctor, true, init, instanceClass);
    }

    @Override
    BoundMethodHandle rebind() {
        return BoundMethodHandle.makeReinvoker(this);
    }

    @Override
    MethodHandle copyWith(MethodType mt, LambdaForm lf) {
        assert(this.getClass() == DirectMethodHandle.class);  // must override in subclasses
        return new DirectMethodHandle(mt, lf, member, crackable);
    }

    @Override
    MethodHandle viewAsType(MethodType newType, boolean strict) {
        // No actual conversions, just a new view of the same method.
        // However, we must not expose a DMH that is crackable into a
        // MethodHandleInfo, so we return a cloned, uncrackable DMH
        assert(viewAsTypeChecks(newType, strict));
        assert(this.getClass() == DirectMethodHandle.class);  // must override in subclasses
        return new DirectMethodHandle(newType, form, member, false);
    }

    @Override
    boolean isCrackable() {
        return crackable;
    }

    @Override
    String internalProperties(int indentLevel) {
        return "\n" + debugPrefix(indentLevel) + "& DMH.MN=" + internalMemberName();
    }

    //// Implementation methods.
    @Override
    @ForceInline
    MemberName internalMemberName() {
        return member;
    }

    private static final MemberName.Factory IMPL_NAMES = MemberName.getFactory();

    /**
     * Create a LF which can invoke the given method.
     * Cache and share this structure among all methods with
     * the same basicType and refKind.
     */
    private static LambdaForm preparedLambdaForm(MemberName m, boolean adaptToSpecialIfc) {
        assert(m.isInvocable()) : m;  // call preparedFieldLambdaForm instead
        MethodType mtype = m.getInvocationType().basicType();
        assert(!m.isMethodHandleInvoke()) : m;
        // MemberName.getReferenceKind represents the JVM optimized form of the call
        // as distinct from the "kind" passed to DMH.make which represents the original
        // bytecode-equivalent request. Specifically private/final methods that use a direct
        // call have getReferenceKind adapted to REF_invokeSpecial, even though the actual
        // invocation mode may be invokevirtual or invokeinterface.
        int which = switch (m.getReferenceKind()) {
            case REF_invokeVirtual    -> LF_INVVIRTUAL;
            case REF_invokeStatic     -> LF_INVSTATIC;
            case REF_invokeSpecial    -> LF_INVSPECIAL;
            case REF_invokeInterface  -> LF_INVINTERFACE;
            case REF_newInvokeSpecial -> LF_NEWINVSPECIAL;
            default -> throw new InternalError(m.toString());
        };
        if (which == LF_INVSTATIC && shouldBeInitialized(m)) {
            // precompute the barrier-free version:
            preparedLambdaForm(mtype, which);
            which = LF_INVSTATIC_INIT;
        }
        if (which == LF_INVSPECIAL && adaptToSpecialIfc) {
            which = LF_INVSPECIAL_IFC;
        }
        LambdaForm lform = preparedLambdaForm(mtype, which);
        assert(lform.methodType().dropParameterTypes(0, 1)
                .equals(m.getInvocationType().basicType()))
                : Arrays.asList(m, m.getInvocationType().basicType(), lform, lform.methodType());
        return lform;
    }

    private static LambdaForm preparedLambdaForm(MemberName m) {
        return preparedLambdaForm(m, false);
    }

    private static LambdaForm preparedLambdaForm(MethodType mtype, int which) {
        LambdaForm lform = mtype.form().cachedLambdaForm(which);
        if (lform != null)  return lform;
        lform = makePreparedLambdaForm(mtype, which);
        return mtype.form().setCachedLambdaForm(which, lform);
    }

    static LambdaForm makePreparedLambdaForm(MethodType mtype, int which) {
        boolean needsInit = (which == LF_INVSTATIC_INIT);
        boolean doesAlloc = (which == LF_NEWINVSPECIAL);
        boolean needsReceiverCheck = (which == LF_INVINTERFACE ||
                                      which == LF_INVSPECIAL_IFC);

        String linkerName;
        LambdaForm.Kind kind;
        switch (which) {
        case LF_INVVIRTUAL:    linkerName = "linkToVirtual";   kind = DIRECT_INVOKE_VIRTUAL;     break;
        case LF_INVSTATIC:     linkerName = "linkToStatic";    kind = DIRECT_INVOKE_STATIC;      break;
        case LF_INVSTATIC_INIT:linkerName = "linkToStatic";    kind = DIRECT_INVOKE_STATIC_INIT; break;
        case LF_INVSPECIAL_IFC:linkerName = "linkToSpecial";   kind = DIRECT_INVOKE_SPECIAL_IFC; break;
        case LF_INVSPECIAL:    linkerName = "linkToSpecial";   kind = DIRECT_INVOKE_SPECIAL;     break;
        case LF_INVINTERFACE:  linkerName = "linkToInterface"; kind = DIRECT_INVOKE_INTERFACE;   break;
        case LF_NEWINVSPECIAL: linkerName = "linkToSpecial";   kind = DIRECT_NEW_INVOKE_SPECIAL; break;
        default:  throw new InternalError("which="+which);
        }

        MethodType mtypeWithArg;
        if (doesAlloc) {
            var ptypes = mtype.ptypes();
            var newPtypes = new Class<?>[ptypes.length + 2];
            newPtypes[0] = Object.class; // insert newly allocated obj
            System.arraycopy(ptypes, 0, newPtypes, 1, ptypes.length);
            newPtypes[newPtypes.length - 1] = MemberName.class;
            mtypeWithArg = MethodType.methodType(void.class, newPtypes, true);
        } else {
            mtypeWithArg = mtype.appendParameterTypes(MemberName.class);
        }
        MemberName linker = new MemberName(MethodHandle.class, linkerName, mtypeWithArg, REF_invokeStatic);
        try {
            linker = IMPL_NAMES.resolveOrFail(REF_invokeStatic, linker, null, LM_TRUSTED,
                                              NoSuchMethodException.class);
        } catch (ReflectiveOperationException ex) {
            throw newInternalError(ex);
        }
        final int DMH_THIS    = 0;
        final int ARG_BASE    = 1;
        final int ARG_LIMIT   = ARG_BASE + mtype.parameterCount();
        int nameCursor = ARG_LIMIT;
        final int NEW_OBJ     = (doesAlloc ? nameCursor++ : -1);
        final int GET_MEMBER  = nameCursor++;
        final int CHECK_RECEIVER = (needsReceiverCheck ? nameCursor++ : -1);
        final int LINKER_CALL = nameCursor++;
        Name[] names = invokeArguments(nameCursor - ARG_LIMIT, mtype);
        assert(names.length == nameCursor);
        if (doesAlloc) {
            // names = { argx,y,z,... new C, init method }
            names[NEW_OBJ] = new Name(getFunction(NF_allocateInstance), names[DMH_THIS]);
            names[GET_MEMBER] = new Name(getFunction(NF_constructorMethod), names[DMH_THIS]);
        } else if (needsInit) {
            names[GET_MEMBER] = new Name(getFunction(NF_internalMemberNameEnsureInit), names[DMH_THIS]);
        } else {
            names[GET_MEMBER] = new Name(getFunction(NF_internalMemberName), names[DMH_THIS]);
        }
        assert(findDirectMethodHandle(names[GET_MEMBER]) == names[DMH_THIS]);
        Object[] outArgs = Arrays.copyOfRange(names, ARG_BASE, GET_MEMBER+1, Object[].class);
        if (needsReceiverCheck) {
            names[CHECK_RECEIVER] = new Name(getFunction(NF_checkReceiver), names[DMH_THIS], names[ARG_BASE]);
            outArgs[0] = names[CHECK_RECEIVER];
        }
        assert(outArgs[outArgs.length-1] == names[GET_MEMBER]);  // look, shifted args!
        int result = LAST_RESULT;
        if (doesAlloc) {
            assert(outArgs[outArgs.length-2] == names[NEW_OBJ]);  // got to move this one
            System.arraycopy(outArgs, 0, outArgs, 1, outArgs.length-2);
            outArgs[0] = names[NEW_OBJ];
            result = NEW_OBJ;
        }
        names[LINKER_CALL] = new Name(linker, outArgs);
        LambdaForm lform = LambdaForm.create(ARG_LIMIT, names, result, kind);

        // This is a tricky bit of code.  Don't send it through the LF interpreter.
        lform.compileToBytecode();
        return lform;
    }

    /* assert */ static Object findDirectMethodHandle(Name name) {
        if (name.function.equals(getFunction(NF_internalMemberName)) ||
            name.function.equals(getFunction(NF_internalMemberNameEnsureInit)) ||
            name.function.equals(getFunction(NF_constructorMethod))) {
            assert(name.arguments.length == 1);
            return name.arguments[0];
        }
        return null;
    }

    /** Static wrapper for DirectMethodHandle.internalMemberName. */
    @ForceInline
    /*non-public*/
    static Object internalMemberName(Object mh) {
        return ((DirectMethodHandle)mh).member;
    }

    /** Static wrapper for DirectMethodHandle.internalMemberName.
     * This one also forces initialization.
     */
    /*non-public*/
    static Object internalMemberNameEnsureInit(Object mh) {
        DirectMethodHandle dmh = (DirectMethodHandle)mh;
        dmh.ensureInitialized();
        return dmh.member;
    }

    /*non-public*/
    static boolean shouldBeInitialized(MemberName member) {
        switch (member.getReferenceKind()) {
        case REF_invokeStatic:
        case REF_getStatic:
        case REF_putStatic:
        case REF_newInvokeSpecial:
            break;
        default:
            // No need to initialize the class on this kind of member.
            return false;
        }
        Class<?> cls = member.getDeclaringClass();
        if (cls == ValueConversions.class ||
            cls == MethodHandleImpl.class ||
            cls == Invokers.class) {
            // These guys have lots of <clinit> DMH creation but we know
            // the MHs will not be used until the system is booted.
            return false;
        }
        if (VerifyAccess.isSamePackage(MethodHandle.class, cls) ||
            VerifyAccess.isSamePackage(ValueConversions.class, cls)) {
            // It is a system class.  It is probably in the process of
            // being initialized, but we will help it along just to be safe.
            UNSAFE.ensureClassInitialized(cls);
            return CDS.needsClassInitBarrier(cls);
        }
        return UNSAFE.shouldBeInitialized(cls) || CDS.needsClassInitBarrier(cls);
    }

    private void ensureInitialized() {
        if (checkInitialized(member)) {
            // The coast is clear.  Delete the <clinit> barrier.
            updateForm(new Function<>() {
                public LambdaForm apply(LambdaForm oldForm) {
                    return (member.isField() ? preparedFieldLambdaForm(member)
                                             : preparedLambdaForm(member));
                }
            });
        }
    }
    private static boolean checkInitialized(MemberName member) {
        Class<?> defc = member.getDeclaringClass();
        UNSAFE.ensureClassInitialized(defc);
        // Once we get here either defc was fully initialized by another thread, or
        // defc was already being initialized by the current thread. In the latter case
        // the barrier must remain. We can detect this simply by checking if initialization
        // is still needed.
        return !UNSAFE.shouldBeInitialized(defc);
    }

    /*non-public*/
    static void ensureInitialized(Object mh) {
        ((DirectMethodHandle)mh).ensureInitialized();
    }

    /** This subclass represents invokespecial instructions. */
    static final class Special extends DirectMethodHandle {
        private final Class<?> caller;
        private Special(MethodType mtype, LambdaForm form, MemberName member, boolean crackable, Class<?> caller) {
            super(mtype, form, member, crackable);
            this.caller = caller;
        }
        @Override
        boolean isInvokeSpecial() {
            return true;
        }
        @Override
        MethodHandle copyWith(MethodType mt, LambdaForm lf) {
            return new Special(mt, lf, member, crackable, caller);
        }
        @Override
        MethodHandle viewAsType(MethodType newType, boolean strict) {
            assert(viewAsTypeChecks(newType, strict));
            return new Special(newType, form, member, false, caller);
        }
        Object checkReceiver(Object recv) {
            if (!caller.isInstance(recv)) {
                if (recv != null) {
                    String msg = String.format("Receiver class %s is not a subclass of caller class %s",
                                               recv.getClass().getName(), caller.getName());
                    throw new IncompatibleClassChangeError(msg);
                } else {
                    String msg = String.format("Cannot invoke %s with null receiver", member);
                    throw new NullPointerException(msg);
                }
            }
            return recv;
        }
    }

    /** This subclass represents invokeinterface instructions. */
    static final class Interface extends DirectMethodHandle {
        private final Class<?> refc;
        private Interface(MethodType mtype, LambdaForm form, MemberName member, boolean crackable, Class<?> refc) {
            super(mtype, form, member, crackable);
            assert(refc.isInterface()) : refc;
            this.refc = refc;
        }
        @Override
        MethodHandle copyWith(MethodType mt, LambdaForm lf) {
            return new Interface(mt, lf, member, crackable, refc);
        }
        @Override
        MethodHandle viewAsType(MethodType newType, boolean strict) {
            assert(viewAsTypeChecks(newType, strict));
            return new Interface(newType, form, member, false, refc);
        }
        @Override
        Object checkReceiver(Object recv) {
            if (!refc.isInstance(recv)) {
                if (recv != null) {
                    String msg = String.format("Receiver class %s does not implement the requested interface %s",
                                               recv.getClass().getName(), refc.getName());
                    throw new IncompatibleClassChangeError(msg);
                } else {
                    String msg = String.format("Cannot invoke %s with null receiver", member);
                    throw new NullPointerException(msg);
                }
            }
            return recv;
        }
    }

    /** Used for interface receiver type checks, by Interface and Special modes. */
    Object checkReceiver(Object recv) {
        throw new InternalError("Should only be invoked on a subclass");
    }

    /** This subclass handles constructor references. */
    @AOTSafeClassInitializer
    static final class Constructor extends DirectMethodHandle {
        final MemberName initMethod;
        final Class<?>   instanceClass;

        private Constructor(MethodType mtype, LambdaForm form, MemberName constructor,
                            boolean crackable, MemberName initMethod, Class<?> instanceClass) {
            super(mtype, form, constructor, crackable);
            this.initMethod = initMethod;
            this.instanceClass = instanceClass;
            assert(initMethod.isResolved());
        }
        @Override
        MethodHandle copyWith(MethodType mt, LambdaForm lf) {
            return new Constructor(mt, lf, member, crackable, initMethod, instanceClass);
        }
        @Override
        MethodHandle viewAsType(MethodType newType, boolean strict) {
            assert(viewAsTypeChecks(newType, strict));
            return new Constructor(newType, form, member, false, initMethod, instanceClass);
        }
    }

    /*non-public*/
    static Object constructorMethod(Object mh) {
        Constructor dmh = (Constructor)mh;
        return dmh.initMethod;
    }

    /*non-public*/
    static Object allocateInstance(Object mh) throws InstantiationException {
        Constructor dmh = (Constructor)mh;
        return UNSAFE.allocateInstance(dmh.instanceClass);
    }

    /** This subclass handles non-static field references. */
    static final class Accessor extends DirectMethodHandle {
        final Class<?> fieldType;
        final int      fieldOffset;
        private Accessor(MethodType mtype, LambdaForm form, MemberName member,
                         boolean crackable, int fieldOffset) {
            super(mtype, form, member, crackable);
            this.fieldType   = member.getFieldType();
            this.fieldOffset = fieldOffset;
        }

        @Override Object checkCast(Object obj) {
            return fieldType.cast(obj);
        }
        @Override
        MethodHandle copyWith(MethodType mt, LambdaForm lf) {
            return new Accessor(mt, lf, member, crackable, fieldOffset);
        }
        @Override
        MethodHandle viewAsType(MethodType newType, boolean strict) {
            assert(viewAsTypeChecks(newType, strict));
            return new Accessor(newType, form, member, false, fieldOffset);
        }
    }

    @ForceInline
    /*non-public*/
    static long fieldOffset(Object accessorObj) {
        // Note: We return a long because that is what Unsafe.getObject likes.
        // We store a plain int because it is more compact.
        return ((Accessor)accessorObj).fieldOffset;
    }

    @ForceInline
    /*non-public*/
    static Object checkBase(Object obj) {
        // Note that the object's class has already been verified,
        // since the parameter type of the Accessor method handle
        // is either member.getDeclaringClass or a subclass.
        // This was verified in DirectMethodHandle.make.
        // Therefore, the only remaining check is for null.
        // Since this check is *not* guaranteed by Unsafe.getInt
        // and its siblings, we need to make an explicit one here.
        return Objects.requireNonNull(obj);
    }

    /** This subclass handles static field references. */
    static final class StaticAccessor extends DirectMethodHandle {
        private final Class<?> fieldType;
        private final Object   staticBase;
        private final long     staticOffset;

        private StaticAccessor(MethodType mtype, LambdaForm form, MemberName member,
                               boolean crackable, Object staticBase, long staticOffset) {
            super(mtype, form, member, crackable);
            this.fieldType    = member.getFieldType();
            this.staticBase   = staticBase;
            this.staticOffset = staticOffset;
        }

        @Override Object checkCast(Object obj) {
            return fieldType.cast(obj);
        }
        @Override
        MethodHandle copyWith(MethodType mt, LambdaForm lf) {
            return new StaticAccessor(mt, lf, member, crackable, staticBase, staticOffset);
        }
        @Override
        MethodHandle viewAsType(MethodType newType, boolean strict) {
            assert(viewAsTypeChecks(newType, strict));
            return new StaticAccessor(newType, form, member, false, staticBase, staticOffset);
        }
    }

    @ForceInline
    /*non-public*/
    static Object nullCheck(Object obj) {
        return Objects.requireNonNull(obj);
    }

    @ForceInline
    /*non-public*/
    static Object staticBase(Object accessorObj) {
        return ((StaticAccessor)accessorObj).staticBase;
    }

    @ForceInline
    /*non-public*/
    static long staticOffset(Object accessorObj) {
        return ((StaticAccessor)accessorObj).staticOffset;
    }

    @ForceInline
    /*non-public*/
    static Object checkCast(Object mh, Object obj) {
        return ((DirectMethodHandle) mh).checkCast(obj);
    }

    Object checkCast(Object obj) {
        return member.getMethodType().returnType().cast(obj);
    }

    // Caching machinery for field accessors:
    static final byte
            AF_GETFIELD        = 0,
            AF_PUTFIELD        = 1,
            AF_GETSTATIC       = 2,
            AF_PUTSTATIC       = 3,
            AF_GETSTATIC_INIT  = 4,
            AF_PUTSTATIC_INIT  = 5,
            AF_LIMIT           = 6;
    // Enumerate the different field kinds using Wrapper,
    // with an extra case added for checked references.
    static final int
            FT_UNCHECKED_REF   = Wrapper.OBJECT.ordinal(),
            FT_CHECKED_REF     = Wrapper.VOID.ordinal(),
            FT_LIMIT           = Wrapper.COUNT;
    private static int afIndex(byte formOp, boolean isVolatile, int ftypeKind) {
        return ((formOp * FT_LIMIT * 2)
                + (isVolatile ? FT_LIMIT : 0)
                + ftypeKind);
    }
    @Stable
    private static final LambdaForm[] ACCESSOR_FORMS
            = new LambdaForm[afIndex(AF_LIMIT, false, 0)];
    static int ftypeKind(Class<?> ftype) {
        if (ftype.isPrimitive()) {
            return Wrapper.forPrimitiveType(ftype).ordinal();
        } else if (ftype.isInterface() || ftype.isAssignableFrom(Object.class)) {
            // retyping can be done without a cast
            return FT_UNCHECKED_REF;
        } else {
            return FT_CHECKED_REF;
        }
    }

    /**
     * Create a LF which can access the given field.
     * Cache and share this structure among all fields with
     * the same basicType and refKind.
     */
    private static LambdaForm preparedFieldLambdaForm(MemberName m) {
        Class<?> ftype = m.getFieldType();
        boolean isVolatile = m.isVolatile();
        byte formOp = switch (m.getReferenceKind()) {
            case REF_getField  -> AF_GETFIELD;
            case REF_putField  -> AF_PUTFIELD;
            case REF_getStatic -> AF_GETSTATIC;
            case REF_putStatic -> AF_PUTSTATIC;
            default -> throw new InternalError(m.toString());
        };
        if (shouldBeInitialized(m)) {
            // precompute the barrier-free version:
            preparedFieldLambdaForm(formOp, isVolatile, ftype);
            assert((AF_GETSTATIC_INIT - AF_GETSTATIC) ==
                   (AF_PUTSTATIC_INIT - AF_PUTSTATIC));
            formOp += (AF_GETSTATIC_INIT - AF_GETSTATIC);
        }
        LambdaForm lform = preparedFieldLambdaForm(formOp, isVolatile, ftype);
        assert(lform.methodType().dropParameterTypes(0, 1)
                .equals(m.getInvocationType().basicType()))
                : Arrays.asList(m, m.getInvocationType().basicType(), lform, lform.methodType());
        return lform;
    }
    private static LambdaForm preparedFieldLambdaForm(byte formOp, boolean isVolatile, Class<?> ftype) {
        int ftypeKind = ftypeKind(ftype);
        int afIndex = afIndex(formOp, isVolatile, ftypeKind);
        LambdaForm lform = ACCESSOR_FORMS[afIndex];
        if (lform != null)  return lform;
        lform = makePreparedFieldLambdaForm(formOp, isVolatile, ftypeKind);
        ACCESSOR_FORMS[afIndex] = lform;  // don't bother with a CAS
        return lform;
    }

    private static final @Stable Wrapper[] ALL_WRAPPERS = Wrapper.values();

    // Names in kind may overload but differ from their basic type
    private static Kind getFieldKind(boolean isVolatile, boolean needsInit, boolean needsCast, Wrapper wrapper) {
        if (isVolatile) {
            if (needsInit) {
                return switch (wrapper) {
                    case BYTE -> VOLATILE_FIELD_ACCESS_INIT_B;
                    case CHAR -> VOLATILE_FIELD_ACCESS_INIT_C;
                    case SHORT -> VOLATILE_FIELD_ACCESS_INIT_S;
                    case BOOLEAN -> VOLATILE_FIELD_ACCESS_INIT_Z;
                    default -> needsCast ? VOLATILE_FIELD_ACCESS_INIT_CAST : VOLATILE_FIELD_ACCESS_INIT;
                };
            } else {
                return switch (wrapper) {
                    case BYTE -> VOLATILE_FIELD_ACCESS_B;
                    case CHAR -> VOLATILE_FIELD_ACCESS_C;
                    case SHORT -> VOLATILE_FIELD_ACCESS_S;
                    case BOOLEAN -> VOLATILE_FIELD_ACCESS_Z;
                    default -> needsCast ? VOLATILE_FIELD_ACCESS_CAST : VOLATILE_FIELD_ACCESS;
                };
            }
        } else {
            if (needsInit) {
                return switch (wrapper) {
                    case BYTE -> FIELD_ACCESS_INIT_B;
                    case CHAR -> FIELD_ACCESS_INIT_C;
                    case SHORT -> FIELD_ACCESS_INIT_S;
                    case BOOLEAN -> FIELD_ACCESS_INIT_Z;
                    default -> needsCast ? FIELD_ACCESS_INIT_CAST : FIELD_ACCESS_INIT;
                };
            } else {
                return switch (wrapper) {
                    case BYTE -> FIELD_ACCESS_B;
                    case CHAR -> FIELD_ACCESS_C;
                    case SHORT -> FIELD_ACCESS_S;
                    case BOOLEAN -> FIELD_ACCESS_Z;
                    default -> needsCast ? FIELD_ACCESS_CAST : FIELD_ACCESS;
                };
            }
        }
    }

    private static String unsafeMethodName(boolean isGetter, boolean isVolatile, Wrapper wrapper) {
        var name = switch (wrapper) {
            case BOOLEAN -> "Boolean";
            case BYTE -> "Byte";
            case CHAR -> "Char";
            case SHORT -> "Short";
            case INT -> "Int";
            case FLOAT -> "Float";
            case LONG -> "Long";
            case DOUBLE -> "Double";
            case OBJECT -> "Reference";
            case VOID -> throw new InternalError();
        };
        var sb = new StringBuilder(3 + name.length() + (isVolatile ? 8 : 0))
                .append(isGetter ? "get" : "put")
                .append(name);
        if (isVolatile) {
            sb.append("Volatile");
        }
        return sb.toString();
    }

    static LambdaForm makePreparedFieldLambdaForm(byte formOp, boolean isVolatile, int ftypeKind) {
        boolean isGetter  = (formOp & 1) == (AF_GETFIELD & 1);
        boolean isStatic  = (formOp >= AF_GETSTATIC);
        boolean needsInit = (formOp >= AF_GETSTATIC_INIT);
        boolean needsCast = (ftypeKind == FT_CHECKED_REF);
        Wrapper fw = (needsCast ? Wrapper.OBJECT : ALL_WRAPPERS[ftypeKind]);
        Class<?> ft = fw.primitiveType();
        assert(ftypeKind(needsCast ? String.class : ft) == ftypeKind);

        // getObject, putIntVolatile, etc.
        String unsafeMethodName = unsafeMethodName(isGetter, isVolatile, fw);
        // isGetter and isStatic is reflected in field type; basic type clash for subwords
        Kind kind = getFieldKind(isVolatile, needsInit, needsCast, fw);

        MethodType linkerType;
        if (isGetter)
            linkerType = MethodType.methodType(ft, Object.class, long.class);
        else
            linkerType = MethodType.methodType(void.class, Object.class, long.class, ft);
        MemberName linker = new MemberName(Unsafe.class, unsafeMethodName, linkerType, REF_invokeVirtual);
        try {
            linker = IMPL_NAMES.resolveOrFail(REF_invokeVirtual, linker, null, LM_TRUSTED,
                                              NoSuchMethodException.class);
        } catch (ReflectiveOperationException ex) {
            throw newInternalError(ex);
        }

        // What is the external type of the lambda form?
        MethodType mtype;
        if (isGetter)
            mtype = MethodType.methodType(ft);
        else
            mtype = MethodType.methodType(void.class, ft);
        mtype = mtype.basicType();  // erase short to int, etc.
        if (!isStatic)
            mtype = mtype.insertParameterTypes(0, Object.class);
        final int DMH_THIS  = 0;
        final int ARG_BASE  = 1;
        final int ARG_LIMIT = ARG_BASE + mtype.parameterCount();
        // if this is for non-static access, the base pointer is stored at this index:
        final int OBJ_BASE  = isStatic ? -1 : ARG_BASE;
        // if this is for write access, the value to be written is stored at this index:
        final int SET_VALUE  = isGetter ? -1 : ARG_LIMIT - 1;
        int nameCursor = ARG_LIMIT;
        final int F_HOLDER  = (isStatic ? nameCursor++ : -1);  // static base if any
        final int F_OFFSET  = nameCursor++;  // Either static offset or field offset.
        final int OBJ_CHECK = (OBJ_BASE >= 0 ? nameCursor++ : -1);
        final int U_HOLDER  = nameCursor++;  // UNSAFE holder
        final int INIT_BAR  = (needsInit ? nameCursor++ : -1);
        final int PRE_CAST  = (needsCast && !isGetter ? nameCursor++ : -1);
        final int LINKER_CALL = nameCursor++;
        final int POST_CAST = (needsCast && isGetter ? nameCursor++ : -1);
        final int RESULT    = nameCursor-1;  // either the call or the cast
        Name[] names = invokeArguments(nameCursor - ARG_LIMIT, mtype);
        if (needsInit)
            names[INIT_BAR] = new Name(getFunction(NF_ensureInitialized), names[DMH_THIS]);
        if (needsCast && !isGetter)
            names[PRE_CAST] = new Name(getFunction(NF_checkCast), names[DMH_THIS], names[SET_VALUE]);
        Object[] outArgs = new Object[1 + linkerType.parameterCount()];
        assert(outArgs.length == (isGetter ? 3 : 4));
        outArgs[0] = names[U_HOLDER] = new Name(getFunction(NF_UNSAFE));
        if (isStatic) {
            outArgs[1] = names[F_HOLDER]  = new Name(getFunction(NF_staticBase), names[DMH_THIS]);
            outArgs[2] = names[F_OFFSET]  = new Name(getFunction(NF_staticOffset), names[DMH_THIS]);
        } else {
            outArgs[1] = names[OBJ_CHECK] = new Name(getFunction(NF_checkBase), names[OBJ_BASE]);
            outArgs[2] = names[F_OFFSET]  = new Name(getFunction(NF_fieldOffset), names[DMH_THIS]);
        }
        if (!isGetter) {
            outArgs[3] = (needsCast ? names[PRE_CAST] : names[SET_VALUE]);
        }
        for (Object a : outArgs)  assert(a != null);
        names[LINKER_CALL] = new Name(linker, outArgs);
        if (needsCast && isGetter)
            names[POST_CAST] = new Name(getFunction(NF_checkCast), names[DMH_THIS], names[LINKER_CALL]);
        for (Name n : names)  assert(n != null);

        LambdaForm form = LambdaForm.create(ARG_LIMIT, names, RESULT, kind);

        if (LambdaForm.debugNames()) {
            // add some detail to the lambdaForm debugname,
            // significant only for debugging
            StringBuilder nameBuilder = new StringBuilder(unsafeMethodName);
            if (isStatic) {
                nameBuilder.append("Static");
            } else {
                nameBuilder.append("Field");
            }
            if (needsCast) {
                nameBuilder.append("Cast");
            }
            if (needsInit) {
                nameBuilder.append("Init");
            }
            LambdaForm.associateWithDebugName(form, nameBuilder.toString());
        }

        // NF_UNSAFE uses field form, avoid circular dependency in interpreter
        form.compileToBytecode();
        return form;
    }

    /**
     * Pre-initialized NamedFunctions for bootstrapping purposes.
     */
    static final byte NF_internalMemberName = 0,
            NF_internalMemberNameEnsureInit = 1,
            NF_ensureInitialized = 2,
            NF_fieldOffset = 3,
            NF_checkBase = 4,
            NF_staticBase = 5,
            NF_staticOffset = 6,
            NF_checkCast = 7,
            NF_allocateInstance = 8,
            NF_constructorMethod = 9,
            NF_UNSAFE = 10,
            NF_checkReceiver = 11,
            NF_LIMIT = 12;

    private static final @Stable NamedFunction[] NFS = new NamedFunction[NF_LIMIT];

    private static NamedFunction getFunction(byte func) {
        NamedFunction nf = NFS[func];
        if (nf != null) {
            return nf;
        }
        // Each nf must be statically invocable or we get tied up in our bootstraps.
        nf = NFS[func] = createFunction(func);
        assert(InvokerBytecodeGenerator.isStaticallyInvocable(nf));
        return nf;
    }

    private static final MethodType OBJ_OBJ_TYPE = MethodType.methodType(Object.class, Object.class);

    private static final MethodType LONG_OBJ_TYPE = MethodType.methodType(long.class, Object.class);

    private static NamedFunction createFunction(byte func) {
        try {
            switch (func) {
                case NF_internalMemberName:
                    return getNamedFunction("internalMemberName", OBJ_OBJ_TYPE);
                case NF_internalMemberNameEnsureInit:
                    return getNamedFunction("internalMemberNameEnsureInit", OBJ_OBJ_TYPE);
                case NF_ensureInitialized:
                    return getNamedFunction("ensureInitialized", MethodType.methodType(void.class, Object.class));
                case NF_fieldOffset:
                    return getNamedFunction("fieldOffset", LONG_OBJ_TYPE);
                case NF_checkBase:
                    return getNamedFunction("checkBase", OBJ_OBJ_TYPE);
                case NF_staticBase:
                    return getNamedFunction("staticBase", OBJ_OBJ_TYPE);
                case NF_staticOffset:
                    return getNamedFunction("staticOffset", LONG_OBJ_TYPE);
                case NF_checkCast:
                    return getNamedFunction("checkCast", MethodType.methodType(Object.class, Object.class, Object.class));
                case NF_allocateInstance:
                    return getNamedFunction("allocateInstance", OBJ_OBJ_TYPE);
                case NF_constructorMethod:
                    return getNamedFunction("constructorMethod", OBJ_OBJ_TYPE);
                case NF_UNSAFE:
                    MemberName member = new MemberName(MethodHandleStatics.class, "UNSAFE", Unsafe.class, REF_getStatic);
                    return new NamedFunction(
                            MemberName.getFactory().resolveOrFail(REF_getStatic, member,
                                                                  DirectMethodHandle.class, LM_TRUSTED,
                                                                  NoSuchFieldException.class));
                case NF_checkReceiver:
                    member = new MemberName(DirectMethodHandle.class, "checkReceiver", OBJ_OBJ_TYPE, REF_invokeVirtual);
                    return new NamedFunction(
                            MemberName.getFactory().resolveOrFail(REF_invokeVirtual, member,
                                                                  DirectMethodHandle.class, LM_TRUSTED,
                                                                  NoSuchMethodException.class));
                default:
                    throw newInternalError("Unknown function: " + func);
            }
        } catch (ReflectiveOperationException ex) {
            throw newInternalError(ex);
        }
    }

    private static NamedFunction getNamedFunction(String name, MethodType type)
        throws ReflectiveOperationException
    {
        MemberName member = new MemberName(DirectMethodHandle.class, name, type, REF_invokeStatic);
        return new NamedFunction(
                MemberName.getFactory().resolveOrFail(REF_invokeStatic, member,
                                                      DirectMethodHandle.class, LM_TRUSTED,
                                                      NoSuchMethodException.class));
    }

    static {
        // The Holder class will contain pre-generated DirectMethodHandles resolved
        // speculatively using MemberName.getFactory().resolveOrNull. However, that
        // doesn't initialize the class, which subtly breaks inlining etc. By forcing
        // initialization of the Holder class we avoid these issues.
        UNSAFE.ensureClassInitialized(Holder.class);
    }

    /* Placeholder class for DirectMethodHandles generated ahead of time */
    @AOTSafeClassInitializer
    final class Holder {}
}
