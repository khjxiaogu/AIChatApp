package com.khjxiaogu.aiwuxia.respscheme;

public class CompletionTokenDetail {
    public long accepted_prediction_tokens;
    public long audio_tokens;
    public long reasoning_tokens;
    public long rejected_prediction_tokens;

    public CompletionTokenDetail() {
    }

    @Override
    public String toString() {
        return "接受预测token " + accepted_prediction_tokens +
                "\n音频token " + audio_tokens +
                "\n推理token " + reasoning_tokens +
                "\n拒绝预测token " + rejected_prediction_tokens;
    }
    
    public synchronized void multiply(float factor) {
        this.accepted_prediction_tokens = Math.round(this.accepted_prediction_tokens * factor);
        this.audio_tokens = Math.round(this.audio_tokens * factor);
        this.reasoning_tokens = Math.round(this.reasoning_tokens * factor);
        this.rejected_prediction_tokens = Math.round(this.rejected_prediction_tokens * factor);
    }

    public synchronized void add(CompletionTokenDetail another) {
        accepted_prediction_tokens += another.accepted_prediction_tokens;
        audio_tokens += another.audio_tokens;
        reasoning_tokens += another.reasoning_tokens;
        rejected_prediction_tokens += another.rejected_prediction_tokens;
    }

    public synchronized void set(CompletionTokenDetail another) {
        accepted_prediction_tokens = another.accepted_prediction_tokens;
        audio_tokens = another.audio_tokens;
        reasoning_tokens = another.reasoning_tokens;
        rejected_prediction_tokens = another.rejected_prediction_tokens;
    }
}