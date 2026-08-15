package tritium.rendering;

import org.lwjgl.opengl.*;
import tritium.interfaces.SharedConstants;

import java.nio.ByteBuffer;
import java.util.Stack;

import static tritium.rendering.rendersystem.RenderSystem.DIVIDE_BY_255;

public class Framebuffer implements SharedConstants {
    public int framebufferTextureWidth;
    public int framebufferTextureHeight;
    public int framebufferWidth;
    public int framebufferHeight;
    public boolean useDepth;
    public int framebufferObject;
    public int framebufferTexture;
    public int depthBuffer;
    public float[] framebufferColor;
    public int framebufferFilter;

    public Stack<StencilClipManager.StencilState> stencilStack = new Stack<>();
    public int currentStencilValue = 0;

    private boolean stencilTestEnabled = false;
    private int stencilFunc = GL11.GL_ALWAYS;
    private int stencilRef = 0;
    private int stencilValueMask = 0xFF;
    private int stencilWriteMask = 0xFF;
    private int stencilOpFail = GL11.GL_KEEP;
    private int stencilOpZFail = GL11.GL_KEEP;
    private int stencilOpZPass = GL11.GL_KEEP;
    private boolean stencilStateInitialized = false;

    public static Framebuffer currentlyBinding = null;

    public Framebuffer(int width, int height, boolean depth) {
        this.useDepth = depth;
        this.framebufferObject = -1;
        this.framebufferTexture = -1;
        this.depthBuffer = -1;
        this.framebufferColor = new float[4];
        this.framebufferColor[0] = 1.0F;
        this.framebufferColor[1] = 1.0F;
        this.framebufferColor[2] = 1.0F;
        this.framebufferColor[3] = 0.0F;
        this.createBindFramebuffer(width, height);
    }

    Framebuffer(int fbo, int fbTexture) {
        this.useDepth = true;
        this.framebufferObject = fbo;
        this.framebufferTexture = fbTexture;
    }

    public void createBindFramebuffer(int width, int height) {
        if (!OpenGlHelper.isFramebufferEnabled()) {
            this.framebufferWidth = width;
            this.framebufferHeight = height;
        } else {
            api.getGLStateManager().enableDepth();

            if (this.framebufferObject >= 0) {
                this.deleteFramebuffer();
            }

            this.createFramebuffer(width, height);
            this.checkFramebufferComplete();
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        }
    }

    public void deleteFramebuffer() {
        if (OpenGlHelper.isFramebufferEnabled()) {
            this.unbindFramebufferTexture();
            this.unbindFramebuffer();

            if (this.depthBuffer > -1) {
                OpenGlHelper.glDeleteRenderbuffers(this.depthBuffer);
                this.depthBuffer = -1;
            }

            if (this.framebufferTexture > -1) {
                TextureUtil.deleteTexture(this.framebufferTexture);
                this.framebufferTexture = -1;
            }

            if (this.framebufferObject > -1) {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
                OpenGlHelper.glDeleteFramebuffers(this.framebufferObject);
                this.framebufferObject = -1;
            }
        }
    }

