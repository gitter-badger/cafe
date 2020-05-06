package compiler.codegen.assembly;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import compiler.SymbolTable;
import compiler.SymbolTableMapper;
import compiler.utils.HandleType;

public class MainFunc implements Func {

	private final ClassWriter cw;
	private final MethodVisitor mv;
	
	private final String clazz;
	
	private MethodVisitor clinit= null;
	private MethodVisitor currMv;
	
	private String currGetterSetterMethodName;
	
	private List<String> fieldsList;
	
	private MainFunc(final ClassWriter cw, final String className) {
		this.cw = cw;
		clazz = className;
		mv = currMv = this.cw.visitMethod(Func.FLAG_PUBLIC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
	
		fieldsList = new ArrayList<>();
	}

	static MainFunc mainFunc(ClassWriter cw, String className) {
		return new MainFunc(cw, className);
	}
	
	@Override
	public Func init() {
		currMv.visitCode();
		return this;
	}

	@Override
	public Func declareVar(String var) {
		initVarAsgn(var);
		currMv = mv;
		return this;
	}
	
	@Override
	public Func initVarAsgn(String var) {
		if(!fieldsList.contains(var)) {
			cw.visitField(Func.FLAG_PRIVATE_STATIC, var, "Ljava/lang/Object;", null, null).visitEnd();
			createGetterSetter(var);
			fieldsList.add(var);
		}
		
		if(clinit == null)
			clinit = cw.visitMethod(Func.FLAG_PUBLIC_STATIC, "<clinit>", "()V", null, null);
		
		currMv = clinit;
		currGetterSetterMethodName = var;
		
		return this;
	}
	
	@Override
	public Func initVarAsgnEnd() {
		currMv.visitInvokeDynamicInsn("#"+currGetterSetterMethodName, "(Ljava/lang/Object;)V", Func.FUNCTION_HANDLE,Program.packageClazz);
		
		currMv = mv;
		return this;
	}

	@Override
	public Func invokeFunc(String name, int args,HandleType handleType) {
		if(handleType == HandleType.OPERATOR_HANDLE_TYPE)
			currMv.visitInvokeDynamicInsn(name, Func.functionSignature(args), Func.OPERATOR_HANDLE, 2);
		else
			currMv.visitInvokeDynamicInsn(name, Func.functionSignature(args), Func.FUNCTION_HANDLE,  Program.packageClazz);
		return this;
	}

	@Override
	public Func loadLiteral(Object ob) {
		BytecodeUtils.loadLiteralToOperandStack(currMv, ob);
		return this;
	}
	
	@Override
	public Func loadIdentifier(String idName) {
		
		currMv.visitInvokeDynamicInsn("#"+idName, "()Ljava/lang/Object;", Func.FUNCTION_HANDLE, Program.packageClazz);
		
		return this;
	}
	
	@Override
	public Func loadReturnValue() {
	
		return this;
	}
	
	@Override
	public Func declareIfCondition() {
		// TODO Auto-generated method stub
		return null;
	}
	
	@Override
	public Func end() {
		if(clinit != null) {
			clinit.visitInsn(RETURN);
			clinit.visitMaxs(0,0);
			clinit.visitEnd();
		}
		
		currMv.visitInsn(RETURN);
		currMv.visitMaxs(0,0);
		currMv.visitEnd();
		return this;
	}
	
	private void createGetterSetter(String name) {
		
		String methodName = "#"+name;
		// Getter
		MethodVisitor field = cw.visitMethod(Func.FLAG_PRIVATE_STATIC, methodName, "()Ljava/lang/Object;", null, null);
		field.visitCode();
		field.visitFieldInsn(GETSTATIC, clazz, name, "Ljava/lang/Object;");
		field.visitInsn(ARETURN);
		field.visitMaxs(0, 0);
		field.visitEnd();

		// Setter
		field = cw.visitMethod(Func.FLAG_PRIVATE_STATIC, methodName, "(Ljava/lang/Object;)V", null, null);
		field.visitCode();
		field.visitVarInsn(ALOAD, 0);
		field.visitFieldInsn(PUTSTATIC, clazz, name, "Ljava/lang/Object;");
		field.visitInsn(RETURN);
		field.visitMaxs(0, 0);
		field.visitEnd();
	}
}