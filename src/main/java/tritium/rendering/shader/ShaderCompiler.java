package tritium.rendering.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import tritium.interfaces.SharedConstants;
import tritium.utils.Location;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class ShaderCompiler implements SharedConstants {

    public static int compile(final String fragment, final String vertex) {
        final String fragmentSource = getShaderResource(fragment);
        final String vertexSource = getShaderResource(vertex);

        if (fragment == null || vertex == null) {
            System.out.println("An error occurred whilst creating shader");
            return -1;
        }

        final int fragmentId = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        final int vertexId = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);

        GL20.glShaderSource(fragmentId, fragmentSource);
        GL20.glShaderSource(vertexId, vertexSource);
        GL20.glCompileShader(fragmentId);
        GL20.glCompileShader(vertexId);

        if (!checkIfCompiled(fragmentId)) return -1;
        if (!checkIfCompiled(vertexId)) return -1;

        final int programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, fragmentId);
        GL20.glAttachShader(programId, vertexId);
        GL20.glValidateProgram(programId);
        GL20.glLinkProgram(programId);
        GL20.glDeleteShader(fragmentId);
        GL20.glDeleteShader(vertexId);

        return programId;
    }

    private static boolean checkIfCompiled(final int shaderId) {
        final boolean compiled = GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE;
        if (compiled) return true;

        final String shaderLog = GL20.glGetShaderInfoLog(shaderId, 8192);
        System.out.println("\nError while compiling shader: ");
        System.out.println("-------------------------------");
        System.out.println(shaderLog);
        return false;
    }

    public static String getShaderResource(final String resource) {
        try {
            final InputStream inputStream = Location.of("/tritium/shaders/" + resource).getResourceStream();
            final InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            final BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            String source = "";

            try {
                for (String s; (s = bufferedReader.readLine()) != null; source += s + System.lineSeparator()) ;
            } catch (final IOException ignored) {
            }

            inputStream.close();

            return source;
        } catch (final IOException | NullPointerException e) {
            System.out.println("An error occurred while getting a shader resource");
            e.printStackTrace();
            return null;
        }
    }

}