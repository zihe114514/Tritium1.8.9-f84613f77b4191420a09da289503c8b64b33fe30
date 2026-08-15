package tritium.screens.ncm;

import tritium.rendering.ui.container.Panel;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 19:51
 */
public abstract class NCMPanel extends Panel {

    public abstract void onInit();

    protected int getColor(NCMScreen.ColorType type) {
        return 0xFF000000 | NCMScreen.getColor(type);
    }

}
