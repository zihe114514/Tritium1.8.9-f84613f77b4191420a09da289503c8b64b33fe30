package tritium.rendering.shader;

import lombok.Getter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import tritium.interfaces.SharedConstants;
import tritium.rendering.rendersystem.RenderSystem;

@Getter
public class ShaderProgram implements SharedConstants {

    private final int programId;

    public ShaderProgram(final String fragmentPath, final String vertexPath) {
        this.programId = ShaderCompiler.compile(fragmentPath, vertexPath);
        if (this.programId == -1) {
            throw new RuntimeException("Failed to compile shader\n    Fragment: " + fragmentPath + "\n    Vertex: " + vertexPath);
        }
    }

    public static void drawQuadFlipped(final double x, final double y, final double width, final double height) {
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        GL11.glTexCoord2d(0, 1);
        GL11.glVertex2d(x, y);
        GL11.glTexCoord2d(0, 0);
        GL11.glVertex2d(x, y + height);
        GL11.glTexCoord2d(1, 1);
        GL11.glVertex2d(x + width, y);
        GL11.glTexCoord2d(1, 0);
        GL11.glVertex2d(x + width, y + height);
        GL11.glEnd();
    }

    public static void drawQuad(final double x, final double y, final double width, final double height) {
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        GL11.glTexCoord2d(0, 0);
        GL11.glVertex2d(x, y);
        GL11.glTexCoord2d(0, 1);
        GL11.glVertex2d(x, y + height);
        GL11.glTexCoord2d(1, 0);
        GL11.glVertex2d(x + width, y);
        GL11.glTexCoord2d(1, 1);
        GL11.glVertex2d(x + width, y + height);
        GL11.glEnd();
    }

    static double lastWidth = -1, lastHeight = -1;
    static double lastWidthSr = -1, lastHeightSr = -1;
    static int callList = -1;
    static int callListSr = -1;

    public static void drawQuadFlipped() {

        if (lastWidth != RenderSystem.getWidth() || lastHeight != RenderSystem.getHeight()) {
            lastWidth = RenderSystem.getWidth();
            lastHeight = RenderSystem.getHeight();

            if (callList != -1)
                GL11.glDeleteLists(callList, 1);

            callList = GL11.glGenLists(1);

            GL11.glNewList(callList, GL11.GL_COMPILE);
            drawQuadFlipped(0.0, 0.0, RenderSystem.getWidth(), RenderSystem.getHeight());
            GL11.glEndList();

        }

        GL11.glCallList(callList);

//        drawQuadFlipped(0.0, 0.0, RenderSystem.getWidth(), RenderSystem.getHeight());
    }

    public void start() {
        GL20.glUseProgram(programId);
    }

    public static void stop() {
        GL20.glUseProgram(0);
    }

}