    public void createFramebuffer(int width, int height) {
        this.framebufferWidth = width;
        this.framebufferHeight = height;
        this.framebufferTextureWidth = width;
        this.framebufferTextureHeight = height;

        if (!OpenGlHelper.isFramebufferEnabled()) {
            this.framebufferClear();
        } else {
            this.framebufferObject = OpenGlHelper.glGenFramebuffers();
            this.framebufferTexture = TextureUtil.glGenTextures();

            if (this.useDepth) {
                this.depthBuffer = OpenGlHelper.glGenRenderbuffers();
            }

            this.setFramebufferFilter(GL11.GL_NEAREST);
            api.getGLStateManager().bindTexture(this.framebufferTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.framebufferTextureWidth, this.framebufferTextureHeight, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, this.framebufferObject);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.framebufferTexture, 0);

            if (this.useDepth) {
                OpenGlHelper.glBindRenderbuffer(OpenGlHelper.GL_RENDERBUFFER, this.depthBuffer);
                OpenGlHelper.glRenderbufferStorage(OpenGlHelper.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, this.framebufferTextureWidth, this.framebufferTextureHeight);
                OpenGlHelper.glFramebufferRenderbuffer(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_DEPTH_ATTACHMENT, OpenGlHelper.GL_RENDERBUFFER, this.depthBuffer);
                EXTFramebufferObject.glFramebufferRenderbufferEXT(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, this.depthBuffer);
            }

            this.framebufferClear();
            this.unbindFramebufferTexture();
        }
    }

    public void setFramebufferFilter(int p_147607_1_) {
        if (OpenGlHelper.isFramebufferEnabled()) {
            this.framebufferFilter = p_147607_1_;
            api.getGLStateManager().bindTexture(this.framebufferTexture);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, (float) p_147607_1_);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, (float) p_147607_1_);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            api.getGLStateManager().bindTexture(0);
        }
    }

    public void checkFramebufferComplete() {
        int i = OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER);

        if (i != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
            if (i == OpenGlHelper.GL_FB_INCOMPLETE_ATTACHMENT) {
                throw new RuntimeException("GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT");
            } else if (i == OpenGlHelper.GL_FB_INCOMPLETE_MISS_ATTACH) {
                throw new RuntimeException("GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT");
            } else if (i == OpenGlHelper.GL_FB_INCOMPLETE_DRAW_BUFFER) {
                throw new RuntimeException("GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER");
            } else if (i == OpenGlHelper.GL_FB_INCOMPLETE_READ_BUFFER) {
                throw new RuntimeException("GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER");
            } else {
                throw new RuntimeException("glCheckFramebufferStatus returned unknown status:" + i);
            }
        }
    }

    public void bindFramebufferTexture() {
        if (OpenGlHelper.isFramebufferEnabled()) {
            api.getGLStateManager().bindTexture(this.framebufferTexture);
        }
    }

    public void unbindFramebufferTexture() {
        if (OpenGlHelper.isFramebufferEnabled()) {
            api.getGLStateManager().bindTexture(0);
        }
    }

    public void forceBind(boolean resetViewport) {
        currentlyBinding = this;

        if (OpenGlHelper.isFramebufferEnabled()) {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, this.framebufferObject);

            if (resetViewport) {
                api.getGLStateManager().viewport(0, 0, this.framebufferWidth, this.framebufferHeight);
            }
        }
    }

    private void saveStencilState() {
        if (!this.useDepth) {
            return;
        }

        this.stencilTestEnabled = StencilClipManager.isStencilEnabled();

        if (this.stencilTestEnabled) {
            this.stencilFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
            this.stencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF);
            this.stencilValueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
            this.stencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
            this.stencilOpFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
            this.stencilOpZFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
            this.stencilOpZPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        }

        this.stencilStateInitialized = true;
    }

    private void restoreStencilState() {
        if (!this.useDepth || !this.stencilStateInitialized) {
            return;
        }

        if (this.stencilTestEnabled) {
            StencilClipManager.enableStencilTest();
            GL11.glStencilFunc(this.stencilFunc, this.stencilRef, this.stencilValueMask);
            GL11.glStencilMask(this.stencilWriteMask);
            GL11.glStencilOp(this.stencilOpFail, this.stencilOpZFail, this.stencilOpZPass);
        } else {
            StencilClipManager.disableStencilTest();
        }
    }

    public void bindFramebuffer(boolean p_147610_1_) {

        if (this == currentlyBinding && currentlyBinding != mcFramebuffer)
            return;

        if (currentlyBinding != null) {
            currentlyBinding.saveStencilState();
        }

        currentlyBinding = this;

        if (OpenGlHelper.isFramebufferEnabled()) {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, this.framebufferObject);

            if (p_147610_1_) {
                api.getGLStateManager().viewport(0, 0, this.framebufferWidth, this.framebufferHeight);
            }
        }

        this.restoreStencilState();
    }

    public void unbindFramebuffer() {

        if (currentlyBinding != null) {
            currentlyBinding.saveStencilState();
        }

        currentlyBinding = mcFramebuffer;

        if (OpenGlHelper.isFramebufferEnabled()) {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        }
    }

    public void setFramebufferColor(float p_147604_1_, float p_147604_2_, float p_147604_3_, float p_147604_4_) {
        this.framebufferColor[0] = p_147604_1_;
        this.framebufferColor[1] = p_147604_2_;
        this.framebufferColor[2] = p_147604_3_;
        this.framebufferColor[3] = p_147604_4_;
    }

    public void setFramebufferColor(int rgb, float alpha) {

        float f1 = (rgb >> 16 & 255) * DIVIDE_BY_255;
        float f2 = (rgb >> 8 & 255) * DIVIDE_BY_255;
        float f3 = (rgb & 255) * DIVIDE_BY_255;

        this.framebufferColor[0] = f1;
        this.framebufferColor[1] = f2;
        this.framebufferColor[2] = f3;
        this.framebufferColor[3] = alpha;
    }

    public void framebufferRender(int width, int height) {
        this.framebufferRenderExt(width, height, true);
    }

    int renderCallList = -1;
    float lastWidth = -1,  lastHeight = -1;

    private void updateRenderCallList(int width, int height) {

        float f = (float) width;
        float f1 = (float) height;
        float f2 = (float) this.framebufferWidth / (float) this.framebufferTextureWidth;
        float f3 = (float) this.framebufferHeight / (float) this.framebufferTextureHeight;

        if (renderCallList != -1) {
            GL11.glDeleteLists(renderCallList, 1);
        }

        renderCallList = GL11.glGenLists(1);

        GL11.glNewList(this.renderCallList, GL11.GL_COMPILE);

        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        GL11.glTexCoord2f(0.0F, f3);
        GL11.glVertex2f(0.0F, 0.0F);
        GL11.glTexCoord2f(0.0F, 0);
        GL11.glVertex2f(0.0F, f1);
        GL11.glTexCoord2f(f2, f3);
        GL11.glVertex2f(f, 0.0F);
        GL11.glTexCoord2f(f2, 0);
        GL11.glVertex2f(f, f1);
        GL11.glEnd();

        GL11.glEndList();

    }

    public void framebufferRenderExt(int width, int height, boolean p_178038_3_) {
        if (OpenGlHelper.isFramebufferEnabled()) {
            api.getGLStateManager().colorMask(true, true, true, true);
            api.getGLStateManager().disableDepth();
            api.getGLStateManager().depthMask(false);
            api.getGLStateManager().matrixMode(GL11.GL_PROJECTION);
            api.getGLStateManager().loadIdentity();
            api.getGLStateManager().ortho(0.0D, width, height, 0.0D, 1000.0D, 3000.0D);
            api.getGLStateManager().matrixMode(GL11.GL_MODELVIEW);
            api.getGLStateManager().loadIdentity();
            api.getGLStateManager().translate(0.0F, 0.0F, -2000.0F);
            api.getGLStateManager().viewport(0, 0, width, height);
            api.getGLStateManager().enableTexture2D();
            api.getGLStateManager().disableLighting();
            api.getGLStateManager().disableAlpha();

            if (p_178038_3_) {
                api.getGLStateManager().disableBlend();
                api.getGLStateManager().enableColorMaterial();
            }

            api.getGLStateManager().color(1.0F, 1.0F, 1.0F, 1.0F);
            this.bindFramebufferTexture();
            float f = (float) width;
            float f1 = (float) height;

            if (lastWidth != f || lastHeight != f1) {
                lastWidth = f;
                lastHeight = f1;
                this.updateRenderCallList(width, height);
            }

            api.getGLStateManager().callList(this.renderCallList);

            this.unbindFramebufferTexture();
            api.getGLStateManager().depthMask(true);
            api.getGLStateManager().colorMask(true, true, true, true);
        }
    }

    public void framebufferClearNoBinding() {
//        this.bindFramebuffer(true);
        api.getGLStateManager().clearColor(this.framebufferColor[0], this.framebufferColor[1], this.framebufferColor[2], this.framebufferColor[3]);
        int i = GL11.GL_COLOR_BUFFER_BIT;

        if (this.useDepth) {
            api.getGLStateManager().clearDepth(1.0D);
            i |= GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT;
        }

        api.getGLStateManager().clear(i);
        StencilClipManager.clear();
//        this.unbindFramebuffer();
    }

    public void framebufferClear() {
        this.bindFramebuffer(true);
        this.framebufferClearNoBinding();
        this.unbindFramebuffer();
    }

    private static Framebuffer mcFramebuffer = new Framebuffer(1, -1);

    public static Framebuffer getMcFramebuffer() {
        return mcFramebuffer;
    }

    static int lastDisplayWidth = 0, lastDisplayHeight = 0;

    public static void updateMcFramebuffer() {
        if (currentlyBinding == null || (lastDisplayWidth != Display.getWidth() || lastDisplayHeight != Display.getHeight())) {
            lastDisplayWidth = Display.getWidth();
            lastDisplayHeight = Display.getHeight();

            int fbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int texId = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER,
                    OpenGlHelper.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
            );

            mcFramebuffer.framebufferObject = fbo;
            mcFramebuffer.framebufferTexture = texId;
            mcFramebuffer.framebufferWidth = Display.getWidth();
            mcFramebuffer.framebufferHeight = Display.getHeight();

            currentlyBinding = mcFramebuffer;
            System.out.println("fbo = " + fbo + ", texId = " + texId);
        }
    }
}