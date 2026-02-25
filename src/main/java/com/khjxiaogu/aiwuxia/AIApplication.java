package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.scheme.Choice.Message;
import com.khjxiaogu.aiwuxia.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.scheme.Usage;

public abstract class AIApplication {
	static Gson gs = new Gson();
	static Gson ppgs = new GsonBuilder().setPrettyPrinting().create();
	static ExecutorService exc=Executors.newCachedThreadPool();
	protected String system;

	public static interface MessageHandler {
		public String apply(AIState state,String message) throws Throwable;
	}

	protected List<MessageHandler> handlers = new ArrayList<>();

	protected static int popFirst(Set<Integer> set) {
		Iterator<Integer> it = set.iterator();
		int ret = it.next();
		it.remove();
		return ret;
	}



	public static AIState.AIData dataFromJson(File jsonFile) throws JsonSyntaxException, IOException {
		return gs.fromJson(FileUtil.readString(jsonFile), AIState.AIData.class);
	}

	public static History historyFromJson(File jsonFile) throws JsonSyntaxException, IOException {
		return gs.fromJson(JsonParser.parseString(FileUtil.readString(jsonFile)).getAsJsonObject().get("history").getAsJsonObject(), History.class);
	}

	public static void saveToJson(AIState aistate, File jsonFile) throws IOException {
		JsonElement je=ppgs.toJsonTree(aistate.getData());
		je.getAsJsonObject().add("history", ppgs.toJsonTree(aistate.getHistory()));
		FileUtil.transfer(ppgs.toJson(je), jsonFile);
	}
	public String constructBackLog(AIState state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			sb.append(hs.role.getName()).append("：").append(hs.getContent())
				.append("\n");
		}

		return sb.toString();

	}
	boolean isUpdated;
	public boolean checkAndUnsetUpdated() {
		boolean res=isUpdated;
		isUpdated=false;
		return res;
	}
	public void setUpdated() {
		isUpdated=true;
		
	}
	public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
	
	
		CodeDialog dialog = new CodeDialog("AIGalgame模拟器");
		File saveData = new File("save/saveData","savefengyi.json");
		AIState aistate=null;
		if (saveData.exists()) {
			aistate=new AIState(
				historyFromJson(saveData),
				dataFromJson(saveData));
		}
		
	
		AIGalgameMain main=new AIGalgameMain("promptfengyi.txt");
		// construct initail message
		if (aistate == null) {
			dialog.setBackLog("正在生成初始面板...");
			// RespScheme airetinit=sendAIRequest(constructAIrequest(null,null,null));
			aistate = new AIState(new History(),new AIState.AIData());
			main.provideInitial(aistate);
		}
	
		dialog.sarea.setText(main.constructSystem(aistate));
		//dialog.setBackLog(constructBackLog());
		dialog.usage.setText(aistate.getUsage());
		final AIState cstate=aistate;
		Thread updateThread=new Thread(()->{
			
			
			try {
				while(true) {
					String s=main.constructBackLog(cstate);
					if(main.checkAndUnsetUpdated()) {
						SwingUtilities.invokeLater(()->{
							dialog.setBackLog(s);
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
		main.setUpdated();
		while (true) {
			String ret = null;
			while (ret == null || ret.isEmpty()) {
				ret = dialog.showDialog();
			}
			
			
			try {
				main.handleSpeech(aistate,ret);
				saveToJson(aistate,saveData);
				dialog.sarea.setText(main.constructSystem(aistate));
				//dialog.setBackLog(constructBackLog());
				if (aistate != null)
					dialog.usage.setText(aistate.getUsage());
			} catch (Throwable t) {
				t.printStackTrace();
	
			}
	
		}
	}

	public AIApplication() {
		super();
	}

	public void handleSpeech(AIState state, String ret) {
		for (MessageHandler i : handlers) {
			try {
				if ((ret=i.apply(state,ret))==null)
					break;
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
		state.onGenComplete();
	}

	public abstract void provideInitial(AIState state);

	public RespScheme sendAIRequest(JsonObject req) throws IOException {
		String tosend = gs.toJson(req);
		System.out.println(ppgs.toJson(req));
		JsonObject retjs = HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", System.getProperty("deepseektoken"))
	
				.post(true).send(tosend).readJson();
		System.out.println(ppgs.toJson(retjs));
		RespScheme resp = gs.fromJson(retjs, RespScheme.class);
		System.out.println("=================Reasoner===============");
		System.out.println(resp.choices.get(0).message.reasoning_content);
		System.out.println("=================Usage===============");
		System.out.println(resp.usage);
		setUpdated();
		return resp;
	}

	public FilledReadable sendAIStreamedRequest(JsonObject req, Consumer<Usage> gainUsage) throws IOException {
		req.addProperty("stream", true);
		req.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		String tosend = gs.toJson(req);
		System.out.println(ppgs.toJson(req));
		FilledReadable readable=new FilledReadable();
		System.out.println("=================Reasoner===============");
		Usage usage=new Usage();
		HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
	
				.post(true).send(tosend).readSSE(exc, (ev,s)->{
					if(s==null||"[DONE]".equals(s)) {
						System.out.println();
						System.out.println("=================Usage===============");
						System.out.println(usage);
						gainUsage.accept(usage);
						readable.putCh(null);
					}
					//System.out.println(s);
					RespScheme scheme=gs.fromJson(s, RespScheme.class);
					Message delta=scheme.choices.get(0).delta;
					if(delta.reasoning_content!=null&&!delta.reasoning_content.isEmpty())
						System.out.print(delta.reasoning_content);
					if(delta.content!=null&&!delta.content.isEmpty())
						readable.putCh(delta.content);
					if(scheme.usage!=null)
						usage.add(scheme.usage);
					setUpdated();
				});
	
	
		return readable;
	}
	public abstract String getName();
	public abstract String constructSystem(AIState state);
	public abstract String getBrief(AIState state);

}