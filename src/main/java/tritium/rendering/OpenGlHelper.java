package tritium.rendering;

import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class OpenGlHelper {
    public static int GL_FRAMEBUFFER = GL30.GL_FRAMEBUFFER;
    public static int GL_RENDERBUFFER = GL30.GL_RENDERBUFFER;
    public static int GL_COLOR_ATTACHMENT0 = GL30.GL_COLOR_ATTACHMENT0;
    public static int GL_DEPTH_ATTACHMENT = GL30.GL_DEPTH_ATTACHMENT;
    public static int GL_FRAMEBUFFER_COMPLETE = GL30.GL_FRAMEBUFFER_COMPLETE;
    public static int GL_FB_INCOMPLETE_ATTACHMENT = GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
    public static int GL_FB_INCOMPLETE_MISS_ATTACH = GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
    public static int GL_FB_INCOMPLETE_DRAW_BUFFER = GL30.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER;
    public static int GL_FB_INCOMPLETE_READ_BUFFER  = GL30.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER;

    public static void glBindFramebuffer(int target, int framebufferIn) {
        GL30.glBindFramebuffer(target, framebufferIn);
    }

    public static void glBindRenderbuffer(int target, int renderbuffer) {
        GL30.glBindRenderbuffer(target, renderbuffer);
    }

    public static void glDeleteRenderbuffers(int renderbuffer) {
        GL30.glDeleteRenderbuffers(renderbuffer);
    }

    public static void glDeleteFramebuffers(int framebufferIn) {
        GL30.glDeleteFramebuffers(framebufferIn);
    }

    /**
     * Calls the appropriate glGenFramebuffers method and returns the newly created fbo, or returns -1 if not supported.
     */
    public static int glGenFramebuffers() {
        return GL30.glGenFramebuffers();
    }

    public static int glGenRenderbuffers() {
        return GL30.glGenRenderbuffers();
    }

    public static void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
        GL30.glRenderbufferStorage(target, internalFormat, width, height);
    }

    public static void glFramebufferRenderbuffer(int target, int attachment, int renderBufferTarget, int renderBuffer) {
        GL30.glFramebufferRenderbuffer(target, attachment, renderBufferTarget, renderBuffer);
    }

    public static int glCheckFramebufferStatus(int target) {
        return GL30.glCheckFramebufferStatus(target);
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public static boolean isFramebufferEnabled() {
        return true;
    }
}
