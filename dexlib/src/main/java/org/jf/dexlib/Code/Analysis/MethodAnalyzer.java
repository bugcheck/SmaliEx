package org.jf.dexlib.Code.Analysis;

import org.jf.dexlib.*;
import org.jf.dexlib.Code.*;
import org.jf.dexlib.Code.Format.ArrayDataPseudoInstruction;
import org.jf.dexlib.Code.Format.Format;
import org.jf.dexlib.Util.*;

import java.util.*;

public class MethodAnalyzer {
    private final ClassDataItem.EncodedMethod encodedMethod;

    private SparseArray<AnalyzedInstruction> instructions;

    private boolean analyzed = false;

    private BitSet instructionsToVerify;

    //This is a dummy instruction that occurs immediately before the first real instruction. We can initialize the
    //register types for this instruction to the parameter types, in order to have them propagate to all of its
    //successors, e.g. the first real instruction, the first instructions in any exception handlers covering the first
    //instruction, etc.
    private AnalyzedInstruction startOfMethod;

    public MethodAnalyzer(ClassDataItem.EncodedMethod encodedMethod) {
        if (encodedMethod == null) {
            throw new IllegalArgumentException("encodedMethod cannot be null");
        }
        if (encodedMethod.codeItem == null || encodedMethod.codeItem.getInstructions().length == 0) {
            throw new IllegalArgumentException("The method has no code");
        }
        this.encodedMethod = encodedMethod;
        buildInstructionList();

        //override AnalyzedInstruction and provide custom implementations of some of the methods, so that we don't
        //have to handle the case this special case of instruction being null, in the main class
        startOfMethod = new AnalyzedInstruction(null, -1, encodedMethod.codeItem.getRegisterCount()) {
            public boolean setsRegister() {
                return false;
            }

            @Override
            public boolean setsWideRegister() {
                return false;
            }

            @Override
            public boolean setsRegister(int registerNumber) {
                return false;
            }

            @Override
            public int getDestinationRegister() {
                assert false;
                return -1;
            };
        };

        instructionsToVerify = new BitSet(instructions.size());
    }

    public AnalyzedInstruction[] analyze() {
        assert encodedMethod != null;
        assert encodedMethod.codeItem != null;

        if (analyzed) {
            return makeInstructionArray();
        }

        CodeItem codeItem = encodedMethod.codeItem;
        MethodIdItem methodIdItem = encodedMethod.method;

        int totalRegisters = codeItem.getRegisterCount();
        int parameterRegisters = methodIdItem.getPrototype().getParameterRegisterCount();

        //if this isn't a static method, determine which register is the "this" register and set the type to the
        //current class
        if ((encodedMethod.accessFlags & AccessFlags.STATIC.getValue()) == 0) {
            int thisRegister = totalRegisters - parameterRegisters - 1;

            //if this is a constructor, then set the "this" register to an uninitialized reference of the current class
            if ((encodedMethod.accessFlags & AccessFlags.CONSTRUCTOR.getValue()) != 0) {
                //TODO: it would probably make more sense to validate this somewhere else, and just put an assert here. Also, need to do a similar check for static constructor
                if (!encodedMethod.method.getMethodName().equals("<init>")) {
                    throw new ValidationException("The constructor flag can only be used with an <init> method.");
                }

                setRegisterTypeAndPropagateChanges(startOfMethod, thisRegister,
                        RegisterType.getRegisterType(RegisterType.Category.UninitRef,
                            ClassPath.getClassDef(methodIdItem.getContainingClass())));
            } else {
                if (encodedMethod.method.getMethodName().equals("<init>")) {
                    throw new ValidationException("An <init> method must have the \"constructor\" access flag");
                }

                setRegisterTypeAndPropagateChanges(startOfMethod, thisRegister,
                        RegisterType.getRegisterType(RegisterType.Category.Reference,
                            ClassPath.getClassDef(methodIdItem.getContainingClass())));
            }
        }

        TypeListItem parameters = methodIdItem.getPrototype().getParameters();
        if (parameters != null) {
            RegisterType[] parameterTypes = getParameterTypes(parameters, parameterRegisters);
            for (int i=0; i<parameterTypes.length; i++) {
                RegisterType registerType = parameterTypes[i];
                int registerNum = (totalRegisters - parameterRegisters) + i;
                setRegisterTypeAndPropagateChanges(startOfMethod, registerNum, registerType);
            }
        }

        BitSet analyzedInstructions = new BitSet(instructionsToVerify.size());

        //make sure all of the "first instructions" are marked for processing
        for (AnalyzedInstruction successor: startOfMethod.successors) {
            instructionsToVerify.set(successor.instructionIndex);
        }

        while (!instructionsToVerify.isEmpty()) {
            for(int i=instructionsToVerify.nextSetBit(0); i>=0; i=instructionsToVerify.nextSetBit(i+1)) {
                analyzeInstruction(instructions.get(i));
                instructionsToVerify.clear(i);
            }
        }

        analyzed = true;
        return makeInstructionArray();
    }

    private int getThisRegister() {
        assert (encodedMethod.accessFlags & AccessFlags.STATIC.getValue()) == 0;

        CodeItem codeItem = encodedMethod.codeItem;
        assert codeItem != null;

        MethodIdItem methodIdItem = encodedMethod.method;
        assert methodIdItem != null;

        int totalRegisters = codeItem.getRegisterCount();
        if (totalRegisters == 0) {
            throw new ValidationException("A non-static method must have at least 1 register");
        }

        int parameterRegisters = methodIdItem.getPrototype().getParameterRegisterCount();

        return totalRegisters - parameterRegisters - 1;
    }

    private boolean isInstanceConstructor() {
        return (encodedMethod.accessFlags & AccessFlags.STATIC.getValue()) == 0 &&
               (encodedMethod.accessFlags & AccessFlags.CONSTRUCTOR.getValue()) != 0;
    }

    private boolean isStaticConstructor() {
        return (encodedMethod.accessFlags & AccessFlags.STATIC.getValue()) != 0 &&
               (encodedMethod.accessFlags & AccessFlags.CONSTRUCTOR.getValue()) != 0;
    }

    public AnalyzedInstruction[] makeInstructionArray() {
        AnalyzedInstruction[] instructionArray = new AnalyzedInstruction[instructions.size()];
        for (int i=0; i<instructions.size(); i++) {
            instructionArray[i] = instructions.valueAt(i);
        }
        return instructionArray;
    }

    private static RegisterType[] getParameterTypes(TypeListItem typeListItem, int parameterRegisterCount) {
        assert typeListItem != null;
        assert parameterRegisterCount == typeListItem.getRegisterCount();

        RegisterType[] registerTypes = new RegisterType[parameterRegisterCount];

        int registerNum = 0;
        for (TypeIdItem type: typeListItem.getTypes()) {
            if (type.getRegisterCount() == 2) {
                registerTypes[registerNum++] = RegisterType.getWideRegisterTypeForTypeIdItem(type, true);
                registerTypes[registerNum++] = RegisterType.getWideRegisterTypeForTypeIdItem(type, false);
            } else {
                registerTypes[registerNum] = RegisterType.getRegisterTypeForTypeIdItem(type);
            }
        }

        return registerTypes;
    }

    private int getInstructionAddress(AnalyzedInstruction instruction) {
        return instructions.keyAt(instruction.instructionIndex);
    }

    private void setWideDestinationRegisterTypeAndPropagateChanges(AnalyzedInstruction analyzedInstruction,
                                                                   RegisterType registerType) {
        assert registerType.category == RegisterType.Category.LongLo ||
               registerType.category == RegisterType.Category.DoubleLo;

        checkWideDestinationPair(analyzedInstruction);

        setRegisterTypeAndPropagateChanges(analyzedInstruction, analyzedInstruction.getDestinationRegister(),
                registerType);
        if (registerType.category == RegisterType.Category.LongLo) {
            setRegisterTypeAndPropagateChanges(analyzedInstruction, analyzedInstruction.getDestinationRegister() + 1,
                RegisterType.getRegisterType(RegisterType.Category.LongHi, null));
        } else {
            setRegisterTypeAndPropagateChanges(analyzedInstruction, analyzedInstruction.getDestinationRegister() + 1,
                RegisterType.getRegisterType(RegisterType.Category.DoubleHi, null));
        }
    }

    private void setDestinationRegisterTypeAndPropagateChanges(AnalyzedInstruction analyzedInstruction,
                                                               RegisterType registerType) {
        setRegisterTypeAndPropagateChanges(analyzedInstruction, analyzedInstruction.getDestinationRegister(),
                registerType);
    }

    private void setRegisterTypeAndPropagateChanges(AnalyzedInstruction instruction, int registerNumber,
                                                RegisterType registerType) {

        BitSet changedInstructions = new BitSet(instructions.size());

        boolean changed = instruction.setPostRegisterType(registerNumber, registerType);

        if (!changed || instruction.setsRegister(registerNumber)) {
            return;
        }

        propagateRegisterToSuccessors(instruction, registerNumber, changedInstructions);

        //using a for loop inside the while loop optimizes for the common case of the successors of an instruction
        //occurring after the instruction. Any successors that occur prior to the instruction will be picked up on
        //the next iteration of the while loop.
        //this could also be done recursively, but in large methods it would likely cause very deep recursion,
        //which would requires the user to specify a larger stack size. This isn't really a problem, but it is
        //slightly annoying.
        while (!changedInstructions.isEmpty()) {
            for (int instructionIndex=changedInstructions.nextSetBit(0);
                     instructionIndex>=0;
                     instructionIndex=changedInstructions.nextSetBit(instructionIndex)) {

                changedInstructions.clear(instructionIndex);

                propagateRegisterToSuccessors(instructions.valueAt(instructionIndex), registerNumber,
                        changedInstructions);
            }
        }
    }

    private void propagateRegisterToSuccessors(AnalyzedInstruction instruction, int registerNumber,
                                               BitSet changedInstructions) {
        for (AnalyzedInstruction successor: instruction.successors) {
            if (!successor.setsRegister(registerNumber)) {
                RegisterType registerType = successor.getMergedRegisterTypeFromPredecessors(registerNumber);

                if (registerType.category == RegisterType.Category.UninitRef && instruction.isInvokeInit()) {
                    continue;
                }

                if (successor.setPostRegisterType(registerNumber, registerType)) {
                    changedInstructions.set(successor.instructionIndex);
                    instructionsToVerify.set(successor.instructionIndex);
                }
            }
        }
    }



