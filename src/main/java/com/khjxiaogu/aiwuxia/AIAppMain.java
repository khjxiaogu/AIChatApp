package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.apps.AICharaTalkMain;
import com.khjxiaogu.aiwuxia.apps.AITRPGSceneMain;
import com.khjxiaogu.aiwuxia.state.MemoryHistory;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.webserver.builder.BasicWebServerBuilder;

public class AIAppMain {

	private AIAppMain() {
		// TODO Auto-generated constructor stub
	}

	public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
		String name="fengxitalk";
		int idx=1;
		//CodeDialog dialog = new CodeDialog("AIGalgame模拟器");
		try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
		AIChatWindow acw=new AIChatWindow();
		acw.setVisible(true);
		File dataFolder=new File("save");
		File saveData = new File(new File(dataFolder,"saveData"), "save+"+name+idx+".json");
		File modelFolder=new File(dataFolder,name);
		File metaFile=new File(modelFolder,"meta.json");
		JsonObject meta=JsonParser.parseString(FileUtil.readString(metaFile)).getAsJsonObject();
		
		AIApplication main = new AICharaTalkMain(dataFolder,name,meta.get("name").getAsString());
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
			aistate = new AppAISession("appuser",new MemoryHistory(), new AISession.AIData(),main);
			aistate.provideInitial();
		}
		
		acw.setStatus(main.constructSystem(aistate.getState()));
		// dialog.setBackLog(constructBackLog());
		acw.setUsage(aistate.getUsage());
		final AISession cstate = aistate;
		new Thread(()->{
			try {
				BasicWebServerBuilder.build().createURIRoot()
				.createWrapper(new AIChatLocal()).rule("/aichat")
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
						if (cstate.isGenerating)
							s += "\n生成中...";
						final String fs = s;
						String rs="";
						if(cstate.getReasoningContent()!=null) {
							rs=cstate.getReasoningContent();
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
				aistate.handleSpeech(ret);
				Thread.sleep(200);
				while(aistate.isGenerating) {
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
