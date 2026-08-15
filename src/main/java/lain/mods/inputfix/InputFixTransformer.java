package lain.mods.inputfix;

import com.google.common.collect.ImmutableSet;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

/**
 * 原样移植：ASM 类转换器，把 GuiScreen.handleKeyboardInput 方法体替换为
 * {@code GuiScreenFix.handleKeyboardInput(this);}，从而接管键盘输入并转发 IME 提交字符。
 * 通过 FMLDeobfuscatingRemapper 做方法名映射，兼容开发(MCP)/生产(SRG)两套命名。
 */
public class InputFixTransformer implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (transformedName.equals("net.minecraft.client.gui.GuiScreen")) {
            return transform001(basicClass);
        }
        return basicClass;
    }

    private byte[] transform001(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        reader.accept(new GuiScreenClassVisitor(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static class GuiScreenClassVisitor extends ClassVisitor {

        private final Set<String> names;
        private final String cl;

        GuiScreenClassVisitor(ClassVisitor cv) {
            super(Opcodes.ASM5, cv);
            this.cl = FMLDeobfuscatingRemapper.INSTANCE.unmap("net/minecraft/client/gui/GuiScreen");
            this.names = ImmutableSet.of("func_146282_l", "handleKeyboardInput");
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            String mapped = FMLDeobfuscatingRemapper.INSTANCE.mapMethodName(cl, name, desc);
            if (names.contains(mapped) && desc.equals("()V")) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "lain/mods/inputfix/GuiScreenFix", "handleKeyboardInput",
                        "(Lnet/minecraft/client/gui/GuiScreen;)V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
                return new SuppressBodyMethodVisitor();
            }
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
    }

    /**
     * 空实现 MethodVisitor：吞掉原方法体（已被上面的替换代码接管），避免重复写回原字节码。
     */
    private static class SuppressBodyMethodVisitor extends MethodVisitor {
        SuppressBodyMethodVisitor() {
            super(Opcodes.ASM5, null);
        }
    }
}
