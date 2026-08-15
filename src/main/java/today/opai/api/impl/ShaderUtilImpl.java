package today.opai.api.impl;

import today.opai.api.interfaces.render.ShaderUtil;

/**
 * S1 阶段：直接执行绘制回调（无 Bloom）。S6 渲染高保真阶段接入真实 Bloom 后处理。
 */
public class ShaderUtilImpl implements ShaderUtil {

    @Override
    public void drawWithBloom(Runnable draw) {
        draw.run();
    }
}
