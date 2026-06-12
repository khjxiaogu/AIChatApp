package com.khjxiaogu.aiwuxia.utils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * 将 Base64 编码的 PCM16 字节块累积并保存为 WAV 文件
 * 对应 Python 代码功能：
 * - 解码 Base64 得到 PCM16 小端字节
 * - 累积所有数据块
 * - 写入 WAV 文件（采样率 24000 Hz，单声道，16 位）
 */
public class PcmToWavConverter {

    private final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
    private final int sampleRate;

    /**
     * 构造转换器
     * @param sampleRate 采样率（Hz），通常为 24000
     */
    public PcmToWavConverter(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    /**
     * 添加一个音频块（Base64 编码的 PCM16 字节）
     * @param base64AudioData Base64 字符串，对应 Python 中的 audio["data"]
     */
    public void addAudioChunk(String base64AudioData) {
        byte[] pcmBytes = Base64.getDecoder().decode(base64AudioData);
        pcmBuffer.writeBytes(pcmBytes);
        System.out.printf("Received audio chunk of size %d bytes%n", pcmBytes.length);
    }

    /**
     * 将所有累积的 PCM 数据保存为 WAV 文件
     * @param filePath 输出文件路径（例如 "tmp/output.wav"）
     * @return 
     * @throws IOException 若文件写入失败
     */
    public byte[] saveAsWav() throws IOException {
        byte[] pcmData = pcmBuffer.toByteArray();
        if (pcmData.length == 0) {
            throw new IllegalStateException("No PCM data to write");
        }
        // 定义音频格式：24000 Hz, 16位, 单声道, 有符号, 小端
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        int bytesPerFrame = 2; // 16位单声道，每帧2字节
        long frameLength = pcmData.length / bytesPerFrame;

        try (ByteArrayInputStream bais = new ByteArrayInputStream(pcmData);
             AudioInputStream audioInputStream = new AudioInputStream(bais, format, frameLength);ByteArrayOutputStream baos=new ByteArrayOutputStream()) {
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, baos);
            return baos.toByteArray();
        }
    }
}