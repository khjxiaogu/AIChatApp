package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingUtilities;

import com.khjxiaogu.aiwuxia.apps.AICharaTalkMain;
import com.khjxiaogu.aiwuxia.state.History;

public class AIAppMain {

	private AIAppMain() {
		// TODO Auto-generated constructor stub
	}

	public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {

		CodeDialog dialog = new CodeDialog("AIGalgame模拟器");
		File saveData = new File("save/saveData", "savefengyi2.json");
		AISession aistate = null;
		if (saveData.exists()) {
			aistate = new AISession(
				AIApplication.historyFromJson(saveData),
				AIApplication.dataFromJson(saveData));
		}

		AICharaTalkMain main = new AICharaTalkMain(new File("save"),"fengyitalk", "姚枫怡");
		// construct initail message
		if (aistate == null) {
			dialog.setBackLog("正在生成初始面板...");
			// RespScheme airetinit=sendAIRequest(constructAIrequest(null,null,null));
			aistate = new AISession(new History(), new AISession.AIData());
			main.provideInitial(aistate);
		}
		
		dialog.sarea.setText(main.constructSystem(aistate.getState()));
		// dialog.setBackLog(constructBackLog());
		dialog.usage.setText(aistate.getUsage());
		final AISession cstate = aistate;
		
		Thread updateThread = new Thread(() -> {

			try {
				while (true) {

					if (cstate.checkAndUnsetUpdated()) {
						String s = main.constructBackLog(cstate);
						if (cstate.isGenerating)
							s += "\n生成中...";
						final String fs = s;
						SwingUtilities.invokeLater(() -> {
							dialog.setBackLog(fs);
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
				ret = dialog.showDialog();
			}

			try {
				main.handleSpeech(aistate, ret);
				AIApplication.saveToJson(aistate, saveData);
				dialog.sarea.setText(main.constructSystem(aistate.getState()));
				// dialog.setBackLog(constructBackLog());
				if (aistate != null)
					dialog.usage.setText(aistate.getUsage());
			} catch (Throwable t) {
				t.printStackTrace();

			}

		}
	}
}
