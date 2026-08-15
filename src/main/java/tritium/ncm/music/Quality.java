package tritium.ncm.music;

import lombok.Getter;

/**
 * @author IzumiiKonata
 * @since 6/16/2023 10:03 AM
 */
public enum Quality {

    STANDARD("Standard"),
    HIGHER("Higher"),
    EXHIGH("ExHigh"),
    LOSSLESS("LossLess"),
    HIRES("HiRes"),
    JYEFFECT("JyEffect"),
    SKY("sky"),
    JYMASTER("JyMaster");

    @Getter
    private final String quality;

    Quality(String quality) {
        this.quality = quality;
    }

}
