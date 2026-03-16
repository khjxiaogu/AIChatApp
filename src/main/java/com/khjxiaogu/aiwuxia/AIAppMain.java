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
package com.khjxiaogu.aiwuxia;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.AIApplicationRegistry;
import com.khjxiaogu.aiwuxia.apps.AICharaTalkMain;
import com.khjxiaogu.aiwuxia.apps.AITRPGSceneMain;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.MemoryHistory;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.session.AppAISession;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.aiwuxia.voice.VoiceModelLocalServer;
import com.khjxiaogu.aiwuxia.voice.VolcanoVoiceApi;
import com.khjxiaogu.webserver.builder.BasicWebServerBuilder;

public class AIAppMain {

	private AIAppMain() {
		// TODO Auto-generated constructor stub
	}
	public static String printAndCollectContent(Reader output) throws IOException {
		BufferedReader br=new BufferedReader(output);
		int read;
		char[] ch=new char[32];
		StringBuilder sb=new StringBuilder();
		while((read=br.read(ch,0,32))!=-1) {
			if(read>0) {
				String input=String.valueOf(ch,0,read);
				System.out.print(input);
				sb.append(input);
			}
		}
		return sb.toString();
	}
	public static void main(String[] args) throws Throwable {
		String name="fengyidlc";
		int idx=0;
		//CodeDialog dialog = new CodeDialog("AIGalgame模拟器");
		try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
		LLMConnector.initDefault();
		VoiceModelHandler.model=new VolcanoVoiceApi();
		AIChatWindow acw=new AIChatWindow();
		acw.setVisible(true);
		File dataFolder=new File("save");
		File saveData = new File(new File(dataFolder,"saveData"), "save+"+name+idx+".json");
		//File saveData =new File(new File(dataFolder,"saveData"), "19d604d7e0e74232b7363fabfba81061.json");
		
		File modelFolder=new File(dataFolder,"apps/"+name);
		File metaFile=new File(modelFolder,"meta.json");
		JsonObject meta=JsonParser.parseString(FileUtil.readString(metaFile)).getAsJsonObject();
		
		AIApplication main = AIApplicationRegistry.createInstance(dataFolder, modelFolder, meta);
		AISession aistate = null;
		if (saveData.exists()) {
			aistate = new AppAISession("appuser",
				AIApplication.historyFromJson(saveData),
				AIApplication.dataFromJson(saveData),
				main);
		}

		// construct initail message
		if (aistate == null) {
			acw.setBackLog("正在生成初始面板...","");
			// RespScheme airetinit=sendAIRequest(constructAIrequest(null,null,null));
			aistate = new AppAISession("appuser",new MemoryHistory(), new AISession.ExtraData(),main);
			aistate.provideInitialHint();
		}
		
		acw.setStatus(main.constructSystem(aistate.getState()));
		// dialog.setBackLog(constructBackLog());
		acw.setUsage(aistate.getUsage());
		final AISession cstate = aistate;
		new Thread(()->{
			try {
				BasicWebServerBuilder.build().createURIRoot()
				.createWrapper(new VoiceModelLocalServer()).rule("/aichat")
				.complete()
				.complete()
				.setNotFound(new File(new File("save"), "404.html"))
				.compile()
				.serverHttp(8998)
				.info("http服务端已开启");
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}).start();
		Thread updateThread = new Thread(() -> {

			try {
				while (true) {

					if (cstate.checkAndUnsetUpdated()) {
						String s = main.constructBackLog(cstate);
						if (cstate.isGenerating())
							s += "\n生成中...";
						final String fs = s;
						String rs="";
						if(cstate.getReasonerContent()!=null) {
							rs=cstate.getReasonerContent();
						}
						final String rfs=rs;
						SwingUtilities.invokeLater(() -> {
							acw.setBackLog(fs,rfs);
						});
					}
					Thread.sleep(100);
				}
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		updateThread.setDaemon(true);
		updateThread.start();
		cstate.setUpdated();
		//System.out.println(main.makeSummaryrequest(cstate,main.constructSummaryBackLog(cstate)));
		while (true) {
			String ret = null;
			while (ret == null || ret.isEmpty()) {
				ret = acw.showDialog();
			}

			try {
				aistate.handleUserSpeech(ret);
				Thread.sleep(200);
				while(aistate.isGenerating()) {
					Thread.sleep(100);
				}
				AIApplication.saveToJson(aistate, saveData);
				acw.setStatus(main.constructSystem(aistate.getState()));
				// dialog.setBackLog(constructBackLog());
				if (aistate != null)
					acw.setUsage(aistate.getUsage());
			} catch (Throwable t) {
				t.printStackTrace();

			}

		}
	}
}
