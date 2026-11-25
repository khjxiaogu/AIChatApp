package com.khjxiaogu.aiwuxia.llm.scheme;
public class TokenDetail {
    public long audio_tokens;
    public long cached_tokens;
    public long image_tokens;
    public long text_tokens;
    /** 视频输入 token（MiMo 等厂商会返回）。 */
    public long video_tokens;

    public TokenDetail() {
    }

    @Override
    public String toString() {
        return "音频token " + audio_tokens +
                "\n缓存token " + cached_tokens +
                "\n图像token " + image_tokens +
                "\n视频token " + video_tokens +
                "\n文本token " + text_tokens;
    }
    
    public synchronized void multiply(float factor) {
        this.audio_tokens = Math.round(this.audio_tokens * factor);
        this.cached_tokens = Math.round(this.cached_tokens * factor);
        this.image_tokens = Math.round(this.image_tokens * factor);
        this.text_tokens = Math.round(this.text_tokens * factor);
        this.video_tokens = Math.round(this.video_tokens * factor);
    }
    
    public synchronized void add(TokenDetail another) {
        audio_tokens += another.audio_tokens;
        cached_tokens += another.cached_tokens;
        image_tokens += another.image_tokens;
        text_tokens += another.text_tokens;
        video_tokens += another.video_tokens;
    }

    public synchronized void set(TokenDetail another) {
        audio_tokens = another.audio_tokens;
        cached_tokens = another.cached_tokens;
        image_tokens = another.image_tokens;
        text_tokens = another.text_tokens;
        video_tokens = another.video_tokens;
    }
}
