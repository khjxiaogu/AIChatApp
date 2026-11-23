package com.khjxiaogu.aiwuxia.modelproxy;

public enum ModelType {
    AUDIO,
    SDXL,
    /** 歌声合成（Singing Voice Synthesis） */
    SVS,
    /** Qwen-Image 2.1 图片生成（本地 ComfyUI） */
    QWEN,
    /** MiniMax H3 视频生成（本地 ComfyUI，含立体声） */
    H3;

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
