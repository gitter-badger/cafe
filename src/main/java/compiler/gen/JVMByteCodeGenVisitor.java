package compiler.gen;

import cafe.DynamicObject;
import cafe.Function;
import cafelang.ir.*;

import compiler.ast.Node;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;

import java.lang.invoke.MethodType;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static compiler.gen.JVMBytecodeUtils.loadInteger;
import static compiler.gen.JVMBytecodeUtils.loadLong;
import static java.lang.invoke.MethodType.genericMethodType;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;
import static java.lang.invoke.MethodType.methodType;

public class JVMByteCodeGenVisitor implements CafeIrVisitor {

    private static final String JOBJECT = "java/lang/Object";
    private static final String TOBJECT = "Ljava/lang/Object;";

    private static final String JDYNAMIC = "cafe/DynamicObject";
    private static final String TDYNAMIC = "Lcafe/DynamicObject;";

    private static final String INIT_FUNC_SIGN = "()V";

    private static final Handle FUNC_REF_HANDLE = makeHandle(
            "FunctionReferenceID","Ljava/lang/String;II"
    );

    private static final Handle FUNC_INVOCATION_HANDLE = makeHandle(
            "FunctionInvocationID","[Ljava/lang/Object;"
    );

    private static final Handle METHOD_INVOCATION_HANDLE = makeHandle(
            "MethodInvocationID", "");

    private static Handle makeHandle(String methodName, String description) {
        return new Handle(H_INVOKESTATIC,
                "cafelang/runtime/" + methodName,
                "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + description + ")Ljava/lang/invoke/CallSite;", false);
    }

    private String functionSignature(CafeFunction function) {
        if(function.isInit())
            return INIT_FUNC_SIGN;

        MethodType signature;
        if (function.isVarargs()) {
            signature = MethodType.genericMethodType(function.getArity() , true);
        } else {
            signature = MethodType.genericMethodType(function.getArity()+1);
        }
        signature = signature.changeParameterType(0,DynamicObject.class);
        return signature.toMethodDescriptorString();
    }

    private String methodSignature(int arity){
        return genericMethodType(arity).toMethodDescriptorString();
    }

    private ClassWriter cw;
    private MethodVisitor mv;
    private CafeFunction currentFunction = null;
    private Context context;
    private String className;

    private static final class Context {
        private final Deque<ReferenceTable> referenceTableStack = new LinkedList<>();
        private final Deque<Object> objectStack = new LinkedList<>();
        private final Deque<SymbolReference> thisStack = new LinkedList<>();
    }

    private static class GlobalThis {
        private static final String THIS = "#thisPointer";
        private static final String INSERT_THIS = "#insertIntoThis";
        private static final String RETRIEVE_THIS = "#retrieveFromThis";

        private static final String INSERT_THIS_DESC = "(Ljava/lang/String;Ljava/lang/Object;)V";
        private static final String RETRIEVE_THIS_DESC = "(Ljava/lang/String;)Ljava/lang/Object;";

        private static void add(MethodVisitor mv, String className){
            mv.visitMethodInsn(INVOKESTATIC, className, INSERT_THIS, INSERT_THIS_DESC, false);
        }

        private static void retrieve(MethodVisitor mv, String className){
            mv.visitMethodInsn(INVOKESTATIC, className, RETRIEVE_THIS, RETRIEVE_THIS_DESC, false);
        }

        private static void declareInsertFunc(ClassWriter cw, String className){
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE|ACC_STATIC|ACC_SYNTHETIC,
                    INSERT_THIS,
                    INSERT_THIS_DESC,
                    null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC,className,THIS,TDYNAMIC);
            mv.visitVarInsn(ALOAD,0);
            mv.visitVarInsn(ALOAD,1);
            mv.visitMethodInsn(INVOKEVIRTUAL, JDYNAMIC, "define", "(Ljava/lang/String;Ljava/lang/Object;)V",false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void visitThisPointer(ClassWriter cw, String className){
            cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                    THIS,
                    TDYNAMIC,
                    null,null)
                    .visitEnd();

            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    "<clinit>",
                    "()V",
                    null,null);

            mv.visitTypeInsn(NEW, JDYNAMIC);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, JDYNAMIC, "<init>", "()V", false);
            mv.visitFieldInsn(PUTSTATIC, className, THIS , TDYNAMIC);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void declareRetrieveFunction(ClassWriter cw, String className){
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE|ACC_STATIC|ACC_SYNTHETIC,
                    RETRIEVE_THIS,
                    RETRIEVE_THIS_DESC,
                    null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC,className,THIS,TDYNAMIC);
            mv.visitVarInsn(ALOAD,0);
            mv.visitMethodInsn(INVOKEVIRTUAL, JDYNAMIC, "get", "(Ljava/lang/String;)Ljava/lang/Object;",false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void initThis(ClassWriter cw, String className){
            visitThisPointer(cw,className);
            declareInsertFunc(cw,className);
            declareRetrieveFunction(cw,className);
        }

        private static void loadThis(MethodVisitor mv,String className){
            mv.visitFieldInsn(GETSTATIC, className, THIS, TDYNAMIC);
        }
    }

