/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.state.session;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.AIChatWindow;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;

import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.advanced.AdvancedPlayer;

public class AppAISession extends AISession {

	File saveData;
	AIChatWindow window;
	public AppAISession(String user, HistoryHolder historym, ExtraData data,AIApplication aiapp,File saveData,AIChatWindow win) {
		super(user, historym, data,aiapp);
		this.saveData=saveData;
		this.window=win;
	}

	@Override
	public void refillChatBox(String text) {
		window.setInput(text);
	}

	@Override
	public void requestUserInput(String input, String prompt, Consumer<String> consumer) {
		consumer.accept(window.prompt(prompt));
		
	}

	@Override
	public void sendNotice(String msg) {
		window.showMessage(msg);
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

	@Override
	public void onGenComplete() {
		super.onGenComplete();
		try {
			AIApplication.saveToJson(this, saveData);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
