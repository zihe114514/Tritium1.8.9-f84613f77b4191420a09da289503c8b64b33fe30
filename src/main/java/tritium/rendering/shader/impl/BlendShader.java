package tritium.rendering.shader.impl;


import tritium.rendering.shader.ShaderProgram;
import tritium.rendering.shader.uniform.Uniform1i;

/**
 * @author IzumiiKonata
 * @since 2024/12/3 21:17
 */
public class BlendShader {

    private final ShaderProgram blendProgram = new ShaderProgram("blend.frag", "vertex.vsh");
    private final Uniform1i textureIn = new Uniform1i(blendProgram, "textureIn");

    public void render() {
//        api.getGLStateManager().disableAlpha();

        this.blendProgram.start();
        textureIn.setValue(0);

        ShaderProgram.drawQuadFlipped();
//        api.getGLStateManager().disableBlend();

        ShaderProgram.stop();
    }


}
