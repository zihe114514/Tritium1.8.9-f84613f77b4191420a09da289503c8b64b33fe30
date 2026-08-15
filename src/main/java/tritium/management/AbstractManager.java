package tritium.management;

import lombok.Getter;
import tritium.utils.logging.LogManager;
import tritium.utils.logging.Logger;

/**
 * 抽象管理器类
 * @author IzumiiKonata
 * @since 2023/12/10
 */
public abstract class AbstractManager {

    @Getter
    private final String name;
    public final Logger logger;

    public AbstractManager(String name) {
        this.name = name;
        this.logger = LogManager.getLogger(name);

        this.logger.debug("<init> @ {}", name);
    }

    public abstract void init();

    public abstract void stop();

}
