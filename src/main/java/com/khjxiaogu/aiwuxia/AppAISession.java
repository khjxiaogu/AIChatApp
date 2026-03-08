package com.khjxiaogu.aiwuxia;

import java.io.FileInputStream;
import java.io.FileNotFoundException;

import com.khjxiaogu.aiwuxia.state.HistoryHolder;

import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.advanced.AdvancedPlayer;

public class AppAISession extends AISession {



	public AppAISession(String user, HistoryHolder historym, AIData data) {
		super(user, historym, data);
	}

	@Override
	public void postAudioComplete(int id, String audioId) {
		super.postAudioComplete(id, audioId);

		 try {
        // 创建一个文件输入流指向你的MP3文件
        FileInputStream fileInputStream = new FileInputStream("save/voice/"+audioId+".mp3");
        // 创建AdvancedPlayer对象
        
			AdvancedPlayer player = new AdvancedPlayer(fileInputStream);
			player.play();
		} catch (JavaLayerException | FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
