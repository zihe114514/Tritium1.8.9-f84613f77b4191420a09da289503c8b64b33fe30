package tritium.rendering.shader.uniform;

import org.lwjgl.opengl.GL20;
import tritium.rendering.shader.ShaderProgram;

/**
 * @author IzumiiKonata
 * @since 2024/10/29 21:38
 */
public class Uniform1f {

    private final int location;
    private float value;

    public Uniform1f(ShaderProgram shader, String uniformName) {
        this.location = GL20.glGetUniformLocation(shader.getProgramId(), uniformName);
    }

    public void setValue(float value) {

        if (this.value != value) {
            GL20.glUniform1f(location, value);
            this.value = value;
        }

    }

}
