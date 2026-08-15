package today.opai.api;

/**
 * 对应原项目 today.opai.api.Extension。原 ExtensionEntry 已被 @Mod 入口替代，
 * 此处仅保留类型与静态 getAPI() 以维持 API 表面一致性。
 */
public abstract class Extension {

    public abstract void initialize(OpenAPI openAPI);

    public abstract void onUnload();

    public static OpenAPI getAPI() {
        return OpenAPI.getInstance();
    }
}
