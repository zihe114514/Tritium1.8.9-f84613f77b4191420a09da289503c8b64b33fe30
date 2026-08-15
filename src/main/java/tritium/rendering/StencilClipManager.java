package tritium.rendering;

import org.lwjgl.opengl.GL11;
import tritium.interfaces.SharedConstants;

public class StencilClipManager implements SharedConstants {

    private static final BooleanState stencilState = new BooleanState(GL11.GL_STENCIL_TEST);

    public static class StencilState {
        int stencilValue;
        boolean colorMask;
        boolean depthMask;

        StencilState(int value, boolean color, boolean depth) {
            this.stencilValue = value;
            this.colorMask = color;
            this.depthMask = depth;
        }
    }

    public static boolean stencilClipping() {
        return !Framebuffer.currentlyBinding.stencilStack.isEmpty();
    }

    public static void disableStencilTest() {
        stencilState.setDisabled();
    }

    public static void enableStencilTest() {
        stencilState.setEnabled();
    }

    public static boolean isStencilEnabled() {
        return stencilState.currentState;
    }


    public static void initialize() {
        GL11.glClearStencil(0);
        api.getGLStateManager().enableDepth();
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        enableStencilTest();
    }

    static boolean depthMask = false;

    public static void beginClip() {
        if (Framebuffer.currentlyBinding.currentStencilValue == 0) {
            initialize();
        }

        boolean colorMask = GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        Framebuffer.currentlyBinding.stencilStack.push(new StencilState(Framebuffer.currentlyBinding.currentStencilValue, colorMask, depthMask));

        api.getGLStateManager().colorMask(false, false, false, false);
        api.getGLStateManager().depthMask(false);

        if (Framebuffer.currentlyBinding.currentStencilValue == 0) {
            GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        } else {
            GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
        }

        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INCR);
    }

    public static void updateClip() {
        Framebuffer.currentlyBinding.currentStencilValue++;

        api.getGLStateManager().colorMask(true, true, true, true);
        api.getGLStateManager().depthMask(depthMask);

        GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
    }

    public static void beginClip(Runnable drawClipShape) {
        beginClip();

        drawClipShape.run();

        updateClip();
    }

    public static void endClip() {
        if (Framebuffer.currentlyBinding.stencilStack.isEmpty()) {
            System.err.println("Stencil stack underflow");
            return;
        }

        StencilState state = Framebuffer.currentlyBinding.stencilStack.pop();
        Framebuffer.currentlyBinding.currentStencilValue = state.stencilValue;

        api.getGLStateManager().colorMask(state.colorMask, state.colorMask, state.colorMask, state.colorMask);
        api.getGLStateManager().depthMask(state.depthMask);

        if (Framebuffer.currentlyBinding.currentStencilValue > 0) {
            GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        } else {
            disable();
        }
    }

    public static void disable() {
        disableStencilTest();
        Framebuffer.currentlyBinding.currentStencilValue = 0;
    }

    public static void clear() {
        Framebuffer.currentlyBinding.currentStencilValue = 0;
        Framebuffer.currentlyBinding.stencilStack.clear();
    }
}