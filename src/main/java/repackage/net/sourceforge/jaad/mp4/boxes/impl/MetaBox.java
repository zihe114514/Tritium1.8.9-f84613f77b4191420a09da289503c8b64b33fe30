package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * Optional iTunes/Apple metadata container. Audio playback does not consume
 * its children; only the enclosing movie's audio track tables are required.
 */
public class MetaBox extends FullBox {

    public MetaBox() {
        super("Meta Box");
    }

    @Override
    public void decode(MP4InputStream in) throws IOException {
        /*
         * Some valid M4A files contain vendor-specific metadata children that
         * the old JAAD parser cannot walk safely. Failing that optional box
         * previously made the complete M4A unreadable although the AAC track
         * tables were valid. Consume the metadata payload atomically so the
         * enclosing movie can continue parsing its track boxes.
         */
        in.skipBytes(getLeft(in));
    }
}