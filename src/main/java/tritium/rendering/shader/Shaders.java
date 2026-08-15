package tritium.rendering.shader;

import tritium.rendering.shader.impl.*;

public class Shaders {
    public static Shader BLOOM_SHADER = new BloomShader();
    public static GaussianBlurShader BLUR_SHADER = new GaussianBlurShader();
    public static BlendShader BLEND = new BlendShader();

    public static ROQShader ROQ_SHADER = new ROQShader();
    public static ROGQShader ROGQ_SHADER = new ROGQShader();
    public static RQShader RQ_SHADER = new RQShader();
    public static RQTShader RQT_SHADER = new RQTShader();
    public static RQGShader RQG_SHADER = new RQGShader();

    public static Deconverge DECONVERGE = new Deconverge();

    public static StencilShader STENCIL = new StencilShader();

    public static VFFadeoutShader VF_FADEOUT = new VFFadeoutShader();
}
