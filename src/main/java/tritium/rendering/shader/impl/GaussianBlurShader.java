package tritium.rendering.shader.impl;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import tritium.rendering.Framebuffer;
import tritium.rendering.StencilClipManager;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.Shader;
import tritium.rendering.shader.ShaderProgram;
import tritium.rendering.shader.uniform.Uniform1i;
import tritium.rendering.shader.uniform.Uniform2f;
import tritium.rendering.shader.uniform.UniformFB;
import tritium.utils.timing.Timer;

import java.nio.FloatBuffer;
import java.util.List;

public class GaussianBlurShader extends Shader {

    private final ShaderProgram blurProgram = new ShaderProgram("blur.frag", "vertex.vsh");
    private Framebuffer inputFramebuffer = new Framebuffer(Display.getWidth(), Display.getHeight(), true);
    private Framebuffer outputFramebuffer = new Framebuffer(Display.getWidth(), Display.getHeight(), true);
    private GaussianKernel gaussianKernel = new GaussianKernel(0);

    private final Uniform1i u_radius = new Uniform1i(blurProgram, "u_radius");
    private final UniformFB u_kernel = new UniformFB(blurProgram, "u_kernel");

    private final Uniform1i u_diffuse_sampler = new Uniform1i(blurProgram, "u_diffuse_sampler");
    private final Uniform1i u_other_sampler = new Uniform1i(blurProgram, "u_other_sampler");
    private final Uniform2f u_texel_size = new Uniform2f(blurProgram, "u_texel_size");
    private final Uniform2f u_direction = new Uniform2f(blurProgram, "u_direction");

    @Override
    public void run(List<Runnable> runnable) {
        // Prevent rendering
        if (!Display.isVisible()) {
            return;
        }

        this.update();

        this.setActive(this.isActive() || !runnable.isEmpty());

        if (this.isActive()) {
            this.inputFramebuffer.bindFramebuffer(true);
            this.inputFramebuffer.setFramebufferColor(1, 1, 1, 0);
            this.inputFramebuffer.framebufferClearNoBinding();

            StencilClipManager.disableStencilTest();
            runnable.forEach(Runnable::run);

            // TODO: make radius and other things as a setting
            final int radius = 5;
            final float compression = .5f;

            this.outputFramebuffer.bindFramebuffer(true);
            this.outputFramebuffer.framebufferClearNoBinding();
            StencilClipManager.disableStencilTest();

            this.blurProgram.start();

            if (this.gaussianKernel.getSize() != radius) {
                this.gaussianKernel = new GaussianKernel(radius);
                this.gaussianKernel.compute();

                final FloatBuffer buffer = BufferUtils.createFloatBuffer(radius);
                buffer.put(this.gaussianKernel.getKernel());
                buffer.flip();

                u_radius.setValue(radius);
                u_kernel.setValue(buffer);
            }

            u_diffuse_sampler.setValue(0);
            u_other_sampler.setValue(20);
            u_texel_size.setValue((float) (1.0F / RenderSystem.getWidth()), (float) (1.0F / RenderSystem.getHeight()));
//            u_texel_size.setValue((float) (1.0F / Display.getWidth() * .5), (float) (1.0F / Display.getHeight() * .5));
            u_direction.setValue(compression, 0.0F);

            api.getGLStateManager().enableBlend();
            api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            api.getGLStateManager().disableAlpha();
            Framebuffer.getMcFramebuffer().bindFramebufferTexture();
            ShaderProgram.drawQuadFlipped();

            Framebuffer.getMcFramebuffer().bindFramebuffer(true);

            u_direction.setValue(0.0F, compression);
            outputFramebuffer.bindFramebufferTexture();
            GL13.glActiveTexture(GL13.GL_TEXTURE20);
            inputFramebuffer.bindFramebufferTexture();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);

//                    api.getGLStateManager().blendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
//
            ShaderProgram.drawQuadFlipped();
            api.getGLStateManager().disableBlend();

            ShaderProgram.stop();
        }
    }

    @Override
    public void update() {
        this.setActive(false);

        if (Display.getWidth() != inputFramebuffer.framebufferWidth || Display.getHeight() != inputFramebuffer.framebufferHeight) {
            inputFramebuffer.deleteFramebuffer();
            inputFramebuffer = new Framebuffer(Display.getWidth(), Display.getHeight(), true);

            outputFramebuffer.deleteFramebuffer();
            outputFramebuffer = new Framebuffer(Display.getWidth(), Display.getHeight(), true);

        } else {
//            inputFramebuffer.framebufferClear();
//            outputFramebuffer.framebufferClear();
        }
    }
}
