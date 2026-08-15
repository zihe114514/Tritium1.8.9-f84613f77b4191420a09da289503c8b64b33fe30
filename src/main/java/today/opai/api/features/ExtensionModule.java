package today.opai.api.features;

import today.opai.api.enums.EnumModuleCategory;
import today.opai.api.interfaces.EventHandler;
import today.opai.api.interfaces.modules.values.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 对应原项目 today.opai.api.features.ExtensionModule。
 * 表示一个可开关的客户端功能模块（原 Opai feature）。
 */
public abstract class ExtensionModule {

    private final String name;
    private final String description;
    private final EnumModuleCategory category;
    private boolean enabled;
    private EventHandler eventHandler;
    protected final List<Value<?>> values = new ArrayList<>();

    public ExtensionModule(String name, String description, EnumModuleCategory category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public EnumModuleCategory getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            onEnabled();
        } else {
            onDisabled();
        }
    }

    protected void addValues(Value<?>... values) {
        for (Value<?> value : values) {
            this.values.add(value);
        }
    }

    protected void setEventHandler(EventHandler handler) {
        this.eventHandler = handler;
    }

    public EventHandler getEventHandler() {
        return eventHandler;
    }

    /** 每 tick 调用（原 onLoop 等价物）。 */
    public void onTick() {
    }

    /** 模块被启用时调用。 */
    public void onEnabled() {
    }

    /** 模块被禁用时调用。 */
    public void onDisabled() {
    }
}