    public byte[] generateByteCode(CafeModule module,String className){
        cw = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS);
        this.context = new Context();
        this.className = className;
        module.accept(this);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private int functionFlags(CafeFunction function) {
        int accessFlags = ACC_STATIC | ACC_PRIVATE ;
        if (function.isSynthetic() ) {
            accessFlags |= ACC_SYNTHETIC;
        }
        if (function.isVarargs()) {
            accessFlags |= ACC_VARARGS;
        }
        return accessFlags;
    }

    @Override
    public void visitObjectAccess(ObjectAccessStatement objectAccessStatement) {
        objectAccessStatement.walk(this);
    }

    @Override
    public void visitPropertyAccess(PropertyAccess propertyAccess) {
        mv.visitInvokeDynamicInsn(propertyAccess.getName(),
                methodSignature(1),
                METHOD_INVOCATION_HANDLE
                );
    }

    @Override
    public void visitMethodInvocation(MethodInvocation methodInvocation) {
        methodInvocation.walk(this);
        visitInvocationArguments(methodInvocation.getArguments());

        mv.visitInvokeDynamicInsn("methodInvocation",
                methodSignature(methodInvocation.getArity()),
                METHOD_INVOCATION_HANDLE);
    }

    @Override
    public void visitSubscript(SubscriptStatement subscriptStatement) {

    }

    @Override
    public void visitFunctionInvocation(FunctionInvocation functionInvocation) {
        ReferenceTable table = context.referenceTableStack.peek();
        SymbolReference reference = functionInvocation.getReference().resolveIn(table);

        if(reference.isGlobal()){
            mv.visitLdcInsn(reference.getName());
            GlobalThis.retrieve(mv,className);
            mv.visitTypeInsn(CHECKCAST, "cafe/Function");
            GlobalThis.loadThis(mv,className);
        }
        else{
            // TODO: error
        }

        MethodType type = genericMethodType(functionInvocation.getArity()+2).changeParameterType(0, Function.class)
                                                                            .changeParameterType(1, DynamicObject.class);
        String name = reference.getName();
        String typedef = type.toMethodDescriptorString();
        Handle handle = FUNC_INVOCATION_HANDLE;

        visitInvocationArguments(functionInvocation.getArguments());
        mv.visitInvokeDynamicInsn(name,typedef, handle,new Object[]{});
    }

    private void visitInvocationArguments(List<CafeElement<?>> arguments){
        for(CafeElement<?> argument : arguments){
            argument.accept(this);
        }
    }