    private void buildInstructionList() {
        assert encodedMethod != null;
        assert encodedMethod.codeItem != null;
        int registerCount = encodedMethod.codeItem.getRegisterCount();

        startOfMethod = new AnalyzedInstruction(null, -1, registerCount);

        Instruction[] insns = encodedMethod.codeItem.getInstructions();

        instructions = new SparseArray<AnalyzedInstruction>(insns.length);

        //first, create all the instructions and populate the instructionAddresses array
        int currentCodeAddress = 0;
        for (int i=0; i<insns.length; i++) {
            instructions.append(currentCodeAddress, new AnalyzedInstruction(insns[i], i, registerCount));
            assert instructions.indexOfKey(currentCodeAddress) == i;
            currentCodeAddress += insns[i].getSize(currentCodeAddress);
        }

        //next, populate the exceptionHandlers array. The array item for each instruction that can throw an exception
        //and is covered by a try block should be set to a list of the first instructions of each exception handler
        //for the try block covering the instruction
        CodeItem.TryItem[] tries = encodedMethod.codeItem.getTries();
        int triesIndex = 0;
        CodeItem.TryItem currentTry = null;
        AnalyzedInstruction[] currentExceptionHandlers = null;
        AnalyzedInstruction[][] exceptionHandlers = new AnalyzedInstruction[insns.length][];

        for (int i=0; i<instructions.size(); i++) {
            AnalyzedInstruction instruction = instructions.valueAt(i);
            Opcode instructionOpcode = instruction.instruction.opcode;

            //check if we have gone past the end of the current try
            if (currentTry != null) {
                if (currentTry.getStartCodeAddress() + currentTry.getTryLength() <= currentCodeAddress) {
                    currentTry = null;
                    triesIndex++;
                }
            }

            //check if the next try is applicable yet
            if (currentTry == null && triesIndex < tries.length) {
                CodeItem.TryItem tryItem = tries[triesIndex];
                if (tryItem.getStartCodeAddress() <= currentCodeAddress) {
                    assert(tryItem.getStartCodeAddress() + tryItem.getTryLength() > currentCodeAddress);

                    currentTry = tryItem;

                    currentExceptionHandlers = buildExceptionHandlerArray(tryItem);
                }
            }

            //if we're inside a try block, and the instruction can throw an exception, then add the exception handlers
            //for the current instruction
            if (currentTry != null && instructionOpcode.canThrow()) {
                exceptionHandlers[i] = currentExceptionHandlers;
            }
        }

        //finally, populate the successors and predecessors for each instruction
        assert instructions.size() > 0;
        addPredecessorSuccessor(startOfMethod, instructions.valueAt(0), exceptionHandlers);
        startOfMethod.addSuccessor(instructions.valueAt(0));

        for (int i=0; i<instructions.size(); i++) {
            AnalyzedInstruction instruction = instructions.valueAt(i);
            Opcode instructionOpcode = instruction.instruction.opcode;
            int instructionCodeAddress = getInstructionAddress(instruction);

            if (instruction.instruction.opcode.canContinue()) {
                if (i == instructions.size() - 1) {
                    throw new ValidationException("Execution can continue past the last instruction");
                }
                AnalyzedInstruction nextInstruction = instructions.valueAt(i+1);
                addPredecessorSuccessor(instruction, nextInstruction, exceptionHandlers);
            }

            if (instruction instanceof OffsetInstruction) {
                OffsetInstruction offsetInstruction = (OffsetInstruction)instruction;

                if (instructionOpcode == Opcode.PACKED_SWITCH || instructionOpcode == Opcode.SPARSE_SWITCH) {
                    MultiOffsetInstruction switchDataInstruction =
                            (MultiOffsetInstruction)instructions.get(instructionCodeAddress +
                                    offsetInstruction.getTargetAddressOffset()).instruction;
                    for (int targetAddressOffset: switchDataInstruction.getTargets()) {
                        AnalyzedInstruction targetInstruction = instructions.get(instructionCodeAddress +
                                targetAddressOffset);

                        addPredecessorSuccessor(instruction, targetInstruction, exceptionHandlers);
                    }
                } else {
                    int targetAddressOffset = offsetInstruction.getTargetAddressOffset();
                    AnalyzedInstruction targetInstruction = instructions.get(instructionCodeAddress +
                            targetAddressOffset);
                    addPredecessorSuccessor(instruction, targetInstruction, exceptionHandlers);
                }
            }
        }
    }

    private void addPredecessorSuccessor(AnalyzedInstruction predecessor, AnalyzedInstruction successor,
                                                AnalyzedInstruction[][] exceptionHandlers) {
        addPredecessorSuccessor(predecessor, successor, exceptionHandlers, false);
    }

    private void addPredecessorSuccessor(AnalyzedInstruction predecessor, AnalyzedInstruction successor,
                                                AnalyzedInstruction[][] exceptionHandlers, boolean allowMoveException) {

        if (!allowMoveException && successor.instruction.opcode == Opcode.MOVE_EXCEPTION) {
            throw new ValidationException("Execution can pass from the " + predecessor.instruction.opcode.name +
                    " instruction at code address 0x" + Integer.toHexString(getInstructionAddress(predecessor)) +
                    " to the move-exception instruction at address 0x" +
                    Integer.toHexString(getInstructionAddress(successor)));
        }

        if (!predecessor.addSuccessor(successor)) {
            //if predecessor already had successor as a successor, then there's nothing else to do
            return;
        }

        successor.addPredecessor(predecessor);

        //TODO: need to handle the case of monitor-exit as a special case - the exception is thrown *after* the instruction executes
        //if the successor can throw an instruction, then we need to add the exception handlers as additional
        //successors to the predecessor (and then apply this same logic recursively if needed)
        AnalyzedInstruction[] exceptionHandlersForSuccessor = exceptionHandlers[successor.instructionIndex];
        if (exceptionHandlersForSuccessor != null) {
            //the item for this instruction in exceptionHandlersForSuccessor should only be set if this instruction
            //can throw an exception
            assert predecessor.instruction.opcode.canThrow();

            for (AnalyzedInstruction exceptionHandler: exceptionHandlersForSuccessor) {
                addPredecessorSuccessor(predecessor, exceptionHandler, exceptionHandlers, true);
            }
        }
    }

    private AnalyzedInstruction[] buildExceptionHandlerArray(CodeItem.TryItem tryItem) {
        int exceptionHandlerCount = tryItem.encodedCatchHandler.handlers.length;
        int catchAllHandler = tryItem.encodedCatchHandler.getCatchAllHandlerAddress();
        if (catchAllHandler != -1) {
            exceptionHandlerCount++;
        }

        AnalyzedInstruction[] exceptionHandlers = new AnalyzedInstruction[exceptionHandlerCount];
        for (int i=0; i<tryItem.encodedCatchHandler.handlers.length; i++) {
            exceptionHandlers[i] = instructions.get(tryItem.encodedCatchHandler.handlers[i].getHandlerAddress());
        }

        if (catchAllHandler != -1) {
            exceptionHandlers[exceptionHandlers.length - 1] = instructions.get(catchAllHandler);
        }

        return exceptionHandlers;
    }

