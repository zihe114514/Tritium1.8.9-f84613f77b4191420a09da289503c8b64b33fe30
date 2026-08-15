package tritium.rendering;

import java.util.HashMap;

public class GaussianKernel {

    private static final HashMap<Integer, float[]> map = new HashMap<>();

    public static float[] generate(int size) {

        float[] floats = map.get(size);

        if (floats == null) {
            floats = new float[size * size];

            if (size % 2 == 0) {
                throw new IllegalArgumentException("卷积核大小必须是奇数");
            }

            int center = size / 2;
            float sigma = size / 3.0f;
            float kernelSum = 0.0f;

            float[][] kernel = new float[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    float x = i - center;
                    float y = j - center;
                    kernel[i][j] = (float) (Math.exp(-(x * x + y * y) / (2 * sigma * sigma)) / (2 * Math.PI * sigma * sigma));
                    kernelSum += kernel[i][j];
                }
            }

            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    kernel[i][j] /= kernelSum;
                }
            }

            for (int i = 0; i < size; i++) {
                System.arraycopy(kernel[i], 0, floats, i * size, size);
            }

            map.put(size, floats);
        }

        return floats;
    }

}
