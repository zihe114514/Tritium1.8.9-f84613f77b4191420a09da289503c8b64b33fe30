package tritium.utils.logging;

import lombok.Getter;

public enum LogLevel {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3),
    CRITICAL(4);

    @Getter
    private final int level;

    LogLevel(int level) {
        this.level = level;
    }
}
