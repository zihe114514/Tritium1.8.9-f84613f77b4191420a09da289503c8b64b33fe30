package com.muoniumplayer.core.rendering.shader;

import lombok.Getter;
import lombok.Setter;
import com.muoniumplayer.core.interfaces.SharedConstants;

import java.util.List;

@Getter
@Setter
public abstract class Shader implements SharedConstants {
    private boolean active;

    public abstract void run(List<Runnable> runnable);

    public abstract void update();
}
