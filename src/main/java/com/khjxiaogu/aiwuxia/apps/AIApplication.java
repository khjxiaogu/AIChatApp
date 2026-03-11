package com.khjxiaogu.aiwuxia.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.respscheme.Choice.Message;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.state.GameStage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.MemoryHistory;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.session.AISession.AIData;
import com.khjxiaogu.aiwuxia.state.status.StateIntf;
import com.khjxiaogu.aiwuxia.utils.ClientTruncatedException;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public abstract class AIApplication {
	protected static Gson gs = new Gson();
	protected static Gson ppgs = new GsonBuilder().setPrettyPrinting().create();

	
	protected String system;
	protected SimpleLogger logger=new SimpleLogger("AI智能");
	public static interface MessageHandler {
		public String apply(AISession state,String message) throws Throwable;
	}

	protected List<MessageHandler> handlers = new ArrayList<>();

	protected static int popFirst(Set<Integer> set) {
		Iterator<Integer> it = set.iterator();
		int ret = it.next();
		it.remove();
		return ret;
	}
	public static String readFile(File f) throws IOException {
		return FileUtil.readString(f).replace("\r", "");
	}
	protected MessageHandler revertAndRegen=(state, ret) -> {
		if (state.getStage() == GameStage.STARTED) {
			if ("重新生成".equals(ret)) {
				HistoryItem last=state.getLast();
				if(last.getRole()==Role.ASSISTANT||last.getRole()==Role.USER) {
					HistoryItem hi = state.removeLast();
					if(hi.getRole()==Role.ASSISTANT) {
						HistoryItem userhi = state.removeLast();
						state.minRow();
						if (hi.getLastState() != null) {
							state.getState().set(hi.getLastState());
						}
						return userhi.getContent().toString();
					}
					return hi.getContent().toString();
					
				}
				return null;
			} else if ("撤回".equals(ret)) {
				if(state.getRow()>state.getMinRow()) {
					HistoryItem last=state.getLast();
					if(last.getRole()==Role.ASSISTANT||last.getRole()==Role.USER) {
						
						HistoryItem hi = state.removeLast();
						if(last.getRole()==Role.ASSISTANT) {
							state.removeLast();
							state.minRow();
							if (hi.getLastState() != null) {
								state.getState().set(hi.getLastState());
							}
						}
					}
				}
				return null;
			}
		}
		return ret;
	};
	public boolean isLocalVoiceSupported() {
		return false;
	}
	public static AISession.AIData dataFromJson(File jsonFile) throws JsonSyntaxException, IOException {
		return gs.fromJson(FileUtil.readString(jsonFile), AISession.AIData.class);
	}

	public static MemoryHistory historyFromJson(File jsonFile) throws JsonSyntaxException, IOException {
		return gs.fromJson(JsonParser.parseString(FileUtil.readString(jsonFile)).getAsJsonObject().get("history").getAsJsonObject(), MemoryHistory.class);
	}

	public static void saveToJson(AISession aistate, File jsonFile) throws IOException {
		JsonElement je=ppgs.toJsonTree(aistate.getData());
		je.getAsJsonObject().add("history", ppgs.toJsonTree(aistate.getHistory()));
		FileUtil.transfer(ppgs.toJson(je), jsonFile);
	}
	public String constructBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getContent())
				.append("\n");
		}

		return sb.toString();

	}



	public AIApplication() {
		super();
	}
	public void handleSpeech(AISession state,final String messageInput) {
		if(state.isGenerating()) {
			state.postMessage(-1, Role.APPLICATION,"内容生成中，请稍后再试。");
			return;
		}
		state.onGenStart();	
		String ret=messageInput;
		int mid=(int) UUID.randomUUID().getMostSignificantBits();
		String sid=Integer.toString(mid,16);
		logger.info("message received "+sid);
		for (MessageHandler i : handlers) {
			try {
				if ((ret=i.apply(state,ret))==null)
					break;
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
		logger.info("message handled "+sid);
		state.onGenComplete();
		
	}
	public abstract void provideInitial(AISession state);
	public void handleReasonerContent(AIOutput output,AISession state) throws IOException {
		BufferedReader br=new BufferedReader(output.getReasoner());
		int read;
		state.resetReasoner();
		char[] ch=new char[32];
		while((read=br.read(ch,0,32))!=-1) {
			if(read>0) {
				String input=String.valueOf(ch,0,read);
				state.appendReasoning(input);
			}
		}
	}
	public String getRoleName(AISession state,Role role) {
		return role.getName();
	}
	public abstract String getName();
	public abstract String constructSystem(StateIntf state);
	public abstract String getBrief(AISession state);

	/**
	 * This method should be read-only and mt support
	 * */
	public void prepareScene(AISession state) {

		
	};
}