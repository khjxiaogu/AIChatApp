package com.khjxiaogu.aiwuxia;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

public class TtsWebsocketDemo {
    public static final String API_URL = "wss://openspeech.bytedance.com/api/v1/tts/ws_binary";
    public static void main(String[] args) throws Exception {
    	sendTTS("（摇头，握住他的手）：“真的不用。可能是早上没吃早餐，低血糖而已。小骨，不要这么紧张，我会照顾好自己。");//
    }
    public static long ticks;
    public static void sendTTS(String otext) throws Exception {
        // set your appid and access_token
    	otext=processMessage(otext);
        String appid = System.getProperty("volcappid");
        String accessToken = System.getProperty("volcsecret");
        JsonObject send=JsonBuilder.object().object("app").add("appid", appid).add("token", accessToken).add("cluster", "volcano_icl").end()
        .object("user").add("uid", "testapp").end()
        .object("audio").add("voice_type", "S_vTd4CtrV1").add("encoding", "wav").end()
        .object("request").add("reqid",UUID.randomUUID().toString()).add("operation", "submit").add("text", otext).add("text_type", "ssml").end()
        .end();
        System.out.println(send.toString());
        ticks=System.nanoTime();
        TtsWebsocketClient ttsWebsocketClient = new TtsWebsocketClient(accessToken);
        ttsWebsocketClient.submit(send.toString());

    }
    public static String processMessage(String orgText) {
    	String text=orgText.replaceAll("枫茜", "枫西").replaceAll("（[^）]+）", "");
    	return text;
    }
    public static void playWavBytes(InputStream bais) throws UnsupportedAudioFileException, IOException, LineUnavailableException {
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(bais));
        AudioFormat format = audioInputStream.getFormat();

        // 创建 SourceDataLine
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();
        // 缓冲区大小（可以自定义）
        byte[] buffer = new byte[4096];
        int bytesRead;
        
        try {
            while ((bytesRead = audioInputStream.read(buffer)) != -1) {
                line.write(buffer, 0, bytesRead);
            }
        } finally {
            // 等待缓冲区数据播放完毕，然后关闭
            line.drain();
            line.close();
            audioInputStream.close();
        }
        System.out.println((System.nanoTime()-ticks)/1000/1000f);
    }

    public static class TtsWebsocketClient extends WebSocketClient {
    	QueueInputStream qis=new QueueInputStream();
    	WavStreamExporter wsi;
    	AtomicInteger i=new AtomicInteger(0);
        public TtsWebsocketClient(String accessToken) throws IOException {
            super(URI.create(API_URL), Map.of("Authorization", "Bearer; " + accessToken));
            wsi=new WavStreamExporter(qis);
        }

        public void submit(String ttsRequest) throws InterruptedException {
            byte[] jsonBytes = ttsRequest.getBytes(StandardCharsets.UTF_8);
            byte[] header = {0x11, 0x10, 0x10, 0x00};
            ByteBuffer requestByte = ByteBuffer.allocate(8 + jsonBytes.length);
            requestByte.put(header).putInt(jsonBytes.length).put(jsonBytes);

            this.connectBlocking();
            synchronized (this) {
                this.send(requestByte.array());
            }
        }

        @SuppressWarnings("unused")
		@Override
        public void onMessage(ByteBuffer bytes) {
            int protocolVersion = (bytes.get(0) & 0xff) >> 4;
            int headerSize = bytes.get(0) & 0x0f;
            int messageType = (bytes.get(1) & 0xff) >> 4;
            int messageTypeSpecificFlags = bytes.get(1) & 0x0f;
            int serializationMethod = (bytes.get(2) & 0xff) >> 4;
            int messageCompression = bytes.get(2) & 0x0f;
            int reserved = bytes.get(3) & 0xff;
            bytes.position(headerSize * 4);
            byte[] fourByte = new byte[4];
            if (messageType == 11) {
                // Audio-only server response
            	System.out.println("received audio-only response.");
                if (messageTypeSpecificFlags == 0) {
                    // Ack without audio data
                } else {
                    bytes.get(fourByte, 0, 4);
                    int sequenceNumber = new BigInteger(fourByte).intValue();
                    bytes.get(fourByte, 0, 4);
                    int payloadSize = new BigInteger(fourByte).intValue();
                    byte[] payload = new byte[payloadSize];
                    bytes.get(payload, 0, payloadSize);
                    try {
                        this.qis.addChunk(payload);
                        wsi.exportNextSegment(i.getAndIncrement()+".wav");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (sequenceNumber < 0) {
                        // received the last segment
                    	System.out.println("received all audio data.");
                    	this.qis.addChunk(null);
                    	try {
							wsi.exportNextSegment(i.getAndIncrement()+".wav");
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
                        this.close(CloseFrame.NORMAL, "received all audio data.");
                    }
                }
            } else if (messageType == 15) {
                // Error message from server
                bytes.get(fourByte, 0, 4);
                int code = new BigInteger(fourByte).intValue();
                bytes.get(fourByte, 0, 4);
                int messageSize = new BigInteger(fourByte).intValue();
                byte[] messageBytes = new byte[messageSize];
                bytes.get(messageBytes, 0, messageSize);
                String message = new String(messageBytes, StandardCharsets.UTF_8);
                throw new TtsException(code, message);
            } else {
            	 System.out.println("Received unknown response message type: "+messageType);
            }
        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {
        	 System.out.println("opened connection");
        }

        @Override
        public void onMessage(String message) {
            System.out.println("received message: " + message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            synchronized (this) {
                notify();
            }
        }

        @Override
        public void onError(Exception e) {
            close(CloseFrame.NORMAL, e.toString());
        }
    }

    public static class TtsException extends RuntimeException {
        /**
		 * 
		 */
		private static final long serialVersionUID = 1L;
        public TtsException(int code, String message) {
            super("code=" + code + ", message=" + message);
        }
    }
}