    private void analyzeInstruction(AnalyzedInstruction analyzedInstruction) {
        Instruction instruction = analyzedInstruction.instruction;

        switch (instruction.opcode) {
            case NOP:
                return;
            case MOVE:
            case MOVE_FROM16:
            case MOVE_16:
                handleMove(analyzedInstruction, Primitive32BitCategories);
                return;
            case MOVE_WIDE:
            case MOVE_WIDE_FROM16:
            case MOVE_WIDE_16:
                handleMoveWide(analyzedInstruction);
                return;
            case MOVE_OBJECT:
            case MOVE_OBJECT_FROM16:
            case MOVE_OBJECT_16:
                handleMove(analyzedInstruction, ReferenceCategories);
                return;
            case MOVE_RESULT:
                handleMoveResult(analyzedInstruction, Primitive32BitCategories);
                return;
            case MOVE_RESULT_WIDE:
                handleMoveResult(analyzedInstruction, WideLowCategories);
                return;
            case MOVE_RESULT_OBJECT:
                handleMoveResult(analyzedInstruction, ReferenceCategories);
                return;
            case MOVE_EXCEPTION:
                handleMoveException(analyzedInstruction);
                return;
            case RETURN_VOID:
                handleReturnVoid(analyzedInstruction);
                return;
            case RETURN:
                handleReturn(analyzedInstruction);
                return;
            case RETURN_WIDE:
                handleReturnWide(analyzedInstruction);
                return;
            case RETURN_OBJECT:
                handleReturnObject(analyzedInstruction);
                return;
            case CONST_4:
            case CONST_16:
            case CONST:
                handleConst(analyzedInstruction);
                return;
            case CONST_HIGH16:
                handleConstHigh16(analyzedInstruction);
                return;
            case CONST_WIDE_16:
            case CONST_WIDE_32:
            case CONST_WIDE:
            case CONST_WIDE_HIGH16:
                handleWideConst(analyzedInstruction);
                return;
            case CONST_STRING:
            case CONST_STRING_JUMBO:
                handleConstString(analyzedInstruction);
                return;
            case CONST_CLASS:
                handleConstClass(analyzedInstruction);
                return;
            case MONITOR_ENTER:
            case MONITOR_EXIT:
                handleMonitor(analyzedInstruction);
                return;
            case CHECK_CAST:
                handleCheckCast(analyzedInstruction);
                return;
            case INSTANCE_OF:
                handleInstanceOf(analyzedInstruction);
                return;
            case ARRAY_LENGTH:
                handleArrayLength(analyzedInstruction);
                return;
            case NEW_INSTANCE:
                handleNewInstance(analyzedInstruction);
                return;
            case NEW_ARRAY:
                handleNewArray(analyzedInstruction);
                return;
            case FILLED_NEW_ARRAY:
                handleFilledNewArray(analyzedInstruction);
                return;
            case FILLED_NEW_ARRAY_RANGE:
                handleFilledNewArrayRange(analyzedInstruction);
                return;
            case FILL_ARRAY_DATA:
                handleFillArrayData(analyzedInstruction);
                return;
            case THROW:
                handleThrow(analyzedInstruction);
                return;
            case GOTO:
            case GOTO_16:
            case GOTO_32:
                //nothing to do
                return;
            case PACKED_SWITCH:
                handleSwitch(analyzedInstruction, Format.PackedSwitchData);
                return;
            case SPARSE_SWITCH:
                handleSwitch(analyzedInstruction, Format.SparseSwitchData);
                return;
            case CMPL_FLOAT:
            case CMPG_FLOAT:
                handleFloatCmp(analyzedInstruction);
                return;
            case CMPL_DOUBLE:
            case CMPG_DOUBLE:
            case CMP_LONG:
                handleWideCmp(analyzedInstruction);
                return;
            case IF_EQ:
            case IF_NE:
                handleIfEqNe(analyzedInstruction);
                return;
            case IF_LT:
            case IF_GE:
            case IF_GT:
            case IF_LE:
                handleIf(analyzedInstruction);
                return;
            case IF_EQZ:
            case IF_NEZ:
                handleIfEqzNez(analyzedInstruction);
                return;
            case IF_LTZ:
            case IF_GEZ:
            case IF_GTZ:
            case IF_LEZ:
                handleIfz(analyzedInstruction);
                return;
            case AGET:
                handle32BitPrimitiveAget(analyzedInstruction, RegisterType.Category.Integer);
                return;
            case AGET_BOOLEAN:
                handle32BitPrimitiveAget(analyzedInstruction, RegisterType.Category.Boolean);
                return;
            case AGET_BYTE:
                handle32BitPrimitiveAget(analyzedInstruction, RegisterType.Category.Byte);
                return;
            case AGET_CHAR:
                handle32BitPrimitiveAget(analyzedInstruction, RegisterType.Category.Char);
                return;
            case AGET_SHORT:
                handle32BitPrimitiveAget(analyzedInstruction, RegisterType.Category.Short);
                return;
            case AGET_WIDE:
                handleAgetWide(analyzedInstruction);
                return;
            case AGET_OBJECT:
                handleAgetObject(analyzedInstruction);
                return;
            case APUT:
                handle32BitPrimitiveAput(analyzedInstruction, RegisterType.Category.Integer);
                return;
            case APUT_BOOLEAN:
                handle32BitPrimitiveAput(analyzedInstruction, RegisterType.Category.Boolean);
                return;
            case APUT_BYTE:
                handle32BitPrimitiveAput(analyzedInstruction, RegisterType.Category.Byte);
                return;
            case APUT_CHAR:
                handle32BitPrimitiveAput(analyzedInstruction, RegisterType.Category.Char);
                return;
            case APUT_SHORT:
                handle32BitPrimitiveAput(analyzedInstruction, RegisterType.Category.Short);
                return;
            case APUT_WIDE:
                handleAputWide(analyzedInstruction);
                return;
            case APUT_OBJECT:
                handleAputObject(analyzedInstruction);
                return;
            case IGET:
                handle32BitPrimitiveIget(analyzedInstruction, RegisterType.Category.Integer);
                return;
            case IGET_BOOLEAN:
                handle32BitPrimitiveIget(analyzedInstruction, RegisterType.Category.Boolean);
                return;
            case IGET_BYTE:
                handle32BitPrimitiveIget(analyzedInstruction, RegisterType.Category.Byte);
                return;
            case IGET_CHAR:
                handle32BitPrimitiveIget(analyzedInstruction, RegisterType.Category.Char);
                return;
            case IGET_SHORT:
                handle32BitPrimitiveIget(analyzedInstruction, RegisterType.Category.Short);
                return;
            case IGET_WIDE:
                handleIgetWide(analyzedInstruction);
                return;
            case IGET_OBJECT:
                handleIgetObject(analyzedInstruction);
                return;
            case IPUT:
                handle32BitPrimitiveIput(analyzedInstruction, RegisterType.Category.Integer);
                return;
            case IPUT_BOOLEAN:
                handle32BitPrimitiveIput(analyzedInstruction, RegisterType.Category.Boolean);
                return;
            case IPUT_BYTE:
                handle32BitPrimitiveIput(analyzedInstruction, RegisterType.Category.Byte);
                return;
            case IPUT_CHAR:
                handle32BitPrimitiveIput(analyzedInstruction, RegisterType.Category.Char);
                return;
            case IPUT_SHORT:
                handle32BitPrimitiveIput(analyzedInstruction, RegisterType.Category.Short);
                return;
            case IPUT_WIDE:
                handleIputWide(analyzedInstruction);
                return;
            case IPUT_OBJECT:
                handleIputObject(analyzedInstruction);
                return;
            case SGET:
                handle32BitPrimitiveSget(analyzedInstruction, RegisterType.Category.Integer);
                return;
            case SGET_BOOLEAN:
                handle32BitPrimitiveSget(analyzedInstruction, RegisterType.Category.Boolean);
                return;
            case SGET_BYTE:
                handle32BitPrimitiveSget(analyzedInstruction, RegisterType.Category.Byte);
                return;
            case SGET_CHAR:
                handle32BitPrimitiveSget(analyzedInstruction, RegisterType.Category.Char);
                return;
            case SGET_SHORT:
                handle32BitPrimitiveSget(analyzedInstruction, RegisterType.Category.Short);
                return;
            case SGET_WIDE:
                handleSgetWide(analyzedInstruction);
                return;
            case SGET_OBJECT:
                handleSgetObject(analyzedInstruction);
                return;
            case SPUT:
                handle32BitPrimitiveSput(analyzedInstruction, RegisterType.Category.Integer);
                return;
            case SPUT_BOOLEAN:
                handle32BitPrimitiveSput(analyzedInstruction, RegisterType.Category.Boolean);
                return;
            case SPUT_BYTE:
                handle32BitPrimitiveSput(analyzedInstruction, RegisterType.Category.Byte);
                return;
            case SPUT_CHAR:
                handle32BitPrimitiveSput(analyzedInstruction, RegisterType.Category.Char);
                return;
            case SPUT_SHORT:
                handle32BitPrimitiveSput(analyzedInstruction, RegisterType.Category.Short);
                return;
            case SPUT_WIDE:
                handleSputWide(analyzedInstruction);
                return;
            case SPUT_OBJECT:
                handleSputObject(analyzedInstruction);
                return;
            case INVOKE_VIRTUAL:
                handleInvoke(analyzedInstruction, INVOKE_VIRTUAL);
                return;
            case INVOKE_SUPER:
                handleInvoke(analyzedInstruction, INVOKE_SUPER);
                return;
            case INVOKE_DIRECT:
                handleInvoke(analyzedInstruction, INVOKE_DIRECT);
                return;
            case INVOKE_STATIC:
                handleInvoke(analyzedInstruction, INVOKE_STATIC);
                return;
            case INVOKE_INTERFACE:
                handleInvoke(analyzedInstruction, INVOKE_INTERFACE);
                return;
            case INVOKE_VIRTUAL_RANGE:
                handleInvokeRange(analyzedInstruction, INVOKE_VIRTUAL);
                return;
            case INVOKE_SUPER_RANGE:
                handleInvokeRange(analyzedInstruction, INVOKE_SUPER);
                return;
            case INVOKE_DIRECT_RANGE:
                handleInvokeRange(analyzedInstruction, INVOKE_DIRECT);
                return;
            case INVOKE_STATIC_RANGE:
                handleInvokeRange(analyzedInstruction, INVOKE_STATIC);
                return;
            case INVOKE_INTERFACE_RANGE:
                handleInvokeRange(analyzedInstruction, INVOKE_INTERFACE);
                return;
        }
    }

    private static final EnumSet<RegisterType.Category> Primitive32BitCategories = EnumSet.of(
            RegisterType.Category.Null,
            RegisterType.Category.Boolean,
            RegisterType.Category.Byte,
            RegisterType.Category.Short,
            RegisterType.Category.Char,
            RegisterType.Category.Integer,
            RegisterType.Category.Float);

    private static final EnumSet<RegisterType.Category> WideLowCategories = EnumSet.of(
            RegisterType.Category.LongLo,
            RegisterType.Category.DoubleLo);

    private static final EnumSet<RegisterType.Category> WideHighCategories = EnumSet.of(
            RegisterType.Category.LongHi,
            RegisterType.Category.DoubleHi);

    private static final EnumSet<RegisterType.Category> ReferenceCategories = EnumSet.of(
            RegisterType.Category.Null,
            RegisterType.Category.Reference);

    private void handleMove(AnalyzedInstruction analyzedInstruction,
                               EnumSet<RegisterType.Category> allowedCategories) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        //get the "pre-instruction" register type for the source register
        RegisterType sourceRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert sourceRegisterType != null;

