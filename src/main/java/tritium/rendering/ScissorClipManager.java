package tritium.rendering;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Screen-space rectangular clipping that follows the active OpenGL transform.
 *
 * <p>Unlike the stencil based clipper, scissor clipping cannot be invalidated by
 * rounded-image widgets opening another stencil level. This makes it suitable as
 * a hard safety boundary for scrolling viewports.</p>
 */
public final class ScissorClipManager {

    private static final FloatBuffer MODEL_VIEW = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private static final IntBuffer PREVIOUS_BOX = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer WINDOW_POSITION = BufferUtils.createFloatBuffer(3);

    private ScissorClipManager() {
    }

    public static void begin(double x, double y, double width, double height) {
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);

        boolean scissorWasEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int previousX = 0;
        int previousY = 0;
        int previousWidth = 0;
        int previousHeight = 0;
        if (scissorWasEnabled) {
            PREVIOUS_BOX.clear();
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, PREVIOUS_BOX);
            previousX = PREVIOUS_BOX.get(0);
            previousY = PREVIOUS_BOX.get(1);
            previousWidth = PREVIOUS_BOX.get(2);
            previousHeight = PREVIOUS_BOX.get(3);
        }

        MODEL_VIEW.clear();
        PROJECTION.clear();
        VIEWPORT.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODEL_VIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        double[][] corners = {
                {x, y},
                {x + Math.max(0, width), y},
                {x, y + Math.max(0, height)},
                {x + Math.max(0, width), y + Math.max(0, height)}
        };

        for (double[] corner : corners) {
            WINDOW_POSITION.clear();
            boolean projected = GLU.gluProject(
                    (float) corner[0], (float) corner[1], 0f,
                    MODEL_VIEW, PROJECTION, VIEWPORT, WINDOW_POSITION);
            if (!projected) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(0, 0, 0, 0);
                return;
            }

            double projectedX = WINDOW_POSITION.get(0);
            double projectedY = WINDOW_POSITION.get(1);
            minX = Math.min(minX, projectedX);
            minY = Math.min(minY, projectedY);
            maxX = Math.max(maxX, projectedX);
            maxY = Math.max(maxY, projectedY);
        }

        int viewportX = VIEWPORT.get(0);
        int viewportY = VIEWPORT.get(1);
        int viewportRight = viewportX + VIEWPORT.get(2);
        int viewportTop = viewportY + VIEWPORT.get(3);

        int left = Math.max(viewportX, (int) Math.floor(minX));
        int bottom = Math.max(viewportY, (int) Math.floor(minY));
        int right = Math.min(viewportRight, (int) Math.ceil(maxX));
        int top = Math.min(viewportTop, (int) Math.ceil(maxY));

        if (scissorWasEnabled) {
            left = Math.max(left, previousX);
            bottom = Math.max(bottom, previousY);
            right = Math.min(right, previousX + previousWidth);
            top = Math.min(top, previousY + previousHeight);
        }

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
    }

    public static void end() {
        GL11.glPopAttrib();
    }
}