package com.khjxiaogu.aiwuxia.voice;

public enum ModelType {
    AUDIO,
    SDXL,
    /** 歌声合成（Singing Voice Synthesis） */
    SVS;

    public static ModelType fromString(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
