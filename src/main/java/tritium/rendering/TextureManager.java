package tritium.rendering;

import com.google.common.collect.Maps;
import lombok.Getter;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.texture.ITextureObject;
import tritium.utils.Location;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author IzumiiKonata
 * Date: 2026/4/1 09:22
 */
public class TextureManager {

    @Getter
    private static final TextureManager instance = new TextureManager();

    public final Map<Location, ITextureObject> mapTextureObjects = new ConcurrentHashMap<>(512);
    private final Map<String, Integer> mapTextureCounters = Maps.newHashMap();

    public ITextureObject getTexture(Location textureLocation) {
        return this.mapTextureObjects.get(textureLocation);
    }

    public Location getDynamicTextureLocation(String name, DynamicTexture texture) {
        Integer integer = this.mapTextureCounters.get(name);

        if (integer == null) {
            integer = 1;
        } else {
            integer = integer + 1;
        }

        this.mapTextureCounters.put(name, integer);
        Location resourcelocation = Location.of(String.format("dynamic/%s_%d", name, integer));
        this.loadTexture(resourcelocation, texture);
        return resourcelocation;
    }

    public void deleteTexture(Location textureLocation) {
        ITextureObject itextureobject = this.getTexture(textureLocation);

        if (itextureobject != null) {
            this.mapTextureObjects.remove(textureLocation);
            TextureUtil.deleteTexture(itextureobject.getGlTextureId());
        }
    }

    public void loadTexture(Location img, ITextureObject textureObj) {
        this.mapTextureObjects.put(img, textureObj);
    }

    public void bindTexture(Location location) {
        ITextureObject itextureobject = this.mapTextureObjects.get(location);

        if (itextureobject == null) {
            itextureobject = new DynamicTexture(location);
            this.loadTexture(location, itextureobject);
        }

        TextureUtil.bindTexture(itextureobject.getGlTextureId());
    }
}
