package com.khjxiaogu.aiwuxia;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 从网络输入流中分段导出WAV音频数据。
 * 输入流必须包含标准的WAV头部（RIFF、fmt、data chunk），之后是连续的音频数据。
 * 每次调用 {@link #exportNextSegment(String)} 会将当前可用的音频数据导出为一个完整的WAV文件，
 * 并清空内部缓冲区，下次调用将从新的位置开始累积数据。
 */
public class WavStreamExporter implements Closeable {
    private final InputStream inputStream;
    private final ByteArrayOutputStream audioBuffer; // 累积音频数据（不含头部）
    private boolean headerParsed;
    private AudioFormat audioFormat;

    /**
     * 构造一个导出器，绑定到指定的输入流。
     *
     * @param inputStream 网络输入流，位置必须指向WAV文件的开头
     */
    public WavStreamExporter(InputStream inputStream) {
        this.inputStream = inputStream;
        this.audioBuffer = new ByteArrayOutputStream();
        this.headerParsed = false;
    }

    /**
     * 解析WAV头部，提取音频格式信息，并丢弃头部数据。
     * 此方法会阻塞直到头部被完整读取（至少找到data chunk）。
     *
     * @throws IOException 如果流读取失败、头部格式错误或流结束
     */
    private void parseHeader() throws IOException {
        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        byte[] temp = new byte[4096];
        int bytesRead;

        // 持续读取直到找到data chunk
        while (true) {
            // 尝试从流中读取更多数据（非阻塞方式读取当前可用数据，但为了简化，这里使用阻塞读取）
            // 注意：如果网络流尚未到达，此read会阻塞直到有数据
            bytesRead = inputStream.read(temp);
            if (bytesRead == -1) {
                throw new IOException("输入流提前结束，无法解析WAV头部");
            }
            headerBuffer.write(temp, 0, bytesRead);

            // 尝试解析缓冲区中的头部
            byte[] headerData = headerBuffer.toByteArray();
            ByteBuffer buffer = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN);

            // 检查RIFF头
            if (headerData.length < 12) continue;
            if (!"RIFF".equals(new String(headerData, 0, 4))) {
                throw new IOException("无效的WAV文件：缺少RIFF标识");
            }
            if (!"WAVE".equals(new String(headerData, 8, 4))) {
                throw new IOException("无效的WAV文件：缺少WAVE标识");
            }

            // 遍历chunk
            int pos = 12;
            boolean foundFmt = false;
            boolean foundData = false;
            int dataStartPos = -1;

            while (pos + 8 <= headerData.length) {
                String chunkId = new String(headerData, pos, 4);
                int chunkSize = buffer.getInt(pos + 4);

                if ("fmt ".equals(chunkId)) {
                    // 确保fmt chunk完整
                    if (pos + 8 + chunkSize > headerData.length) break; // 需要更多数据
                    foundFmt = true;
                    // 解析fmt子块（只取前16字节，忽略扩展）
                    int fmtOffset = pos + 8;
                    short audioFormatVal = buffer.getShort(fmtOffset);
                    short numChannels = buffer.getShort(fmtOffset + 2);
                    int sampleRate = buffer.getInt(fmtOffset + 4);
                    int byteRate = buffer.getInt(fmtOffset + 8);
                    short blockAlign = buffer.getShort(fmtOffset + 12);
                    short bitsPerSample = buffer.getShort(fmtOffset + 14);

                    // 存储音频格式
                    audioFormat = new AudioFormat(
                            audioFormatVal,
                            numChannels,
                            sampleRate,
                            byteRate,
                            blockAlign,
                            bitsPerSample
                    );
                } else if ("data".equals(chunkId)) {
                    foundData = true;
                    dataStartPos = pos + 8; // data chunk数据起始位置
                    break; // 找到data即可停止遍历
                }

                pos += 8 + chunkSize;
            }

            if (foundData) {
                if (!foundFmt) {
                    throw new IOException("WAV文件中缺少fmt chunk");
                }
                // 如果已经读取了部分音频数据（dataStartPos之后的数据），将其转移到audioBuffer
                if (dataStartPos < headerData.length) {
                    int extraLen = headerData.length - dataStartPos;
                    audioBuffer.write(headerData, dataStartPos, extraLen);
                }
                // 丢弃头部数据，后续直接读取音频数据
                headerParsed = true;
                return;
            }
            // 未找到data chunk，继续读取
        }
    }

    /**
     * 导出当前可用的音频数据段为一个新的WAV文件。
     * 第一次调用时会自动解析头部，后续调用只导出从上一次导出到现在的音频数据。
     *
     * @param filePath 输出文件路径
     * @throws IOException 如果读取或写入失败
     */
    public void exportNextSegment(String filePath) throws IOException {
        if (!headerParsed) {
            parseHeader();
        }
        System.out.println("loadi");
        // 读取当前所有可用的音频数据（非阻塞方式）
        byte[] temp = new byte[4096];
        int bytesRead;
        while (inputStream.available() > 0 && (bytesRead = inputStream.read(temp)) != -1) {
            audioBuffer.write(temp, 0, bytesRead);
        }

        // 获取当前累积的音频数据
        byte[] audioData = audioBuffer.toByteArray();
        if (audioData.length == 0) {
            // 没有新数据，可以选择不生成文件或抛出异常
            System.out.println("没有新音频数据可导出");
            return;
        }

        // 写入WAV文件
        writeWavFile(filePath, audioData, audioFormat);

        // 清空缓冲区，准备下一段
        audioBuffer.reset();
    }

    /**
     * 将音频数据写入标准的WAV文件。
     *
     * @param filePath   输出路径
     * @param audioData  音频样本数据
     * @param format     音频格式
     * @throws IOException 写入失败
     */
    private void writeWavFile(String filePath, byte[] audioData, AudioFormat format) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(filePath);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            int dataSize = audioData.length;
            int fileSize = 36 + dataSize; // RIFF头(12) + fmt块(24) + data头(8) + 数据大小

            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

            // RIFF头
            header.put("RIFF".getBytes());
            header.putInt(fileSize - 8); // 文件总大小-8
            header.put("WAVE".getBytes());

            // fmt子块
            header.put("fmt ".getBytes());
            header.putInt(16); // fmt块大小（PCM为16）
            header.putShort(format.audioFormat);
            header.putShort(format.channels);
            header.putInt(format.sampleRate);
            header.putInt(format.byteRate);
            header.putShort(format.blockAlign);
            header.putShort(format.bitsPerSample);

            // data子块
            header.put("data".getBytes());
            header.putInt(dataSize);

            bos.write(header.array());
            bos.write(audioData);
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
        audioBuffer.close();
    }

    /**
     * 简单的音频格式封装。
     */
    private static class AudioFormat {
        final short audioFormat;      // 音频格式（1 = PCM）
        final short channels;         // 通道数
        final int sampleRate;         // 采样率
        final int byteRate;           // 字节率 = sampleRate * blockAlign
        final short blockAlign;       // 块对齐 = channels * bitsPerSample / 8
        final short bitsPerSample;    // 位深度

        AudioFormat(short audioFormat, short channels, int sampleRate,
                    int byteRate, short blockAlign, short bitsPerSample) {
            this.audioFormat = audioFormat;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.byteRate = byteRate;
            this.blockAlign = blockAlign;
            this.bitsPerSample = bitsPerSample;
        }
    }

}