package tritium.rendering.shader.uniform;

import org.lwjgl.opengl.GL20;
import tritium.rendering.shader.ShaderProgram;

public class Uniform3f {

    private final int location;
    private float value1, value2, value3;

    public Uniform3f(ShaderProgram shader, String uniformName) {
        this.location = GL20.glGetUniformLocation(shader.getProgramId(), uniformName);
    }

    public void setValue(float value1, float value2, float value3) {

        if (this.value1 != value1 || this.value2 != value2 || this.value3 != value3) {
            GL20.glUniform3f(location, value1, value2, value3);
            this.value1 = value1;
            this.value2 = value2;
            this.value3 = value3;
        }

    }
}