        checkRegister(sourceRegisterType, allowedCategories);

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, sourceRegisterType);
    }

    private void handleMoveWide(AnalyzedInstruction analyzedInstruction) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        RegisterType sourceRegisterType = getAndCheckWideSourcePair(analyzedInstruction,
                instruction.getRegisterB());
        assert sourceRegisterType != null;

        checkWideDestinationPair(analyzedInstruction);

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, sourceRegisterType);
    }

    private void handleMoveResult(AnalyzedInstruction analyzedInstruction,
                                     EnumSet<RegisterType.Category> allowedCategories) {

        //TODO: handle the case when the previous instruction is an odexed instruction

        if (analyzedInstruction.instructionIndex == 0) {
            throw new ValidationException(analyzedInstruction.instruction.opcode.name + " cannot be the first " +
                    "instruction in a method. It must occur after an invoke-*/fill-new-array instruction");
        }

        AnalyzedInstruction previousInstruction = instructions.valueAt(analyzedInstruction.instructionIndex-1);

        if (!previousInstruction.instruction.opcode.setsResult()) {
            throw new ValidationException(analyzedInstruction.instruction.opcode.name + " must occur after an " +
                    "invoke-*/fill-new-array instruction");
        }

        if (analyzedInstruction.instruction.opcode.setsWideRegister()) {
            checkWideDestinationPair(analyzedInstruction);
        }

        //TODO: does dalvik allow a move-result after an invoke with a void return type?
        RegisterType destinationRegisterType;

        InstructionWithReference invokeInstruction = (InstructionWithReference)previousInstruction.instruction;
        Item item = invokeInstruction.getReferencedItem();

        if (item instanceof MethodIdItem) {
            destinationRegisterType = RegisterType.getRegisterTypeForTypeIdItem(
                    ((MethodIdItem)item).getPrototype().getReturnType());
        } else {
            assert item instanceof TypeIdItem;
            destinationRegisterType = RegisterType.getRegisterTypeForTypeIdItem((TypeIdItem)item);
        }

        checkRegister(destinationRegisterType, allowedCategories);
        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, destinationRegisterType);
    }

    private void handleMoveException(AnalyzedInstruction analyzedInstruction) {
        CodeItem.TryItem[] tries = encodedMethod.codeItem.getTries();
        int instructionAddress = getInstructionAddress(analyzedInstruction);

        if (tries == null) {
            throw new ValidationException("move-exception must be the first instruction in an exception handler block");
        }

        RegisterType exceptionType = null;

        for (CodeItem.TryItem tryItem: encodedMethod.codeItem.getTries()) {
            if (tryItem.encodedCatchHandler.getCatchAllHandlerAddress() == instructionAddress) {
                exceptionType = RegisterType.getRegisterType(RegisterType.Category.Reference,
                        ClassPath.getClassDef("Ljava/lang/Throwable;"));
                break;
            }
            for (CodeItem.EncodedTypeAddrPair handler: tryItem.encodedCatchHandler.handlers) {
                if (handler.getHandlerAddress() == instructionAddress) {
                    exceptionType = RegisterType.getRegisterTypeForTypeIdItem(handler.exceptionType)
                            .merge(exceptionType);
                }
            }
        }

        //TODO: check if the type is a throwable. Should we throw a ValidationException or print a warning? (does dalvik validate that it's a throwable? It doesn't in CodeVerify.c, but it might check in DexSwapVerify.c)
        checkRegister(exceptionType, ReferenceCategories);
        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, exceptionType);
    }

    private void checkConstructorReturn(AnalyzedInstruction analyzedInstruction) {
        assert this.isInstanceConstructor();

        //if we're in an instance constructor (an <init> method), then the superclass <init> must have been called.
        //When execution enters the method, the "this" register is set as an uninitialized reference to the containing
        //class. Once the superclass' <init> is called, the "this" register is upgraded to a full-blown reference type,
        //so we need to ensure that the "this" register isn't an uninitialized reference

        int thisRegister = getThisRegister();
        RegisterType thisRegisterType = analyzedInstruction.postRegisterMap[thisRegister];

        if (thisRegisterType.category == RegisterType.Category.UninitRef) {
            throw new ValidationException("Returning from constructor without calling the superclass' <init>");
        }
        assert thisRegisterType.category == RegisterType.Category.Reference;
        assert thisRegisterType.type == ClassPath.getClassDef(encodedMethod.method.getContainingClass());
    }

    private void handleReturnVoid(AnalyzedInstruction analyzedInstruction) {
        if (this.isInstanceConstructor()) {
            checkConstructorReturn(analyzedInstruction);
        }

        TypeIdItem returnType = encodedMethod.method.getPrototype().getReturnType();
        if (returnType.getTypeDescriptor().charAt(0) != 'V') {
            //TODO: could add which return-* variation should be used instead
            throw new ValidationException("Cannot use return-void with a non-void return type (" +
                returnType.getTypeDescriptor() + ")");
        }
    }

    private void handleReturn(AnalyzedInstruction analyzedInstruction) {
        if (this.isInstanceConstructor()) {
            checkConstructorReturn(analyzedInstruction);
        }

        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;
        RegisterType returnRegisterType = analyzedInstruction.postRegisterMap[instruction.getRegisterA()];

        checkRegister(returnRegisterType, Primitive32BitCategories);

        TypeIdItem returnType = encodedMethod.method.getPrototype().getReturnType();
        if (returnType.getTypeDescriptor().charAt(0) == 'V') {
            throw new ValidationException("Cannot use return with a void return type. Use return-void instead");
        }

        RegisterType registerType = RegisterType.getRegisterTypeForTypeIdItem(returnType);

        if (!Primitive32BitCategories.contains(registerType.category)) {
            //TODO: could add which return-* variation should be used instead
            throw new ValidationException("Cannot use return with return type " + returnType.getTypeDescriptor());
        }
    }

    private void handleReturnWide(AnalyzedInstruction analyzedInstruction) {
        if (this.isInstanceConstructor()) {
            checkConstructorReturn(analyzedInstruction);
        }

        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;
        RegisterType returnType = getAndCheckWideSourcePair(analyzedInstruction, instruction.getRegisterA());

        TypeIdItem returnTypeIdItem = encodedMethod.method.getPrototype().getReturnType();
        if (returnTypeIdItem.getTypeDescriptor().charAt(0) == 'V') {
            throw new ValidationException("Cannot use return-wide with a void return type. Use return-void instead");
        }

        returnType = RegisterType.getRegisterTypeForTypeIdItem(returnTypeIdItem);
        if (!WideLowCategories.contains(returnType.category)) {
            //TODO: could add which return-* variation should be used instead
            throw new ValidationException("Cannot use return-wide with return type " +
                    returnTypeIdItem.getTypeDescriptor());
        }
    }

    private void handleReturnObject(AnalyzedInstruction analyzedInstruction) {
        if (this.isInstanceConstructor()) {
            checkConstructorReturn(analyzedInstruction);
        }

        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;
        int returnRegister = instruction.getRegisterA();
        RegisterType returnRegisterType = analyzedInstruction.postRegisterMap[returnRegister];

        checkRegister(returnRegisterType, ReferenceCategories);

        TypeIdItem returnTypeIdItem = encodedMethod.method.getPrototype().getReturnType();
        if (returnTypeIdItem.getTypeDescriptor().charAt(0) == 'V') {
            throw new ValidationException("Cannot use return with a void return type. Use return-void instead");
        }

        RegisterType returnType = RegisterType.getRegisterTypeForTypeIdItem(returnTypeIdItem);

        if (!ReferenceCategories.contains(returnType.category)) {
            //TODO: could add which return-* variation should be used instead
            throw new ValidationException("Cannot use " + analyzedInstruction + " with return type " +
                    returnTypeIdItem.getTypeDescriptor());
        }

        if (returnType.type.isInterface()) {
            if (!returnRegisterType.type.implementsInterface(returnType.type)) {
                //TODO: how to handle warnings?
            }
        } else {
            if (!returnRegisterType.type.extendsClass(returnType.type)) {
                throw new ValidationException("The return value in register v" + Integer.toString(returnRegister) +
                        "(" + returnRegisterType.type.getClassType() + ") is not compatible with the method's return " +
                        "type (" + returnType.type.getClassType() + ")");
            }
        }
    }

    private void handleConst(AnalyzedInstruction analyzedInstruction) {
        LiteralInstruction instruction = (LiteralInstruction)analyzedInstruction.instruction;

        RegisterType newDestinationRegisterType = RegisterType.getRegisterTypeForLiteral(instruction.getLiteral());

        //we assume that the literal value is a valid value for the given instruction type, because it's impossible
        //to store an invalid literal with the instruction. so we don't need to check the type of the literal
        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, newDestinationRegisterType);
    }

    private void handleConstHigh16(AnalyzedInstruction analyzedInstruction) {
        LiteralInstruction instruction = (LiteralInstruction)analyzedInstruction.instruction;

        //TODO: test this
        long literalValue = instruction.getLiteral() << 16;
        RegisterType newDestinationRegisterType = RegisterType.getRegisterTypeForLiteral(literalValue);

        //we assume that the literal value is a valid value for the given instruction type, because it's impossible
        //to store an invalid literal with the instruction. so we don't need to check the type of the literal
        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, newDestinationRegisterType);
    }

    private void handleWideConst(AnalyzedInstruction analyzedInstruction) {
        setWideDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                RegisterType.getRegisterType(RegisterType.Category.LongLo, null));
    }

    private void handleConstString(AnalyzedInstruction analyzedInstruction) {
        ClassPath.ClassDef stringClassDef = ClassPath.getClassDef("Ljava/lang/String;");
        RegisterType stringType = RegisterType.getRegisterType(RegisterType.Category.Reference, stringClassDef);
        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, stringType);
    }

    private void handleConstClass(AnalyzedInstruction analyzedInstruction) {
        ClassPath.ClassDef classClassDef = ClassPath.getClassDef("Ljava/lang/Class;");
        RegisterType classType = RegisterType.getRegisterType(RegisterType.Category.Reference, classClassDef);

        InstructionWithReference instruction = (InstructionWithReference)analyzedInstruction.instruction;
        Item item = instruction.getReferencedItem();
        assert item.getItemType() == ItemType.TYPE_TYPE_ID_ITEM;

        //make sure the referenced class is resolvable
        //TODO: need to check class access
        ClassPath.ClassDef classDef = ClassPath.getClassDef((TypeIdItem)item);
    }

    private void handleMonitor(AnalyzedInstruction analyzedInstruction) {
        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction;

        RegisterType registerType = analyzedInstruction.postRegisterMap[instruction.getRegisterA()];
        assert registerType != null;

        checkRegister(registerType, ReferenceCategories);
    }

    private void handleCheckCast(AnalyzedInstruction analyzedInstruction) {
        {
            //ensure the "source" register is a reference type
            SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;

            RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
            assert registerType != null;

            checkRegister(registerType, ReferenceCategories);
        }

        {
            //resolve and verify the class that we're casting to
            InstructionWithReference instruction = (InstructionWithReference)analyzedInstruction.instruction;

            Item item = instruction.getReferencedItem();
            assert item.getItemType() == ItemType.TYPE_TYPE_ID_ITEM;

            //TODO: need to check class access
            RegisterType newDestinationRegisterType = RegisterType.getRegisterTypeForTypeIdItem((TypeIdItem)item);
            try {
                checkRegister(newDestinationRegisterType, ReferenceCategories);
            } catch (ValidationException ex) {
                //TODO: verify that dalvik allows a non-reference type..
                //TODO: print a warning, but don't re-throw the exception. dalvik allows a non-reference type during validation (but throws an exception at runtime)
            }

            setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, newDestinationRegisterType);
        }
    }

    private void handleInstanceOf(AnalyzedInstruction analyzedInstruction) {
        {
            //ensure the register that is being checks is a reference type
            TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction;

            RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
            assert registerType != null;

            checkRegister(registerType, ReferenceCategories);
        }

        {
            //resolve and verify the class that we're checking against
            InstructionWithReference instruction = (InstructionWithReference)analyzedInstruction.instruction;

            Item item = instruction.getReferencedItem();
            assert  item.getItemType() == ItemType.TYPE_TYPE_ID_ITEM;
            RegisterType registerType = RegisterType.getRegisterTypeForTypeIdItem((TypeIdItem)item);
            checkRegister(registerType, ReferenceCategories);

            //TODO: is it valid to use an array type?

            //TODO: could probably do an even more sophisticated check, where we check the possible register types against the specified type. In some cases, we could determine that it always fails, and print a warning to that effect.
            setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                    RegisterType.getRegisterType(RegisterType.Category.Boolean, null));
        }
    }

    private void handleArrayLength(AnalyzedInstruction analyzedInstruction) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction;

        int arrayRegisterNumber = instruction.getRegisterB();
        RegisterType arrayRegisterType = analyzedInstruction.getPreInstructionRegisterType(arrayRegisterNumber);
        assert arrayRegisterType != null;

        assert arrayRegisterType.type instanceof ClassPath.ArrayClassDef;

        checkRegister(arrayRegisterType, ReferenceCategories);
        if (arrayRegisterType.type != null) {
            if (arrayRegisterType.type.getClassType().charAt(0) != '[') {
                throw new ValidationException("Cannot use array-length with non-array type " +
                        arrayRegisterType.type.getClassType());
            }
        }

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                RegisterType.getRegisterType(RegisterType.Category.Integer, null));
    }

    private void handleNewInstance(AnalyzedInstruction analyzedInstruction) {
        InstructionWithReference instruction = (InstructionWithReference)analyzedInstruction.instruction;

        int register = ((SingleRegisterInstruction)analyzedInstruction.instruction).getRegisterA();
        RegisterType destRegisterType = analyzedInstruction.postRegisterMap[register];
        if (destRegisterType.category != RegisterType.Category.Unknown) {
            assert destRegisterType.category == RegisterType.Category.UninitRef;

            //the "post-instruction" destination register will only be set if we've gone over
            //this instruction at least once before. If this is the case, then we need to check
            //all the other registers, and make sure that none of them contain the same
            //uninitialized reference that is in the destination register.

            for (int i=0; i<analyzedInstruction.postRegisterMap.length; i++) {
                if (i==register) {
                    continue;
                }

                if (analyzedInstruction.getPreInstructionRegisterType(i) == destRegisterType) {
                    throw new ValidationException(String.format("Register v%d contains an uninitialized reference " +
                            "that was created by this new-instance instruction.", i));
                }
            }
        }

        Item item = instruction.getReferencedItem();
        assert item.getItemType() == ItemType.TYPE_TYPE_ID_ITEM;

        //TODO: need to check class access
        RegisterType classType = RegisterType.getRegisterTypeForTypeIdItem((TypeIdItem)item);
        checkRegister(classType, ReferenceCategories);
        if (((TypeIdItem)item).getTypeDescriptor().charAt(0) == '[') {
            throw new ValidationException("Cannot use array type \"" + ((TypeIdItem)item).getTypeDescriptor() +
                    "\" with new-instance. Use new-array instead.");
        }

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                RegisterType.getUnitializedReference(classType.type));
    }

    private void handleNewArray(AnalyzedInstruction analyzedInstruction) {
        {
            TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

            int sizeRegister = instruction.getRegisterB();
            RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(sizeRegister);
            assert registerType != null;

            checkRegister(registerType, Primitive32BitCategories);
        }

        InstructionWithReference instruction = (InstructionWithReference)analyzedInstruction.instruction;

        Item item = instruction.getReferencedItem();
        assert item.getItemType() == ItemType.TYPE_TYPE_ID_ITEM;

        RegisterType arrayType = RegisterType.getRegisterTypeForTypeIdItem((TypeIdItem)item);
        assert arrayType.type instanceof ClassPath.ArrayClassDef;

        checkRegister(arrayType, ReferenceCategories);
        if (arrayType.type.getClassType().charAt(0) != '[') {
            throw new ValidationException("Cannot use non-array type \"" + arrayType.type.getClassType() +
                    "\" with new-array. Use new-instance instead.");
        }

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, arrayType);
    }

    private static interface RegisterIterator {
        int getRegister();
        boolean moveNext();
        int getCount();
        boolean pastEnd();
    }

    private static class Format35cRegisterIterator implements RegisterIterator {
        private final int registerCount;
        private final int[] registers;
        private int currentRegister = 0;

        public Format35cRegisterIterator(FiveRegisterInstruction instruction) {
            registerCount = instruction.getRegCount();
            registers = new int[]{instruction.getRegisterD(), instruction.getRegisterE(),
                                  instruction.getRegisterF(), instruction.getRegisterG(),
                                  instruction.getRegisterA()};
        }

        public int getRegister() {
            return registers[currentRegister];
        }

        public boolean moveNext() {
            currentRegister++;
            return pastEnd();
        }

        public int getCount() {
            return registerCount;
        }

        public boolean pastEnd() {
            return currentRegister >= registerCount;
        }
    }

    private static class Format3rcRegisterIterator implements RegisterIterator {
        private final int startRegister;
        private final int registerCount;
        private int currentRegister = 0;

        public Format3rcRegisterIterator(RegisterRangeInstruction instruction) {
            startRegister = instruction.getStartRegister();
            registerCount = instruction.getRegCount();
        }

        public int getRegister() {
            return startRegister + currentRegister;
        }

        public boolean moveNext() {
            currentRegister++;
            return pastEnd();
        }

        public int getCount() {
            return registerCount;
        }

        public boolean pastEnd() {
            return currentRegister >= registerCount;
        }
    }

    private void handleFilledNewArrayCommon(AnalyzedInstruction analyzedInstruction,
                                               RegisterIterator registerIterator) {
        InstructionWithReference instruction = (InstructionWithReference)analyzedInstruction.instruction;

        RegisterType arrayType;
        RegisterType arrayImmediateElementType;

        Item item = instruction.getReferencedItem();
        assert  item.getItemType() == ItemType.TYPE_TYPE_ID_ITEM;

        ClassPath.ClassDef classDef = ClassPath.getClassDef((TypeIdItem)item);

        if (classDef.getClassType().charAt(0) != '[') {
            throw new ValidationException("Cannot use non-array type \"" + classDef.getClassType() +
                "\" with new-array. Use new-instance instead.");
        }

        ClassPath.ArrayClassDef arrayClassDef = (ClassPath.ArrayClassDef)classDef;
        arrayType = RegisterType.getRegisterType(RegisterType.Category.Reference, classDef);
        arrayImmediateElementType = RegisterType.getRegisterTypeForType(
                arrayClassDef.getImmediateElementClass().getClassType());
        String baseElementType = arrayClassDef.getBaseElementClass().getClassType();
        if (baseElementType.charAt(0) == 'J' || baseElementType.charAt(0) == 'D') {
            throw new ValidationException("Cannot use filled-new-array to create an array of wide values " +
                    "(long or double)");
        }

        do {
            int register = registerIterator.getRegister();
            RegisterType elementType = analyzedInstruction.getPreInstructionRegisterType(register);
            assert elementType != null;

            if (!elementType.canBeAssignedTo(arrayImmediateElementType)) {
                throw new ValidationException("Register v" + Integer.toString(register) + " is of type " +
                        elementType.toString() + " and is incompatible with the array type " +
                        arrayType.type.getClassType());
            }
        } while (registerIterator.moveNext());

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, arrayType);
    }

    private void handleFilledNewArray(AnalyzedInstruction analyzedInstruction) {
        FiveRegisterInstruction instruction = (FiveRegisterInstruction)analyzedInstruction.instruction;
        handleFilledNewArrayCommon(analyzedInstruction, new Format35cRegisterIterator(instruction));
    }

    private void handleFilledNewArrayRange(AnalyzedInstruction analyzedInstruction) {
        RegisterRangeInstruction instruction = (RegisterRangeInstruction)analyzedInstruction.instruction;

        //instruction.getStartRegister() and instruction.getRegCount() both return an int value, but are actually
        //unsigned 16 bit values, so we don't have to worry about overflowing an int when adding them together
        if (instruction.getStartRegister() + instruction.getRegCount() >= 1<<16) {
            throw new ValidationException(String.format("Invalid register range {v%d .. v%d}. The ending register " +
                    "is larger than the largest allowed register of v65535.",
                    instruction.getStartRegister(),
                    instruction.getStartRegister() + instruction.getRegCount() - 1));
        }

        handleFilledNewArrayCommon(analyzedInstruction, new Format3rcRegisterIterator(instruction));
    }

    private void handleFillArrayData(AnalyzedInstruction analyzedInstruction) {
        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;

        int register = instruction.getRegisterA();
        RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(register);
        assert registerType != null;

        if (registerType.category == RegisterType.Category.Null) {
            return;
        }

        if (registerType.category != RegisterType.Category.Reference) {
            throw new ValidationException(String.format("Cannot use fill-array-data with non-array register v%d of " +
                    "type %s", register, registerType.toString()));
        }

        assert registerType.type instanceof ClassPath.ArrayClassDef;
        ClassPath.ArrayClassDef arrayClassDef = (ClassPath.ArrayClassDef)registerType.type;

        if (arrayClassDef.getArrayDimensions() != 1) {
            throw new ValidationException(String.format("Cannot use fill-array-data with array type %s. It can only " +
                    "be used with a one-dimensional array of primitives.", arrayClassDef.getClassType()));
        }

        int elementWidth;
        switch (arrayClassDef.getBaseElementClass().getClassType().charAt(0)) {
            case 'Z':
            case 'B':
                elementWidth = 1;
                break;
            case 'C':
            case 'S':
                elementWidth = 2;
                break;
            case 'I':
            case 'F':
                elementWidth = 4;
                break;
            case 'J':
            case 'D':
                elementWidth = 8;
                break;
            default:
                throw new ValidationException(String.format("Cannot use fill-array-data with array type %s. It can " +
                        "only be used with a one-dimensional array of primitives.", arrayClassDef.getClassType()));
        }


        int arrayDataAddressOffset = ((OffsetInstruction)analyzedInstruction.instruction).getTargetAddressOffset();
        int arrayDataCodeAddress = getInstructionAddress(analyzedInstruction) + arrayDataAddressOffset;
        AnalyzedInstruction arrayDataInstruction = this.instructions.get(arrayDataCodeAddress);
        if (arrayDataInstruction == null || arrayDataInstruction.instruction.getFormat() != Format.ArrayData) {
            throw new ValidationException(String.format("Could not find an array data structure at code address 0x%x",
                    arrayDataCodeAddress));
        }

        ArrayDataPseudoInstruction arrayDataPseudoInstruction =
                (ArrayDataPseudoInstruction)arrayDataInstruction.instruction;

        if (elementWidth != arrayDataPseudoInstruction.getElementWidth()) {
            throw new ValidationException(String.format("The array data at code address 0x%x does not have the " +
                    "correct element width for array type %s. Expecting element width %d, got element width %d.",
                    arrayDataCodeAddress, arrayClassDef.getClassType(), elementWidth,
                    arrayDataPseudoInstruction.getElementWidth()));
        }
    }

    private void handleThrow(AnalyzedInstruction analyzedInstruction) {
        int register = ((SingleRegisterInstruction)analyzedInstruction.instruction).getRegisterA();

        RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(register);
        assert registerType != null;

        if (registerType.category == RegisterType.Category.Null) {
            return;
        }

        if (registerType.category != RegisterType.Category.Reference) {
            throw new ValidationException(String.format("Cannot use throw with non-reference type %s in register v%d",
                    registerType.toString(), register));
        }

        assert registerType.type != null;

        if (!registerType.type.extendsClass(ClassPath.getClassDef("Ljava/lang/Throwable;"))) {
            throw new ValidationException(String.format("Cannot use throw with non-throwable type %s in register v%d",
                    registerType.type.getClassType(), register));
        }
    }

    private void handleSwitch(AnalyzedInstruction analyzedInstruction, Format expectedSwitchDataFormat) {
        int register = ((SingleRegisterInstruction)analyzedInstruction.instruction).getRegisterA();
        int switchCodeAddressOffset = ((OffsetInstruction)analyzedInstruction.instruction).getTargetAddressOffset();

        RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(register);
        assert registerType != null;

        checkRegister(registerType, Primitive32BitCategories);

        int switchDataCodeAddress = this.getInstructionAddress(analyzedInstruction) + switchCodeAddressOffset;
        AnalyzedInstruction switchDataAnalyzedInstruction = instructions.get(switchDataCodeAddress);

        if (switchDataAnalyzedInstruction == null ||
            switchDataAnalyzedInstruction.instruction.getFormat() != expectedSwitchDataFormat) {
            throw new ValidationException(String.format("There is no %s structure at code address 0x%x",
                    expectedSwitchDataFormat.name(), switchDataCodeAddress));
        }
    }

    private void handleFloatCmp(AnalyzedInstruction analyzedInstruction) {
        ThreeRegisterInstruction instruction = (ThreeRegisterInstruction)analyzedInstruction.instruction;

        RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert registerType != null;

        checkRegister(registerType, Primitive32BitCategories);

        registerType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterC());
        assert registerType != null;

        checkRegister(registerType, Primitive32BitCategories);

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                RegisterType.getRegisterType(RegisterType.Category.Byte, null));
    }

    private void handleWideCmp(AnalyzedInstruction analyzedInstruction) {
        ThreeRegisterInstruction instruction = (ThreeRegisterInstruction)analyzedInstruction.instruction;

        RegisterType registerType = getAndCheckWideSourcePair(analyzedInstruction, instruction.getRegisterB());
        assert registerType != null;

        registerType = getAndCheckWideSourcePair(analyzedInstruction, instruction.getRegisterC());
        assert registerType != null;

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                RegisterType.getRegisterType(RegisterType.Category.Byte, null));
    }

    private void handleIfEqNe(AnalyzedInstruction analyzedInstruction) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        RegisterType registerType1 = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert registerType1 != null;

        RegisterType registerType2 = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert registerType2 != null;

        if (!(
                (ReferenceCategories.contains(registerType1.category) &&
                ReferenceCategories.contains(registerType2.category))
                    ||
                (Primitive32BitCategories.contains(registerType1.category) &&
                Primitive32BitCategories.contains(registerType2.category))
              )) {

            throw new ValidationException(String.format("%s cannot be used on registers of dissimilar types %s and " +
                    "%s. They must both be a reference type or a primitive 32 bit type.",
                    analyzedInstruction.instruction.opcode.name, registerType1.toString(), registerType2.toString()));
        }
    }

    private void handleIf(AnalyzedInstruction analyzedInstruction) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert registerType != null;

        checkRegister(registerType, Primitive32BitCategories);

        registerType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert registerType != null;

        checkRegister(registerType, Primitive32BitCategories);
    }

    private void handleIfEqzNez(AnalyzedInstruction analyzedInstruction) {
        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;

        RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert registerType != null;

        if (!ReferenceCategories.contains(registerType.category) &&
            !Primitive32BitCategories.contains(registerType.category)) {
            throw new ValidationException(String.format("%s cannot be used with register type %s. Expecting 32-bit " +
                    "primitive type or reference type.", analyzedInstruction.instruction.opcode));
        }
    }

    private void handleIfz(AnalyzedInstruction analyzedInstruction) {
        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;

        RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert registerType != null;

        checkRegister(registerType, Primitive32BitCategories);
    }

    private void handle32BitPrimitiveAget(AnalyzedInstruction analyzedInstruction,
                                             RegisterType.Category instructionCategory) {
        ThreeRegisterInstruction instruction = (ThreeRegisterInstruction)analyzedInstruction.instruction;

        RegisterType indexRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterC());
        assert indexRegisterType != null;
        checkRegister(indexRegisterType, Primitive32BitCategories);

        RegisterType arrayRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert arrayRegisterType != null;

        if (arrayRegisterType.category != RegisterType.Category.Null) {
            if (arrayRegisterType.category != RegisterType.Category.Reference) {
                throw new ValidationException(String.format("Cannot use %s with non-array type %s",
                        analyzedInstruction.instruction.opcode.name, arrayRegisterType.category.toString()));
            }

            assert arrayRegisterType.type != null;
            if (arrayRegisterType.type.getClassType().charAt(0) != '[') {
                throw new ValidationException(String.format("Cannot use %s with non-array type %s",
                        analyzedInstruction.instruction.opcode.name, arrayRegisterType.type.getClassType()));
            }

            assert arrayRegisterType.type instanceof ClassPath.ArrayClassDef;
            ClassPath.ArrayClassDef arrayClassDef = (ClassPath.ArrayClassDef)arrayRegisterType.type;

            if (arrayClassDef.getArrayDimensions() != 1) {
                throw new ValidationException(String.format("Cannot use %s with multi-dimensional array type %s",
                        analyzedInstruction.instruction.opcode.name, arrayRegisterType.type.getClassType()));
            }

            RegisterType arrayBaseType =
                    RegisterType.getRegisterTypeForType(arrayClassDef.getBaseElementClass().getClassType());
            if (checkArrayFieldAssignment(arrayBaseType.category, instructionCategory)) {
                throw new ValidationException(String.format("Cannot use %s with array type %s. Incorrect array type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        arrayRegisterType.type.getClassType()));
            }
        }

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                RegisterType.getRegisterType(instructionCategory, null));
    }

    private void handleAgetWide(AnalyzedInstruction analyzedInstruction) {
        ThreeRegisterInstruction instruction = (ThreeRegisterInstruction)analyzedInstruction.instruction;

        RegisterType indexRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterC());
        assert indexRegisterType != null;
        checkRegister(indexRegisterType, Primitive32BitCategories);

        RegisterType arrayRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert arrayRegisterType != null;

        if (arrayRegisterType.category != RegisterType.Category.Null) {
            if (arrayRegisterType.category != RegisterType.Category.Reference) {
                throw new ValidationException(String.format("Cannot use aget-wide with non-array type %s",
                        arrayRegisterType.category.toString()));
            }

            assert arrayRegisterType.type != null;
            if (arrayRegisterType.type.getClassType().charAt(0) != '[') {
                throw new ValidationException(String.format("Cannot use aget-wide with non-array type %s",
                        arrayRegisterType.type.getClassType()));
            }

            assert arrayRegisterType.type instanceof ClassPath.ArrayClassDef;
            ClassPath.ArrayClassDef arrayClassDef = (ClassPath.ArrayClassDef)arrayRegisterType.type;

            if (arrayClassDef.getArrayDimensions() != 1) {
                throw new ValidationException(String.format("Cannot use aget-wide with multi-dimensional array type %s",
                        arrayRegisterType.type.getClassType()));
            }

            char arrayBaseType = arrayClassDef.getBaseElementClass().getClassType().charAt(0);
            if (arrayBaseType == 'J') {
                setWideDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                        RegisterType.getRegisterType(RegisterType.Category.LongLo, null));
            } else if (arrayBaseType == 'D') {
                setWideDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                        RegisterType.getRegisterType(RegisterType.Category.DoubleLo, null));
            } else {
                throw new ValidationException(String.format("Cannot use aget-wide with array type %s. Incorrect " +
                        "array type for the instruction.", arrayRegisterType.type.getClassType()));
            }
        } else {
            setWideDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                        RegisterType.getRegisterType(RegisterType.Category.LongLo, null));
        }
    }

    private void handleAgetObject(AnalyzedInstruction analyzedInstruction) {
        ThreeRegisterInstruction instruction = (ThreeRegisterInstruction)analyzedInstruction.instruction;

        RegisterType indexRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterC());
        assert indexRegisterType != null;
        checkRegister(indexRegisterType, Primitive32BitCategories);

        RegisterType arrayRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert arrayRegisterType != null;

        if (arrayRegisterType.category != RegisterType.Category.Null) {
            if (arrayRegisterType.category != RegisterType.Category.Reference) {
                throw new ValidationException(String.format("Cannot use aget-object with non-array type %s",
                        arrayRegisterType.category.toString()));
            }

            assert arrayRegisterType.type != null;
            if (arrayRegisterType.type.getClassType().charAt(0) != '[') {
                throw new ValidationException(String.format("Cannot use aget-object with non-array type %s",
                        arrayRegisterType.type.getClassType()));
            }

            assert arrayRegisterType.type instanceof ClassPath.ArrayClassDef;
            ClassPath.ArrayClassDef arrayClassDef = (ClassPath.ArrayClassDef)arrayRegisterType.type;

            ClassPath.ClassDef elementClassDef = arrayClassDef.getImmediateElementClass();
            char elementTypePrefix = elementClassDef.getClassType().charAt(0);
            if (elementTypePrefix != 'L' && elementTypePrefix != '[') {
                throw new ValidationException(String.format("Cannot use aget-object with array type %s. Incorrect " +
                        "array type for the instruction.", arrayRegisterType.type.getClassType()));
            }

            setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                    RegisterType.getRegisterType(RegisterType.Category.Reference, elementClassDef));
        } else {
            setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                    RegisterType.getRegisterType(RegisterType.Category.Null, null));
        }
    }

    private void handle32BitPrimitiveAput(AnalyzedInstruction analyzedInstruction,
                                             RegisterType.Category instructionCategory) {
        ThreeRegisterInstruction instruction = (ThreeRegisterInstruction)analyzedInstruction.instruction;

        RegisterType indexRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterC());
        assert indexRegisterType != null;
        checkRegister(indexRegisterType, Primitive32BitCategories);


        RegisterType sourceRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert sourceRegisterType != null;
        RegisterType instructionRegisterType = RegisterType.getRegisterType(instructionCategory, null);
        if (!sourceRegisterType.canBeAssignedTo(instructionRegisterType)) {
            throw new ValidationException(String.format("Cannot use %s with source register type %s.",
                    analyzedInstruction.instruction.opcode.name, sourceRegisterType.toString()));
        }


        RegisterType arrayRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert arrayRegisterType != null;

        if (arrayRegisterType.category != RegisterType.Category.Null) {
            if (arrayRegisterType.category != RegisterType.Category.Reference) {
                throw new ValidationException(String.format("Cannot use %s with non-array type %s",
                        analyzedInstruction.instruction.opcode.name, arrayRegisterType.category.toString()));
            }

            assert arrayRegisterType.type != null;
            if (arrayRegisterType.type.getClassType().charAt(0) != '[') {
                throw new ValidationException(String.format("Cannot use %s with non-array type %s",
                        analyzedInstruction.instruction.opcode.name, arrayRegisterType.type.getClassType()));
            }

            assert arrayRegisterType.type instanceof ClassPath.ArrayClassDef;
            ClassPath.ArrayClassDef arrayClassDef = (ClassPath.ArrayClassDef)arrayRegisterType.type;

            if (arrayClassDef.getArrayDimensions() != 1) {
                throw new ValidationException(String.format("Cannot use %s with multi-dimensional array type %s",
                        analyzedInstruction.instruction.opcode.name, arrayRegisterType.type.getClassType()));
            }

            RegisterType arrayBaseType =
                    RegisterType.getRegisterTypeForType(arrayClassDef.getBaseElementClass().getClassType());
            if (checkArrayFieldAssignment(arrayBaseType.category, instructionCategory)) {
                throw new ValidationException(String.format("Cannot use %s with array type %s. Incorrect array type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        arrayRegisterType.type.getClassType()));
            }
        }
    }

    private void handleAputWide(AnalyzedInstruction analyzedInstruction) {
        ThreeRegisterInstruction instruction = (ThreeRegisterInstruction)analyzedInstruction.instruction;

        RegisterType indexRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterC());
        assert indexRegisterType != null;
        checkRegister(indexRegisterType, Primitive32BitCategories);

        RegisterType sourceRegisterType = getAndCheckWideSourcePair(analyzedInstruction, instruction.getRegisterA());

        RegisterType arrayRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert arrayRegisterType != null;

        if (arrayRegisterType.category != RegisterType.Category.Null) {
            if (arrayRegisterType.category != RegisterType.Category.Reference) {
                throw new ValidationException(String.format("Cannot use aput-wide with non-array type %s",
                        arrayRegisterType.category.toString()));
            }

            assert arrayRegisterType.type != null;
            if (arrayRegisterType.type.getClassType().charAt(0) != '[') {
                throw new ValidationException(String.format("Cannot use aput-wide with non-array type %s",
                        arrayRegisterType.type.getClassType()));
            }

            assert arrayRegisterType.type instanceof ClassPath.ArrayClassDef;
            ClassPath.ArrayClassDef arrayClassDef = (ClassPath.ArrayClassDef)arrayRegisterType.type;

            if (arrayClassDef.getArrayDimensions() != 1) {
                throw new ValidationException(String.format("Cannot use aput-wide with multi-dimensional array type %s",
                        arrayRegisterType.type.getClassType()));
            }

            char arrayBaseType = arrayClassDef.getBaseElementClass().getClassType().charAt(0);
            if (arrayBaseType != 'J' && arrayBaseType != 'D') {
                throw new ValidationException(String.format("Cannot use aput-wide with array type %s. Incorrect " +
                        "array type for the instruction.", arrayRegisterType.type.getClassType()));
            }
        }
    }

    private void handleAputObject(AnalyzedInstruction analyzedInstruction) {
        ThreeRegisterInstruction instruction = (ThreeRegisterInstruction)analyzedInstruction.instruction;

        RegisterType indexRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterC());
        assert indexRegisterType != null;
        checkRegister(indexRegisterType, Primitive32BitCategories);

        RegisterType sourceRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert sourceRegisterType != null;

        //TODO: ensure sourceRegisterType is a Reference type?

        RegisterType arrayRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert arrayRegisterType != null;

        if (arrayRegisterType.category != RegisterType.Category.Null) {
            //don't check the source type against the array type, just make sure it is an array of reference types

            if (arrayRegisterType.category != RegisterType.Category.Reference) {
                throw new ValidationException(String.format("Cannot use aget-object with non-array type %s",
                        arrayRegisterType.category.toString()));
            }

            assert arrayRegisterType.type != null;
            if (arrayRegisterType.type.getClassType().charAt(0) != '[') {
                throw new ValidationException(String.format("Cannot use aget-object with non-array type %s",
                        arrayRegisterType.type.getClassType()));
            }

            assert arrayRegisterType.type instanceof ClassPath.ArrayClassDef;
            ClassPath.ArrayClassDef arrayClassDef = (ClassPath.ArrayClassDef)arrayRegisterType.type;

            ClassPath.ClassDef elementClassDef = arrayClassDef.getImmediateElementClass();
            char elementTypePrefix = elementClassDef.getClassType().charAt(0);
            if (elementTypePrefix != 'L' && elementTypePrefix != '[') {
                throw new ValidationException(String.format("Cannot use aget-object with array type %s. Incorrect " +
                        "array type for the instruction.", arrayRegisterType.type.getClassType()));
            }
        }
    }

    private void handle32BitPrimitiveIget(AnalyzedInstruction analyzedInstruction,
                                             RegisterType.Category instructionCategory) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        RegisterType objectRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert objectRegisterType != null;
        checkRegister(objectRegisterType, ReferenceCategories);

        //TODO: check access
        //TODO: allow an uninitialized "this" reference, if the current method is an <init> method
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        if (objectRegisterType.category != RegisterType.Category.Null &&
            !objectRegisterType.type.extendsClass(ClassPath.getClassDef(field.getContainingClass()))) {
            throw new ValidationException(String.format("Cannot access field %s through type %s",
                    field.getFieldString(), objectRegisterType.type.getClassType()));
        }

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (!checkArrayFieldAssignment(fieldType.category, instructionCategory)) {
                throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                RegisterType.getRegisterType(instructionCategory, null));
    }

    private void handleIgetWide(AnalyzedInstruction analyzedInstruction) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        RegisterType objectRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert objectRegisterType != null;
        checkRegister(objectRegisterType, ReferenceCategories);

        getAndCheckWideSourcePair(analyzedInstruction, instruction.getRegisterB());

        //TODO: check access
        //TODO: allow an uninitialized "this" reference, if the current method is an <init> method
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        if (objectRegisterType.category != RegisterType.Category.Null &&
            !objectRegisterType.type.extendsClass(ClassPath.getClassDef(field.getContainingClass()))) {
            throw new ValidationException(String.format("Cannot access field %s through type %s",
                    field.getFieldString(), objectRegisterType.type.getClassType()));
        }

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        try {
            checkRegister(fieldType, WideLowCategories);
        } catch (ValidationException ex) {
            throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }

        setWideDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, fieldType);
    }

    private void handleIgetObject(AnalyzedInstruction analyzedInstruction) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        RegisterType objectRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert objectRegisterType != null;
        checkRegister(objectRegisterType, ReferenceCategories);

        //TODO: check access
        //TODO: allow an uninitialized "this" reference, if the current method is an <init> method
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        if (objectRegisterType.category != RegisterType.Category.Null &&
            !objectRegisterType.type.extendsClass(ClassPath.getClassDef(field.getContainingClass()))) {
            throw new ValidationException(String.format("Cannot access field %s through type %s",
                    field.getFieldString(), objectRegisterType.type.getClassType()));
        }

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (fieldType.category != RegisterType.Category.Reference) {
            throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, fieldType);
    }

    private void handle32BitPrimitiveIput(AnalyzedInstruction analyzedInstruction,
                                             RegisterType.Category instructionCategory) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        RegisterType objectRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert objectRegisterType != null;
        checkRegister(objectRegisterType, ReferenceCategories);

        RegisterType sourceRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert sourceRegisterType != null;

        //per CodeVerify.c in dalvik:
        //java generates synthetic functions that write byte values into boolean fields
        if (sourceRegisterType.category == RegisterType.Category.Byte &&
            instructionCategory == RegisterType.Category.Boolean) {

            sourceRegisterType = RegisterType.getRegisterType(RegisterType.Category.Boolean, null);
        }

        RegisterType instructionRegisterType = RegisterType.getRegisterType(instructionCategory, null);
        if (!sourceRegisterType.canBeAssignedTo(instructionRegisterType)) {
            throw new ValidationException(String.format("Cannot use %s with source register type %s.",
                    analyzedInstruction.instruction.opcode.name, sourceRegisterType.toString()));
        }


        //TODO: check access
        //TODO: allow an uninitialized "this" reference, if the current method is an <init> method
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        if (objectRegisterType.category != RegisterType.Category.Null &&
            !objectRegisterType.type.extendsClass(ClassPath.getClassDef(field.getContainingClass()))) {
            throw new ValidationException(String.format("Cannot access field %s through type %s",
                    field.getFieldString(), objectRegisterType.type.getClassType()));
        }

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (!checkArrayFieldAssignment(fieldType.category, instructionCategory)) {
                throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }
    }

    private void handleIputWide(AnalyzedInstruction analyzedInstruction) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        RegisterType objectRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert objectRegisterType != null;
        checkRegister(objectRegisterType, ReferenceCategories);

        RegisterType sourceRegisterType = getAndCheckWideSourcePair(analyzedInstruction, instruction.getRegisterA());
        assert sourceRegisterType != null;

        //TODO: check access
        //TODO: allow an uninitialized "this" reference, if the current method is an <init> method
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        if (objectRegisterType.category != RegisterType.Category.Null &&
                !objectRegisterType.type.extendsClass(ClassPath.getClassDef(field.getContainingClass()))) {
            throw new ValidationException(String.format("Cannot access field %s through type %s",
                    field.getFieldString(), objectRegisterType.type.getClassType()));
        }

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (!WideLowCategories.contains(fieldType.category)) {
            throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                    "for the instruction.", analyzedInstruction.instruction.opcode.name,
                    field.getFieldString()));
        }
    }

    private void handleIputObject(AnalyzedInstruction analyzedInstruction) {
        TwoRegisterInstruction instruction = (TwoRegisterInstruction)analyzedInstruction.instruction;

        RegisterType objectRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterB());
        assert objectRegisterType != null;
        checkRegister(objectRegisterType, ReferenceCategories);

        RegisterType sourceRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert sourceRegisterType != null;
        checkRegister(sourceRegisterType, ReferenceCategories);

        //TODO: check access
        //TODO: allow an uninitialized "this" reference, if the current method is an <init> method
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        if (objectRegisterType.category != RegisterType.Category.Null &&
            !objectRegisterType.type.extendsClass(ClassPath.getClassDef(field.getContainingClass()))) {
            throw new ValidationException(String.format("Cannot access field %s through type %s",
                    field.getFieldString(), objectRegisterType.type.getClassType()));
        }

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (fieldType.category != RegisterType.Category.Reference) {
            throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }

        if (sourceRegisterType.category != RegisterType.Category.Null &&
            !fieldType.type.isInterface() &&
            !sourceRegisterType.type.extendsClass(fieldType.type)) {

            throw new ValidationException(String.format("Cannot store a value of type %s into a field of type %s",
                    sourceRegisterType.type.getClassType(), fieldType.type.getClassType()));
        }
    }

    private void handle32BitPrimitiveSget(AnalyzedInstruction analyzedInstruction,
                                             RegisterType.Category instructionCategory) {
        //TODO: check access
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (!checkArrayFieldAssignment(fieldType.category, instructionCategory)) {
                throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction,
                RegisterType.getRegisterType(instructionCategory, null));
    }

    private void handleSgetWide(AnalyzedInstruction analyzedInstruction) {
        //TODO: check access
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());


        if (fieldType.category != RegisterType.Category.LongLo &&
            fieldType.category != RegisterType.Category.DoubleLo) {

            throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                    "for the instruction.", analyzedInstruction.instruction.opcode.name,
                    field.getFieldString()));
        }

        setWideDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, fieldType);
    }

    private void handleSgetObject(AnalyzedInstruction analyzedInstruction) {
        //TODO: check access
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (fieldType.category != RegisterType.Category.Reference) {
                throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }

        setDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, fieldType);
    }

    private void handle32BitPrimitiveSput(AnalyzedInstruction analyzedInstruction,
                                             RegisterType.Category instructionCategory) {
        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;

        RegisterType sourceRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert sourceRegisterType != null;

        //per CodeVerify.c in dalvik:
        //java generates synthetic functions that write byte values into boolean fields
        if (sourceRegisterType.category == RegisterType.Category.Byte &&
            instructionCategory == RegisterType.Category.Boolean) {

            sourceRegisterType = RegisterType.getRegisterType(RegisterType.Category.Boolean, null);
        }

        RegisterType instructionRegisterType = RegisterType.getRegisterType(instructionCategory, null);
        if (!sourceRegisterType.canBeAssignedTo(instructionRegisterType)) {
            throw new ValidationException(String.format("Cannot use %s with source register type %s.",
                    analyzedInstruction.instruction.opcode.name, sourceRegisterType.toString()));
        }

        //TODO: check access
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (!checkArrayFieldAssignment(fieldType.category, instructionCategory)) {
                throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }
    }

    private void handleSputWide(AnalyzedInstruction analyzedInstruction) {
        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;


        RegisterType sourceRegisterType = getAndCheckWideSourcePair(analyzedInstruction, instruction.getRegisterA());
        assert sourceRegisterType != null;

        //TODO: check access
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (!WideLowCategories.contains(fieldType.category)) {
                throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }

        setWideDestinationRegisterTypeAndPropagateChanges(analyzedInstruction, fieldType);
    }

    private void handleSputObject(AnalyzedInstruction analyzedInstruction) {
        SingleRegisterInstruction instruction = (SingleRegisterInstruction)analyzedInstruction.instruction;

        RegisterType sourceRegisterType = analyzedInstruction.getPreInstructionRegisterType(instruction.getRegisterA());
        assert sourceRegisterType != null;
        checkRegister(sourceRegisterType, ReferenceCategories);

        //TODO: check access
        Item referencedItem = ((InstructionWithReference)analyzedInstruction.instruction).getReferencedItem();
        assert referencedItem instanceof FieldIdItem;
        FieldIdItem field = (FieldIdItem)referencedItem;

        RegisterType fieldType = RegisterType.getRegisterTypeForTypeIdItem(field.getFieldType());

        if (fieldType.category != RegisterType.Category.Reference) {
            throw new ValidationException(String.format("Cannot use %s with field %s. Incorrect field type " +
                        "for the instruction.", analyzedInstruction.instruction.opcode.name,
                        field.getFieldString()));
        }

        if (sourceRegisterType.category != RegisterType.Category.Null &&
            !fieldType.type.isInterface() &&
            !sourceRegisterType.type.extendsClass(fieldType.type)) {

            throw new ValidationException(String.format("Cannot store a value of type %s into a field of type %s",
                    sourceRegisterType.type.getClassType(), fieldType.type.getClassType()));
        }
    }

    private void handleInvoke(AnalyzedInstruction analyzedInstruction, int invokeType) {
        FiveRegisterInstruction instruction = (FiveRegisterInstruction)analyzedInstruction.instruction;
        handleInvokeCommon(analyzedInstruction, false, invokeType, new Format35cRegisterIterator(instruction));
    }

    private void handleInvokeRange(AnalyzedInstruction analyzedInstruction, int invokeType) {
        RegisterRangeInstruction instruction = (RegisterRangeInstruction)analyzedInstruction.instruction;
        handleInvokeCommon(analyzedInstruction, true, invokeType, new Format3rcRegisterIterator(instruction));
    }

    private static final int INVOKE_VIRTUAL = 0x01;
    private static final int INVOKE_SUPER = 0x02;
    private static final int INVOKE_DIRECT = 0x04;
    private static final int INVOKE_INTERFACE = 0x08;
    private static final int INVOKE_STATIC = 0x10;

    private void handleInvokeCommon(AnalyzedInstruction analyzedInstruction, boolean isRange, int invokeType,
                                       RegisterIterator registers) {
        InstructionWithReference instruction = (InstructionWithReference)analyzedInstruction.instruction;

        //TODO: check access
        //TODO: allow uninitialized reference if this in an <init> method

        Item item = instruction.getReferencedItem();
        assert item.getItemType() == ItemType.TYPE_METHOD_ID_ITEM;
        MethodIdItem methodIdItem = (MethodIdItem)item;

        TypeIdItem methodClass = methodIdItem.getContainingClass();
        boolean isInit = false;

        if (methodIdItem.getMethodName().getStringValue().charAt(0) == '<') {
            if ((invokeType & INVOKE_DIRECT) != 0) {
                isInit = true;
            } else {
                throw new ValidationException(String.format("Cannot call constructor %s with %s",
                        methodIdItem.getMethodString(), analyzedInstruction.instruction.opcode.name));
            }
        }

        ClassPath.ClassDef methodClassDef = ClassPath.getClassDef(methodClass);
        if ((invokeType & INVOKE_INTERFACE) != 0) {
            if (!methodClassDef.isInterface()) {
                throw new ValidationException(String.format("Cannot call method %s with %s. %s is not an interface " +
                        "class.", methodIdItem.getMethodString(), analyzedInstruction.instruction.opcode.name,
                        methodClassDef.getClassType()));
            }
        } else {
            if (methodClassDef.isInterface()) {
                throw new ValidationException(String.format("Cannot call method %s with %s. %s is an interface class." +
                        " Use invoke-interface or invoke-interface/range instead.", methodIdItem.getMethodString(),
                        analyzedInstruction.instruction.opcode.name, methodClassDef.getClassType()));
            }
        }

        if ((invokeType & INVOKE_SUPER) != 0) {
            if (methodClassDef.getSuperclass() == null) {
                throw new ValidationException(String.format("Cannot call method %s with %s. %s has no superclass",
                        methodIdItem.getMethodString(), analyzedInstruction.instruction.opcode.name,
                        methodClassDef.getSuperclass().getClassType()));
            }

            if (!methodClassDef.getSuperclass().hasVirtualMethod(methodIdItem.getMethodString())) {
                throw new ValidationException(String.format("Cannot call method %s with %s. The superclass %s has" +
                        "no such method", methodIdItem.getMethodString(),
                        analyzedInstruction.instruction.opcode.name, methodClassDef.getSuperclass().getClassType()));
            }
        }

        assert isRange || registers.getCount() <= 5;

        TypeListItem typeListItem = methodIdItem.getPrototype().getParameters();
        int methodParameterRegisterCount;
        if (typeListItem == null) {
            methodParameterRegisterCount = 0;
        } else {
            methodParameterRegisterCount = typeListItem.getRegisterCount();
        }

        if ((invokeType & INVOKE_STATIC) == 0) {
            methodParameterRegisterCount++;
        }

        if (methodParameterRegisterCount != registers.getCount()) {
            throw new ValidationException(String.format("The number of registers does not match the number of " +
                    "parameters for method %s. Expecting %d registers, got %d.", methodIdItem.getMethodString(),
                    methodParameterRegisterCount + 1, registers.getCount()));
        }

        RegisterType objectRegisterType = null;
        int objectRegister = 0;
        if ((invokeType & INVOKE_STATIC) == 0) {
            objectRegister = registers.getRegister();
            registers.moveNext();

            objectRegisterType = analyzedInstruction.getPreInstructionRegisterType(objectRegister);
            assert objectRegisterType != null;
            if (objectRegisterType.category == RegisterType.Category.UninitRef) {
                if (!isInit) {
                    throw new ValidationException(String.format("Cannot invoke non-<init> method %s on uninitialized " +
                            "reference type %s", methodIdItem.getMethodString(),
                            objectRegisterType.type.getClassType()));
                }
            } else if (objectRegisterType.category == RegisterType.Category.Reference) {
                if (isInit) {
                    throw new ValidationException(String.format("Cannot invoke %s on initialized reference type %s",
                            methodIdItem.getMethodString(), objectRegisterType.type.getClassType()));
                }
            } else if (objectRegisterType.category == RegisterType.Category.Null) {
                if (isInit) {
                    throw new ValidationException(String.format("Cannot invoke %s on a null reference",
                            methodIdItem.getMethodString()));
                }
            }
            else {
                throw new ValidationException(String.format("Cannot invoke %s on non-reference type %s",
                        methodIdItem.getMethodString(), objectRegisterType.toString()));
            }

            if (isInit) {
                if (objectRegisterType.type == methodClassDef.getSuperclass()) {
                    if (!encodedMethod.method.getMethodName().equals("<init>")) {
                        throw new ValidationException(String.format("Cannot call %s on type %s. The object type must " +
                                "match the method type exactly", methodIdItem.getMethodString(),
                                objectRegisterType.type.getClassType()));
                    }
                }
            }


            if ((invokeType & INVOKE_INTERFACE) == 0 && !objectRegisterType.type.extendsClass(methodClassDef)) {
               throw new ValidationException(String.format("Cannot call method %s on an object of type %s, which " +
                       "does not extend %s.", methodIdItem.getMethodString(), objectRegisterType.type.getClassType(),
                        methodClassDef.getClassType()));
            }
        }

        List<TypeIdItem> parameterTypes = typeListItem.getTypes();
        int parameterTypeIndex = 0;
        while (!registers.pastEnd()) {
            assert parameterTypeIndex < parameterTypes.size();
            RegisterType parameterType =
                    RegisterType.getRegisterTypeForTypeIdItem(parameterTypes.get(parameterTypeIndex));

            int register = registers.getRegister();

            RegisterType parameterRegisterType;
            if (WideLowCategories.contains(parameterType.category)) {
                parameterRegisterType = getAndCheckWideSourcePair(analyzedInstruction, register);

                if (!registers.moveNext()) {
                    throw new ValidationException(String.format("No 2nd register specified for wide register pair v%d",
                            parameterTypeIndex+1));
                }
                int nextRegister = registers.getRegister();

                if (nextRegister != register + 1) {
                    throw new ValidationException(String.format("Invalid wide register pair (v%d, v%d). Registers " +
                            "must be consecutive.", register, nextRegister));
                }
            } else {
                parameterRegisterType = analyzedInstruction.getPreInstructionRegisterType(register);
            }

            assert parameterRegisterType != null;

            if (!parameterRegisterType.canBeAssignedTo(parameterType)) {
                throw new ValidationException(
                        String.format("Invalid register type %s for parameter %d %s.",
                                parameterRegisterType.toString(), parameterTypeIndex+1,
                                parameterType.toString()));
            }

            parameterTypeIndex++;
            registers.moveNext();
        }


        //TODO: need to ensure the "this" register is initialized, in a constructor method
        if (isInit) {
            setRegisterTypeAndPropagateChanges(analyzedInstruction, objectRegister,
                    RegisterType.getRegisterType(RegisterType.Category.Reference, objectRegisterType.type));

            for (int i=0; i<analyzedInstruction.postRegisterMap.length; i++) {
                RegisterType postInstructionRegisterType = analyzedInstruction.postRegisterMap[i];
                if (postInstructionRegisterType.category == RegisterType.Category.Unknown) {
                    RegisterType preInstructionRegisterType =
                            analyzedInstruction.getMergedRegisterTypeFromPredecessors(i);

                    if (preInstructionRegisterType.category == RegisterType.Category.UninitRef) {
                        RegisterType registerType = null;
                        if (preInstructionRegisterType == objectRegisterType) {
                            registerType = analyzedInstruction.postRegisterMap[objectRegister];
                        } else {
                            registerType = preInstructionRegisterType;
                        }

                        setRegisterTypeAndPropagateChanges(analyzedInstruction, i, registerType);
                    }
                }
            }
        }
    }



    private static boolean checkArrayFieldAssignment(RegisterType.Category arrayFieldCategory,
                                                  RegisterType.Category instructionCategory) {
        if (arrayFieldCategory == instructionCategory) {
            return true;
        }

        if ((arrayFieldCategory == RegisterType.Category.Integer &&
             instructionCategory == RegisterType.Category.Float) ||
            (arrayFieldCategory == RegisterType.Category.Float &&
             instructionCategory == RegisterType.Category.Integer)) {
            return true;
        }
        return false;
    }

    private static void checkRegister(RegisterType registerType, EnumSet validCategories) {
        if (!validCategories.contains(registerType.category)) {
            //TODO: add expected categories to error message
            throw new ValidationException("Invalid register type. Expecting one of: " + " but got \"" +
                    registerType.category + "\"");
        }
    }

    private static void checkWideDestinationPair(AnalyzedInstruction analyzedInstruction) {
        int register = analyzedInstruction.getDestinationRegister();

        if (register == (analyzedInstruction.postRegisterMap.length - 1)) {
            throw new ValidationException("v" + register + " is the last register and not a valid wide register " +
                    "pair.");
        }
    }

    private static RegisterType getAndCheckWideSourcePair(AnalyzedInstruction analyzedInstruction, int firstRegister) {
        assert firstRegister >= 0 && firstRegister < analyzedInstruction.postRegisterMap.length;

        if (firstRegister == analyzedInstruction.postRegisterMap.length - 1) {
            throw new ValidationException("v" + firstRegister + " is the last register and not a valid wide register " +
                    "pair.");
        }

        RegisterType registerType = analyzedInstruction.getPreInstructionRegisterType(firstRegister);
        assert registerType != null;
        if (registerType.category == RegisterType.Category.Unknown) {
            return registerType;
        }
        checkRegister(registerType, WideLowCategories);

        RegisterType secondRegisterType = analyzedInstruction.getPreInstructionRegisterType(firstRegister + 1);
        assert secondRegisterType != null;
        checkRegister(secondRegisterType, WideHighCategories);

        if ((       registerType.category == RegisterType.Category.LongLo &&
                    secondRegisterType.category == RegisterType.Category.DoubleHi)
            ||  (   registerType.category == RegisterType.Category.DoubleLo &&
                    secondRegisterType.category == RegisterType.Category.LongHi)) {
            assert false;
            throw new ValidationException("The first register in the wide register pair isn't the same type (long " +
                    "vs. double) as the second register in the pair");
        }

        return registerType;
    }
}