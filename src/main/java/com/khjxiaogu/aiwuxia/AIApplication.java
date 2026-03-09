package com.khjxiaogu.aiwuxia;

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
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.state.AIOutput;
import com.khjxiaogu.aiwuxia.state.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.state.GameStage;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.MemoryHistory;
import com.khjxiaogu.aiwuxia.state.StateIntf;
import com.khjxiaogu.aiwuxia.utils.ClientTruncatedException;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public abstract class AIApplication {
	protected static Gson gs = new Gson();
	protected static Gson ppgs = new GsonBuilder().setPrettyPrinting().create();
	public  static ExecutorService exc=Executors.newCachedThreadPool();
	
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
		if(state.isGenerating) {
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

	public RespScheme sendAIRequest(JsonObject req) throws IOException {
		String tosend = gs.toJson(req);
		logger.info("trigger generation");
		JsonObject retjs = HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
	
				.post(true).send(tosend).readJson();
		//System.out.println(ppgs.toJson(retjs));
		RespScheme resp = gs.fromJson(retjs, RespScheme.class);
		if(resp.choices.get(0).message.reasoning_content!=null) {
			logger.info("=================Reasoner===============");
			logger.info(resp.choices.get(0).message.reasoning_content);
		}
		logger.info("=================Usage===============");
		logger.info(resp.usage);
		return resp;
	}
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
	public AIOutput sendAIStreamedRequest(JsonObject req, Consumer<Usage> gainUsage) throws IOException {
		req.addProperty("stream", true);
		req.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		logger.info("trigger generation");
		String tosend = gs.toJson(req);
		StreamedAIOutput readable=new StreamedAIOutput();
		Usage usage=new Usage();
		HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
	
				.post(true).send(tosend).readSSE(exc, (ev,s)->{
					if(s==null||"[DONE]".equals(s)) {
						System.out.println();
						logger.info("=================Usage===============");
						logger.info(usage);
						logger.info("finish generation");
						gainUsage.accept(usage);
						readable.endContent();
						return;
					}
					//if(readable.isEnded())
					//	throw new ClientTruncatedException();
					RespScheme scheme=gs.fromJson(s, RespScheme.class);
					Message delta=scheme.choices.get(0).delta;
					if(delta.reasoning_content!=null&&!delta.reasoning_content.isEmpty()) {
						readable.putReasoner(delta.reasoning_content);
					}
					if(delta.content!=null&&!delta.content.isEmpty()) {
						
						readable.putContent(delta.content);
					}
					if(scheme.usage!=null)
						usage.add(scheme.usage);
					
				});
	
	
		return readable;
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