    private void visitInitFunc(CafeFunction function) {
        mv = cw.visitMethod(functionFlags(function),
                function.getName(),
                INIT_FUNC_SIGN,
                null, null);
        mv.visitCode();
        currentFunction = function;

        function.walk(this);

        currentFunction = null;
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void visitMainFunc(){
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, className, "#init", INIT_FUNC_SIGN, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @Override
    public void visitModule(CafeModule module) {
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, className, null, JOBJECT, null);
        cw.visitSource(className,null);

        GlobalThis.initThis(cw,className);
        //visitInitFunc(module.getInitFunc());
        //context.referenceTableStack.push(module.getInitFunc().getBlock().getReferenceTable());
        visitMainFunc();
        module.walk(this);
    }

    @Override
    public void visitThis(ThisStatement thisStatement) {
        if(thisStatement.isGlobal())
            GlobalThis.loadThis(mv,className);
        else
            mv.visitVarInsn(ALOAD,0);
    }

    @Override
    public void visitSymbolReference(SymbolReference symbolReference) {

    }

    @Override
    public void visitBlock(Block block) {
        ReferenceTable table = block.getReferenceTable();
        context.referenceTableStack.push(table);
        for(CafeStatement<?> statement : block.getStatements()){
            statement.accept(this);
        }
        context.referenceTableStack.pop();
    }

    @Override
    public void visitReturn(ReturnStatement returnStatement) {
        CafeStatement<?> statement = returnStatement.getExpressionStatement();
        if(statement != null)
            statement.accept(this);
        else
            mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
    }

    @Override
    public void visitFunction(CafeFunction cafeFunction) {
        currentFunction = cafeFunction;

        mv = cw.visitMethod(
            functionFlags(cafeFunction),
            cafeFunction.getName(),
            functionSignature(cafeFunction),
            null,null
        );

        mv.visitParameter("this",ACC_PRIVATE);
        for(String parameter : cafeFunction.getParameterNames()){
            mv.visitParameter(parameter,ACC_PRIVATE);
        }

        mv.visitCode();
        cafeFunction.walk(this);
        if(cafeFunction.isInit())
            mv.visitInsn(RETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();

        currentFunction = null;
    }

    @Override
    public void visitObjectCreation(ObjectCreationStatement creationStatement) {
        Map<String, ExpressionStatement<?>> map = creationStatement.getMap();
        int index = creationStatement.index();
        // Create DynamicObject instance
        mv.visitTypeInsn(NEW, JDYNAMIC);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, JDYNAMIC, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, index);

        // define every property
        for(Map.Entry<String, ExpressionStatement<?>> entry: map.entrySet()){
            mv.visitVarInsn(ALOAD, index);
            String key = entry.getKey();
            mv.visitLdcInsn(key);
            entry.getValue().accept(this);
            mv.visitMethodInsn(INVOKEVIRTUAL, JDYNAMIC, "define", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
        }
        mv.visitVarInsn(ALOAD, index);
    }

    @Override
    public void visitAssignment(AssignmentStatement assignmentStatement) {
        ExpressionStatement<?> lhs = assignmentStatement.getLhsExpression();
        if(lhs instanceof ReferenceLookup){
            assignmentStatement.walk(this);
            SymbolReference ref = (SymbolReference) context.objectStack.pop();
            if(ref.isGlobal())
                GlobalThis.add(mv,className);
            else
                mv.visitVarInsn(ASTORE,ref.getIndex());
            return;
        }

        ObjectAccessStatement node = null;
        if(lhs instanceof ObjectAccessStatement)
            node = (ObjectAccessStatement) lhs;
        else throw new AssertionError("Unknown LHS expression");

        node.getAccessedOn().accept(this);

        ExpressionStatement<?> rhs = assignmentStatement.getRhsExpression();
        rhs.accept(this);
        visitLHSObjectProperty(node.getProperty());

    }

    private void visitLHSObjectProperty(ExpressionStatement<?> expressionStatement){
        if(expressionStatement instanceof PropertyAccess){
            mv.visitInvokeDynamicInsn(((PropertyAccess) expressionStatement).getName(),
                    methodSignature(2),
                    METHOD_INVOCATION_HANDLE
                    );
        }
        else if( expressionStatement instanceof SubscriptStatement){

        }
        else{
            // TODO: error
        }
    }

    @Override
    public void visitBinaryExpression(BinaryExpression binaryExpression) {

    }

    @Override
    public void visitUnaryExpression(UnaryExpression unaryExpression) {

    }

    @Override
    public void visitConstantStatement(ConstantStatement constantStatement) {
        Object value = constantStatement.value();
        if (value instanceof Integer) {
            int i = (Integer) value;
            loadInteger(mv, i);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            return;
        }
        if (value instanceof Long) {
            long l = (Long) value;
            loadLong(mv, l);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            return;
        }
        if (value instanceof Boolean) {
            boolean b = (Boolean) value;
            loadInteger(mv, b ? 1 : 0);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            return;
        }
        if (value instanceof String) {
            mv.visitLdcInsn(value);
            return;
        }
        if (value instanceof Double) {
            double d = (Double) value;
            mv.visitLdcInsn(d);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
            return;
        }
        if (value instanceof Float) {
            float f = (Float) value;
            mv.visitLdcInsn(f);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            return;
        }
        throw new IllegalArgumentException("Constants of type " + value.getClass() + " cannot be handled.");
    }

    @Override
    public void visitReferenceLookup(ReferenceLookup referenceLookup) {
        SymbolReference reference = referenceLookup.resolveIn(context.referenceTableStack.peek());
        context.objectStack.push(reference);
        if(reference.isGlobal()){
            mv.visitLdcInsn(reference.getName());
            GlobalThis.retrieve(mv,className);
        }else{
            mv.visitVarInsn(ALOAD, reference.getIndex());
        }
    }

    @Override
    public void visitFunctionWrapper(FunctionWrapper functionWrapper) {
        //functionWrapper.walk(this);
        CafeFunction target = functionWrapper.getTarget();
        int arity = (target.isVarargs()) ? target.getArity()-1 : target.getArity()+1;
        mv.visitInvokeDynamicInsn(
                target.getName(),
                methodType(Function.class).toMethodDescriptorString(),
                FUNC_REF_HANDLE,
                className,
                (Integer) arity,
                (Boolean) target.isVarargs()
        );
    }

    @Override
    public void visitDeclarativeAssignment(DeclarativeAssignmentStatement declarativeAssignmentStatement) {
        SymbolReference reference = declarativeAssignmentStatement.getSymbolReference();
        if(reference.isGlobal()){
            String key = reference.getName();
            mv.visitLdcInsn(key);
            declarativeAssignmentStatement.walk(this);
            GlobalThis.add(mv,className);
        }
        else{
            declarativeAssignmentStatement.walk(this);
            mv.visitVarInsn(ASTORE, reference.getIndex());
        }
    }

    @Override
    public void visitNull(NullStatement aNull) {
        mv.visitInsn(ACONST_NULL);
    }
}