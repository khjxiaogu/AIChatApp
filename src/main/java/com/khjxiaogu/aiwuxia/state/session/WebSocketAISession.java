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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.AIChatService;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.ApplicationAttributes;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolApprovalRequest;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;
import com.khjxiaogu.aiwuxia.tools.NameTranslator;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.webserver.web.lowlayer.WebsocketEvents;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.UnorderedThreadPoolEventExecutor;

public class WebSocketAISession extends AISession implements WebsocketEvents,ChatIdentity {

	/**
	 * 消息内容的 JSON 序列化器，供带结构化内容（文本/图片等）的帧使用。
	 */
	protected static final Gson GSON = new GsonBuilder()
			.registerTypeAdapter(MessageContents.class, new MessageContents.Serilizer<>())
			.create();

	/** 单页历史记录条数 */
	private static final int HISTORY_PAGE_SIZE = 20;
	/** 单页历史记录条数上限，防止客户端一次请求过多 */
	private static final int HISTORY_PAGE_MAX = 100;

	ChannelGroup conn=new DefaultChannelGroup(new UnorderedThreadPoolEventExecutor(3));
	protected final AIChatService parent;
	private final String chatId;
	File fn;
	boolean isLocalAudioEnabled=false;

	AtomicBoolean lock=new AtomicBoolean();
	ApplicationAttributes attributes;
	public WebSocketAISession(AIChatService par,String uid,String chatid,AIApplication aiapp,ApplicationAttributes attr,File fn, ISaveData data) {
		super(uid, data,aiapp);
		this.fn = fn;
		this.parent=par;
		this.chatId=chatid;
		this.attributes=attr;
	}


