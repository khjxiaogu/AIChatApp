package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.respscheme.Choice.Message;
import com.khjxiaogu.aiwuxia.state.GameStage;
import com.khjxiaogu.aiwuxia.state.History;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.StateIntf;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.BlockingReader;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public abstract class AIApplication {
	protected static Gson gs = new Gson();
	protected static Gson ppgs = new GsonBuilder().setPrettyPrinting().create();
	protected static ExecutorService exc=Executors.newCachedThreadPool();
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

	protected MessageHandler revertAndRegen=(state, ret) -> {
		if (state.getStage() == GameStage.STARTED) {
			if ("重新生成".equals(ret)) {
				HistoryItem last=state.getLast();
				if(last.getRole()==Role.ASSISTANT||last.getRole()==Role.USER) {
					HistoryItem hi = state.removeLast();
					if(last.getRole()==Role.ASSISTANT) {
						HistoryItem userhi = state.removeLast();
						state.minRow();
						if (hi.lastState != null) {
							state.getState().set(hi.lastState);
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
							HistoryItem userhi = state.removeLast();
							state.minRow();
							if (hi.lastState != null) {
								state.getState().set(hi.lastState);
							}
						}
					}
				}
				return null;
			}
		}
		return ret;
	};

	public static AISession.AIData dataFromJson(File jsonFile) throws JsonSyntaxException, IOException {
		return gs.fromJson(FileUtil.readString(jsonFile), AISession.AIData.class);
	}

	public static History historyFromJson(File jsonFile) throws JsonSyntaxException, IOException {
		return gs.fromJson(JsonParser.parseString(FileUtil.readString(jsonFile)).getAsJsonObject().get("history").getAsJsonObject(), History.class);
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

	public void handleSpeech(AISession state, String ret) {
		state.onGenStart();
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

	public abstract void provideInitial(AISession state);

	public RespScheme sendAIRequest(JsonObject req) throws IOException {
		String tosend = gs.toJson(req);
		logger.info(ppgs.toJson(req));
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

	public BlockingReader sendAIStreamedRequest(JsonObject req, Consumer<Usage> gainUsage) throws IOException {
		req.addProperty("stream", true);
		req.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		String tosend = gs.toJson(req);
		logger.info(ppgs.toJson(req));
		BlockingReader readable=new BlockingReader();
		
		Usage usage=new Usage();
		AtomicBoolean hasReasoner=new AtomicBoolean();
		HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
	
				.post(true).send(tosend).readSSE(exc, (ev,s)->{
					if(s==null||"[DONE]".equals(s)) {
						//System.out.println();
						logger.info("=================Usage===============");
						logger.info(usage);
						gainUsage.accept(usage);
						readable.putCh(null);
						return;
					}
					RespScheme scheme=gs.fromJson(s, RespScheme.class);
					Message delta=scheme.choices.get(0).delta;
					if(delta.reasoning_content!=null&&!delta.reasoning_content.isEmpty()) {
						if(!hasReasoner.getAndSet(true)) {
							logger.info("=================Reasoner===============");
						}
						System.out.print(delta.reasoning_content);
					}
					if(delta.content!=null&&!delta.content.isEmpty()) {
						
						readable.putCh(delta.content);
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
	public File getResource(String path) {
		return null;
	}



	public void prepareScene(AISession state) {

		
	};
}