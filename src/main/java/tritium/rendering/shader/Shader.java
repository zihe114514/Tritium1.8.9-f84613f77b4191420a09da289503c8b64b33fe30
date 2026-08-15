package tritium.rendering.shader;

import lombok.Getter;
import lombok.Setter;
import tritium.interfaces.SharedConstants;

import java.util.List;

@Getter
@Setter
public abstract class Shader implements SharedConstants {
    private boolean active;

    public abstract void run(List<Runnable> runnable);

    public abstract void update();
}