	@Override
	public void onOpen(Channel conn, FullHttpRequest handshake) {
		
		this.conn.add(conn);
		prepareInitialHint();
		if(shouldSendBacklog()) {
			requireMoreMessages();
		}
		// 生成中的思维链只存在于会话内存缓冲区，不随历史下发，连接（重连）时需要单独补发
		sendGeneratingReasoning();
		
		getAiapp().prepareScene(this);
		boolean hasAudioService=VoiceModelHandler.hasSupportedAudio(this.getRoleName(Role.ASSISTANT));
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object()
				.add("type", "status")
				.add("generating", isGenerating())
				.add("conversation_id", chatId)
				.add("price", this.getPrice())
				.add("isVoiceEnabled",super.getExtraData().isAudioSession)
				.add("isVoiceUsable",hasAudioService)
				.add("models", buildModelList())
				.add("modelTree", buildModelTree())
				.add("model", getModelHint()).end().toString()));
	}

	/**
	 * 构建当前应用可用模型的平铺列表，供不支持目录树的旧前端使用。
	 *
	 * @return 每项包含模型标识 key 与翻译后显示名 name 的 JSON 数组
	 */
	protected JsonArray buildModelList() {
		JsonArray models=new JsonArray();
		for(String s:attributes.models) {
			if(s==null||s.isEmpty())
				continue;
			NameTranslator helper=parent.modelTranslations.get(s.split("/")[0]);
			String translate=s;
			if(helper!=null)
				translate=helper.translate(s);
			
			models.add(JsonBuilder.object().add("key", s).add("name", translate).end());
		}
		return models;
	}

	/**
	 * 构建当前应用可用模型的目录树。
	 * <p>
	 * 模型标识按 "/" 切分成分层节点，公共前缀合并为同一个目录节点；
	 * 每个节点自身也是一个合法的模型标识（即该节点的完整路径），
	 * 因此中间层目录同样可以被选中。
	 * 节点默认全部展开，折叠状态由前端各自维护，这里只输出结构与翻译后的显示名。
	 * </p>
	 *
	 * @return 树形结构的 JSON 数组，每项包含 key（完整路径）、name（显示名）与 children（子节点）
	 */
	protected JsonArray buildModelTree() {
		JsonArray tree=new JsonArray();
		// 以完整路径为键，保证不同模型共用的前缀只生成一个节点
		Map<String,JsonObject> known=new LinkedHashMap<>();
		for(String model:attributes.models) {
			if(model==null||model.isEmpty())
				continue;
			NameTranslator helper=parent.modelTranslations.get(model.split("/")[0]);
			JsonArray siblings=tree;
			StringBuilder path=new StringBuilder();
			for(String segment:model.split("/")) {
				if(segment.isEmpty())
					continue;
				if(path.length()>0)
					path.append('/');
				path.append(segment);
				String current=path.toString();
				JsonObject node=known.get(current);
				if(node==null) {
					String translate=helper==null?segment:helper.translate(segment);
					node=new JsonObject();
					node.addProperty("key", current);
					// 无翻译（例如服务商本身的译名为空）时回退为原始片段，避免出现无名节点
					node.addProperty("name", translate==null||translate.isEmpty()?segment:translate);
					node.add("children", new JsonArray());
					known.put(current, node);
					siblings.add(node);
				}
				siblings=node.getAsJsonArray("children");
			}
		}
		return tree;
	}

	/**
	 * 获取当前会话使用的模型标识。
	 * <p>
	 * 会话未显式指定模型（{@code modelHint} 为空）时回退到应用配置的默认模型，
	 * 因此前端上的“默认模型”即等价于应用默认模型，调用方无需再判断空值。
	 * </p>
	 *
	 * @return 模型标识，应用也没有默认模型时返回空字符串
	 */
	@Override
	public String getModelHint() {
		String hint=super.getModelHint();
		if(!hint.isEmpty())
			return hint;
		return attributes==null?"":attributes.getDefaultModelHint();
	}

	/**
	 * 切换当前会话使用的模型，并把最终生效的模型回发给前端。
	 * <p>
	 * 空字符串表示使用默认模型；其余只接受本应用声明过的模型标识（即目录树中任意节点的路径）。
	 * 非法标识不会生效，此时回发当前生效的模型，避免前后端状态不一致。
	 * </p>
	 *
	 * @param model 模型标识（按 "/" 分层的完整路径）
	 */
	public void setModel(String model) {
		final String requested=model==null?"":model;
		if(!requested.isEmpty()&&!attributes.models.contains(requested)) {
			sendModelHint(getExtraData().modelHint);
			return;
		}
		getCommandExec().submit(()->{
			getExtraData().modelHint=requested;
			flush();
		});
		sendModelHint(requested);
	}

	/**
	 * 把当前生效的模型回发给前端。
	 * <p>
	 * 默认按对话前端的帧格式下发 {@code model} 字段；使用其它帧协议的前端（如绘画前端按 {@code type} 分发）
	 * 可重写本方法。
	 * </p>
	 *
	 * @param model 当前生效的模型标识
	 */
	protected void sendModelHint(String model) {
		sendFrame(JsonBuilder.object().add("type", "model").add("model", model).end().toString());
	}
	
	/**
	 * 等待初始化提示完成，子类可重写以改变等待方式（例如不阻塞连接建立）。
	 */
	protected void prepareInitialHint() {
		try {
			provideInitialHint().get();
		} catch (InterruptedException | ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	/**
	 * 把一次历史写入排到会话命令线程上执行，并在写入完成后落盘。
	 * <p>
	 * 历史条目只能在会话命令线程上修改：生成同样跑在这条线程上，若在 WebSocket/HTTP 线程里
	 * 直接写历史（例如生成过程中上传图片、发送消息），新条目会插到正在流式输出的助手条目之后，
	 * 随后的思考内容、助手消息、状态快照都会挂到错误的条目上；请求构建遍历历史时也可能与写入并发，
	 * 落盘时的序列化更会读到写了一半的状态。
	 * </p>
	 * <p>
	 * 命令线程是单线程执行器，写入按提交顺序执行，因此与正在进行的生成天然串行：
	 * 生成期间提交的写入会排到这一轮之后执行。
	 * </p>
	 *
	 * @param write         历史写入动作
	 * @param failureNotice 写入失败时给用户的提示，可为 null（不提示）
	 */
	protected void submitHistoryWrite(Runnable write, String failureNotice) {
		getCommandExec().submit(() -> {
			try {
				write.run();
				flush();
			} catch (Throwable t) {
				// 任务在命令线程上异步执行，异常不会传回调用方，这里显式记录并提示
				parent.getLogger().printStackTrace(t);
				if (failureNotice != null)
					sendNotice(failureNotice);
			}
		});
	}

	/**
	 * 连接建立（含断开重连）后补发正在生成、尚未写入历史的思维链。
	 * <p>
	 * 推理内容在首个助手内容产生之前只保存在会话内存里（{@link AISession#currentReasoner}），
	 * 不会随历史分页下发；需要展示思考过程的前端可重写本方法，按自己的帧格式补发一次，
	 * 否则重连后只能看到重连之后新产生的思考内容。
	 * </p>
	 * <p>
	 * 本方法由 WebSocket 线程在连接建立时调用，可能与生成线程并发，实现时按只读方式取用缓冲区。
	 * </p>
	 */
	protected void sendGeneratingReasoning() {
		// 默认前端不展示思考过程，无需补发
	}

	/**
	 * 连接建立时是否需要下发历史记录，子类可重写。
	 *
	 * @return 需要下发返回 true
	 */
	protected boolean shouldSendBacklog() {
		return super.getStage()!=ApplicationStage.INITIALIZE;
	}
	
	public void requireMoreMessages() {
		requireMoreMessages(-1, HISTORY_PAGE_SIZE);
	}
	/**
	 * 分页下发历史记录，连接建立时的初始加载与客户端向上滚动加载共用。
	 * <p>
	 * 历史条目按标识符升序排列，本方法从最新往最旧遍历，取 {@code beforeId} 之前的
	 * {@code limit} 条记录发送给客户端，并告知是否还有更早的记录。
	 * 客户端向上滚动时携带当前最旧记录的标识符作为 {@code beforeId} 即可继续加载。
	 * </p>
	 *
	 * @param beforeId 只返回标识符严格小于该值的历史条目；小于等于0表示从最新记录开始（初始加载）
	 * @param limit    本页最多返回的条目数
	 */
	public synchronized void requireMoreMessages(int beforeId, int limit) {
		if (limit <= 0) {
			limit = HISTORY_PAGE_SIZE;
		} else if (limit > HISTORY_PAGE_MAX) {
			limit = HISTORY_PAGE_MAX;
		}
		List<HistoryItem> his = new ArrayList<>();
		boolean hasMore = false;
		for (Iterator<HistoryItem> it = getHistory().reverseIterator(); it.hasNext();) {
			HistoryItem hisitem=it.next();
			if (beforeId > 0 && hisitem.getIdentifier() >= beforeId) continue;
			if (his.size() >= limit) {
				// 已经取满一页且还有更旧的记录
				hasMore = true;
				break;
			}
			his.add(0, hisitem);
		}
		// beforeId > 0 说明这是向上滚动加载的历史页，客户端需要插入到列表最前面
		postMessages(his, beforeId > 0, hasMore);
	}
	/**
	 * 从客户端请求中解析分页参数（{@code before_id}/{@code limit}）并下发历史记录，
	 * 未携带参数时下发最新一页。
	 *
	 * @param jo 客户端请求
	 */
	public void requireMoreMessages(JsonObject jo) {
		int beforeId = -1;
		if (jo.has("before_id") && !jo.get("before_id").isJsonNull()) {
			beforeId = jo.get("before_id").getAsInt();
		}
		int limit = HISTORY_PAGE_SIZE;
		if (jo.has("limit") && !jo.get("limit").isJsonNull()) {
			limit = jo.get("limit").getAsInt();
		}
		requireMoreMessages(beforeId, limit);
	}
	@Override
	public void onClose(Channel conn) {
		this.conn.remove(conn);
		if(this.conn.isEmpty()) {
			// 已无连接可答复许可请求，立即按拒绝收敛，避免生成线程等到超时
			cancelToolApprovals();
			if(!isGenerating()) {
				parent.markRelease(this);
			}
		}
	}
	/**
	 * 上行帧处理器表，键为帧的 {@code type}。
	 * <p>
	 * 上行协议与下行一致：客户端发来的每一帧都带 {@code type}，
	 * 通用指令（语音开关、撤回、重说、模型切换等）在这里按 type 分发；
	 * {@code message} 与 {@code requestBackLog} 因为需要先做会话级处理，
	 * 由 {@link #onMessage(Channel, String)} 直接处理，不放进本表。
	 * </p>
	 */
	public static Map<String,BiConsumer<JsonObject,WebSocketAISession>> frameHandlers=new HashMap<>();
	static{
		frameHandlers.put("setVoiceEnabled", (jo,state)->{
			state.getExtraData().isAudioSession=jo.get("voiceEnabled").getAsBoolean();
			state.sendFrame(JsonBuilder.object().add("type", "voice").add("isVoiceEnabled",state.getExtraData().isAudioSession).end().toString());
			state.flush();
		});
		frameHandlers.put("revert", (jo,state)->state.requestRevert());
		frameHandlers.put("regen", (jo,state)->state.requestRegen());
		frameHandlers.put("prompt", (jo,state)->{
			state.getCommandExec().submit(()->{
				state.handleUserInput(jo.get("input").getAsString(), jo.get("content").getAsString());
				state.flush();
			});
		});
		frameHandlers.put("toolApproval", (jo,state)->{
			// 直接在 WebSocket 线程回填答复：生成线程正阻塞等待，绝不能提交到 commandExec
			if(!jo.has("id"))
				return;
			String id=jo.get("id").getAsString();
			boolean approved=jo.has("approved")&&jo.get("approved").getAsBoolean();
			boolean accepted=state.completeToolApproval(id, approved);
			state.sendFrame(JsonBuilder.object()
					.add("type", "toolApprovalResult")
					.add("id", id)
					.add("approved", approved)
					.add("accepted", accepted)
					.end().toString());
		});
		frameHandlers.put("model", (jo,state)->{
			state.setModel(jo.has("model")&&!jo.get("model").isJsonNull()?jo.get("model").getAsString():"");
		});
		frameHandlers.put("genVoice", (jo,state)->{
			state.getCommandExec().submit(()->{
				HistoryItem last=state.getLast();
				// 撤回会软删除条目，全部撤回后这里可能取不到最后一条
				if(last!=null&&last.getRole()==Role.ASSISTANT) {
					if(last.getAudioId()==null) {
						String audioId=UUID.randomUUID().toString();
						CompletableFuture<Boolean> cf=state.getAiapp().generateVoice(state, last.getDisplayContent().toString(), audioId);
						try {
							if(cf.get()) {
								state.setAudioId(audioId);
								state.postAudioComplete(last.getIdentifier(),audioId);
								state.flush();
							}
						} catch (InterruptedException | ExecutionException e) {
							e.printStackTrace();
						}
					}
				}
			});
		});
	}
	/**
	 * 请求撤回最后一轮对话。
	 * <p>
	 * 实际动作在会话命令线程中执行，避免与正在进行的生成并发；
	 * 对话前端（{@code operation:"revert"}）与绘画前端（{@code type:"revert"}）共用本方法。
	 * </p>
	 * <p>
	 * 最后一条是尚未生成的用户输入时（绘画前端已发出、但还没有点“生成”的消息），
	 * 直接把它撤回并回填到输入框：这类输入不属于任何一轮对话，
	 * 也不受对话轮次计数限制（{@code AIApplication#doRevert} 在还没有完成任何一轮时会拒绝撤回）。
	 * </p>
	 */
	public void requestRevert() {
		getCommandExec().submit(()->{
			if(!revertPendingUserInput()) {
				getAiapp().doRevert(this);
			}
		});
	}

	/**
	 * 判断历史中最后一条是否为“待生成”的用户输入。
	 * <p>
	 * 绘画前端的用户消息在 {@code message} 帧到达时即写入历史并等待生成，
	 * 此时最后一条是用户消息而不是助手回复；已经完成的每一轮对话都以助手回复收尾。
	 * </p>
	 *
	 * @return 存在待生成的用户输入时返回 true
	 */
	protected boolean hasPendingUserInput() {
		HistoryItem last=getLast();
		return last!=null&&last.getRole()==Role.USER;
	}

	/**
	 * 撤回尚未生成的用户输入：把它从历史中移除，并回填到前端输入框。
	 * <p>
	 * 该类输入在录入时就已持久化，因此撤回后必须落盘，避免服务重启后又被读回来。
	 * </p>
	 *
	 * @return 是否撤回了一条待生成的用户输入
	 */
	protected boolean revertPendingUserInput() {
		if(!hasPendingUserInput())
			return false;
		HistoryItem removed=deleteLast();
		refillChatBox(removed.getContextContent());
		flush();
		return true;
	}

	/**
	 * 请求重新生成最后一条回复。
	 * <p>
	 * 与 {@link #requestRevert()} 相同，实际动作提交到会话命令线程串行执行。
	 * </p>
	 */
	public void requestRegen() {
		getCommandExec().submit(()->getAiapp().doRegen(this));
	}

	public boolean onModifyAttempt() {
		if(isGenerating)return false;
		return lock.compareAndSet(false, true);
	}
	public void onModifyComplete() {
		lock.set(false);
	}
	@Override
	public void onMessage(Channel conn, String message) {
		JsonObject jo=JsonParser.parseString(message).getAsJsonObject();
		// 心跳：ping -> pong，不参与其他消息处理
		if(Heartbeat.replyPongIfPing(conn, jo))
			return;
		// 上行协议与下行一致：所有帧都带 type
		if(!jo.has("type")||jo.get("type").isJsonNull())
			return;
		String frameType=jo.get("type").getAsString();
		if("message".equals(frameType)) {
			handleUserSpeech(conn, jo);
			return;
		}
		if("requestBackLog".equals(frameType)) {
			requireMoreMessages(jo);
			return;
		}
		BiConsumer<JsonObject, WebSocketAISession> handler=frameHandlers.get(frameType);
		if(handler!=null)
			handler.accept(jo, this);
	}

	/**
	 * 处理用户发言（{@code {type:"message",content:"..."}}）。
	 * <p>
	 * 同一时刻只允许一轮生成：会话锁被占用时提示用户稍后再试。
	 * </p>
	 *
	 * @param conn 发起请求的连接
	 * @param jo   上行帧
	 */
	protected void handleUserSpeech(Channel conn, JsonObject jo) {
		String speech=jo.has("content")&&!jo.get("content").isJsonNull()?jo.get("content").getAsString():null;
		if(speech==null)
			return;
		if(lock.compareAndSet(false, true)) {
			refillChatBox(MessageContents.EMPTY);
			getCommandExec().submit(()->{
				try {
					getAiapp().handleSpeech(this,new MutableMessageContents(speech));
					boolean hasAudioService=VoiceModelHandler.hasSupportedAudio(this.getRoleName(Role.ASSISTANT));
					if(hasAudioService&&!super.isAudioSession()) {
						if(isLocalAudioEnabled!=hasAudioService) {
							isLocalAudioEnabled=hasAudioService;
							conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("type", "voice").add("isVoiceUsable",isLocalAudioEnabled).end().toString()));
						}
					}
				}finally{
					lock.set(false);
				}
			});
			
		}else {
			sendNotice("操作太快啦，请稍后再试");
		}
	}

	@Override
	public void requestUserInput(String input, String prompt, Consumer<String> consumer) {
		sendFrame(JsonBuilder.object().add("type", "input").add("input", input).add("prompt", prompt).end().toString());
		super.requestUserInput(input, prompt, consumer);
	}

	/**
	 * 把工具调用许可请求下发给前端，等待用户答复。
	 * <p>
	 * 与 {@link #requestUserInput} 不同，答复不经 {@code commandExec} 处理：
	 * 发起请求的生成线程正阻塞等待答复，若答复也排队到同一个单线程执行器上就会死锁。
	 * </p>
	 */
	@Override
	protected void requestToolCallApproval(ToolApprovalRequest request) {
		sendFrame(JsonBuilder.object()
				.add("type", "toolApproval")
				.add("id", request.getId())
				.add("tool", request.getToolName())
				.add("arguments", request.getArguments())
				.add("reason", request.getReason())
				.add("timeout", getToolApprovalManager().getTimeoutMillis())
				.end().toString());
	}


	@Override
	public void sendNotice(String msg) {
		super.sendNotice(msg);
		sendFrame(JsonBuilder.object().add("type", "notice").add("content", msg).end().toString());
	}


	@Override
	public void delMessage(int num) {
		sendFrame(JsonBuilder.object().add("type", "remove").add("id", num).end().toString());
	}

	@Override
	public void appendMessage(int id, String message) {
		super.appendMessage(id, message);
		sendFrame(JsonBuilder.object()
				.add("type", "content")
				.add("id", id)
				.add("content", contentsToJson(new MutableMessageContents(message)))
				.end().toString());
	}

	@Override
	public void postMessage(int id, Role role, MessageContents message) {
		super.postMessage(id, role, message);
		sendFrame(JsonBuilder.object()
				.add("type", "message")
				.add("id", id)
				.add("title", getRoleName(role))
				// role 为内部小写标识（user/assistant/...），前端据此判断消息归属，不必依赖显示名
				.add("role", role.getRoleName())
				.add("content", contentsToJson(message))
				.end().toString());
	}
	public void sendFrame(String content) {
		conn.writeAndFlush(new TextWebSocketFrame(content));
	}
	@Override
	public void postAudioComplete(int id,String audioId) {
		super.postAudioComplete(id, audioId);
		sendFrame(JsonBuilder.object().add("type", "audio").add("id", id).add("audioId", audioId).end().toString());
	}
	@Override
	public void postMessages(List<HistoryItem> items) {
		postMessages(items, false, false);
	}
	/**
	 * 批量下发历史记录，并附带分页信息。
	 *
	 * @param items   按时间从旧到新排列的历史条目
	 * @param prepend {@code true} 表示这批记录比客户端已有的记录更早，客户端应插入到列表最前面
	 * @param hasMore 是否还有更早的历史记录可以继续加载
	 */
	public void postMessages(List<HistoryItem> items, boolean prepend, boolean hasMore) {
		super.postMessages(items);
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> ja=JsonBuilder.object().array("messages");
		for(HistoryItem i:items) {
			JsonArray thinking=new JsonArray();
			if(i.getReasoningContent()!=null&&i.getRole()==Role.ASSISTANT)
				for(MessageContent current:i.getReasoningContent()) {
					if (current instanceof PlainText) {
						thinking.add(((PlainText) current).toText());
					} else if (current instanceof ToolCallContent) {
						ToolCallContent content=(ToolCallContent) current;
						List<ToolCall> calls=content.getToolCalls();
						for (int idx=0;idx<calls.size();idx++) {
							ToolCall call=calls.get(idx);
							// 用户友好简述替代原始工具名称，具体内容按需附带
							ToolCallDisplay display=content.getDisplay(idx);
							JsonObjectBuilder<JsonObject> seg=JsonBuilder.object()
								.add("type", "tool_call")
								.add("tool_name", display.getBrief())
								.add("tool", call.function==null?"":call.function.name);
							if(display.hasContent())
								seg.add("content", contentsToJson(display.getContent()));
							thinking.add(seg.end());
						}
					} else if (current instanceof ToolContent) {
						// 工具结果的具体内容由工具自行提供，未提供时不展示原始结果
						ToolCallDisplay display=((ToolContent) current).getDisplay();
						if(display.hasContent()) {
							thinking.add(JsonBuilder.object()
								.add("type", "tool_result")
								.add("tool_name", display.getBrief())
								.add("content", contentsToJson(display.getContent()))
								.end());
						}
					}
				}
			// 统一协议：每条历史记录只带结构化 content 与思考过程
			JsonObjectBuilder<JsonArrayBuilder<JsonObjectBuilder<JsonObject>>> ix=ja.object()
				.add("id", i.getIdentifier())
				.add("title", getRoleName(i.getRole()))
				// role 为内部小写标识，前端据此判断消息归属
				.add("role", i.getRole().getRoleName())
				.add("thinking", thinking)
				.add("content", contentsToJson(i.getDisplayContent()));
			if(i.getAudioId()!=null)
				ix.add("audioId", i.getAudioId());
		}
		JsonObjectBuilder<JsonObject> outer=ja.end();
		outer.add("type", "batch_messages");
		outer.add("prepend", prepend);
		outer.add("hasMore", hasMore);
		JsonObject frame=outer.end();
		if(!items.isEmpty()) {
			// 客户端可用 oldestId 作为下一次向上滚动的游标
			frame.addProperty("oldestId", items.get(0).getIdentifier());
			frame.addProperty("newestId", items.get(items.size()-1).getIdentifier());
		}
		sendFrame(frame.toString());
	}
	/**
	 * 下发当前生成状态帧。
	 * <p>
	 * 前端用它纠正本地状态（正在生成 / 停止中）。例如停止请求到达时本轮其实已经结束，
	 * 只回一句提示会让"停止中"的按钮一直等下去，因此要把真实状态回发给前端。
	 * 重连时 {@link #onOpen(Channel, FullHttpRequest)} 的状态帧同样用于此目的。
	 * </p>
	 */
	protected void sendGenerationStatus() {
		sendFrame(JsonBuilder.object().add("type", "status").add("generating", isGenerating()).end().toString());
	}

	@Override
	public void onGenerateStart() {
		super.onGenerateStart();
		sendGenerationStatus();
	}
	public void sendSceneContent(String type,String value) {

		sendFrame(JsonBuilder.object().add("type", "scene").add("scene", type).add("scene_data", value).end().toString());
	}
	@Override
	public void onGenComplete() {
		
		
		parent.updateBrief(chatId, getAiapp().getBrief(this));
		String price=getPrice();
		
		parent.setPrice(chatId, price);
		super.onGenComplete();
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object()
				.add("type", "done")
				.add("price",price).end().toString()));
		if(this.conn.isEmpty()) {
			parent.markRelease(this);
		}
	}
	@Override
	public String getChatId() {
		return chatId;
	}
	@Override
	public void addUsage(UsageIntf<?> usage) {
		// 使用量类型由用量对象自身提供，用于补充配额消费明细记录
		String usageType = usage == null ? UsageIntf.DEFAULT_USAGE_NAME : usage.getUsageName();
		if(attributes.paidOnly) {
			int actualCost=(int) Math.ceil(usage.getEquivantTokens());
		
			if(actualCost>0) {
				parent.getLogger().info("已消费："+actualCost);
				parent.consumePaidTokens(user, actualCost, chatId, usageType);
			}
			
		}else
		if(!attributes.freeNow) {
			int actualCost=(int) Math.floor(usage.getEquivantTokens());
			if(actualCost>0) {
				parent.getLogger().info("已消费："+actualCost);
				parent.consumeTokens(user, actualCost, chatId, usageType);
			}
		}
		
		super.addStatUsage(usage);
	}
	@Override
	public boolean canGenerate() {
		if(attributes.paidOnly)
			return parent.hasAnyPaidTokenRemaining(user);
		if(!attributes.freeNow)
			return parent.hasAnyTokenRemaining(user);
		return true;
	}
	@Override
	public void refillChatBox(MessageContents text) {
		conn.writeAndFlush(new TextWebSocketFrame(JsonBuilder.object().add("type", "sendbox").add("content",text.toText()).end().toString()));
	}

	/**
	 * 将消息内容序列化为结构化 JSON 数组，供带结构化内容的前端使用。
	 *
	 * @param contents 消息内容，可为 null
	 * @return 内容数组，contents 为 null 时返回空数组
	 */
	protected static JsonElement contentsToJson(MessageContents contents) {
		if (contents == null) {
			return new JsonArray();
		}
		return GSON.toJsonTree(contents, MessageContents.class);
	}








}
