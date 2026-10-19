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
package com.khjxiaogu.aiwuxia.apps;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.GsonHelper;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.SavedData;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.history.MemoryHistory;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.session.AISession.ExtraData;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.MessageReader;
import com.khjxiaogu.webserver.loging.SimpleLogger;

/**
 * AI应用程序的抽象基类，定义了AI应用的核心功能和行为。
 * 该类负责管理消息处理链、会话状态、日志记录、系统提示构造等通用逻辑，
 * 并提供了一些工具方法（如JSON读写、历史记录处理）。
 * 子类需要实现特定的方法（如{@link #getName()}、{@link #constructSystem(ApplicationState)}等）
 * 以定义具体AI应用的行为。
 */
public abstract class AIApplication {

    /** 简单的日志记录器，用于输出日志信息（标签为"AI智能"） */
    protected SimpleLogger logger = new SimpleLogger("AI智能");

    /**
     * 消息处理器的函数式接口，用于处理用户消息并返回处理后的结果。
     * 多个处理器可以形成链式调用，每个处理器可以决定是否继续处理链。
     */
    public static interface MessageHandler {
        /**
         * 应用该处理器处理消息。
         *
         * @param state   当前AI会话状态
         * @param message 输入的消息内容
         * @return 处理后的消息内容；如果返回null，则终止处理链
         * @throws Throwable 处理过程中可能抛出的异常
         */
        public MessageContents apply(AISession state, MessageContents message) throws Throwable;
    }

    /** 消息处理器列表，按添加顺序构成处理链 */
    protected List<MessageHandler> handlers = new ArrayList<>();

    /**
     * 读取文件内容并返回字符串，同时将文件中的回车符（\r）替换为空。
     *
     * @param f 要读取的文件
     * @return 文件内容字符串
     * @throws IOException 如果读取文件失败
     */
	public static String readFile(File f) throws IOException {
		return FileUtil.readString(f).replace("\r", "");
	}
	protected MessageHandler checkTokenUse=(state,ret) ->{
		if(state.canGenerate()) {
			if(!state.isAvailable()) {

				state.sendNotice("该服务暂不可用，请联系网站管理员。");
				state.refillChatBox(ret);
				return null;
			}
			return ret;
		}
		state.sendNotice("token限额已达到！查看配额页面获取更多信息。");
		state.refillChatBox(ret);
		return null;
	};
    /**
     * 默认的“撤回与重新生成”消息处理器。
     * 该处理器会在会话处于{@link ApplicationStage#STARTED}阶段时，
     * 检查消息内容是否为“重新生成”或“撤回”，并执行相应的会话操作（如移除最后一条或两条历史条目）。
     * 对于“重新生成”，返回被移除的用户消息内容以触发重新生成；对于“撤回”，返回null。
     *
     * @see MessageHandler
     */

	public void doRegen(AISession state) {
		HistoryItem last=state.getLast();
		if(last.getRole()==Role.ASSISTANT||last.getRole()==Role.USER) {
			HistoryItem hi = state.deleteLast();
			if(hi.getRole()==Role.ASSISTANT) {
				HistoryItem userhi = state.deleteLast();
				state.minDialogRow();
				if (hi.getLastState() != null) {
					state.getState().set(hi.getLastState());
				}
				this.handleSpeech(state, userhi.getContextContent());
				return;
			}
			this.handleSpeech(state, hi.getContextContent());
			
		}
	}
	public void doRevert(AISession state) {
		if(state.getDialogRow()>state.getMinDialogRow()) {
			HistoryItem last=state.getLast();
			if(last.getRole()==Role.ASSISTANT||last.getRole()==Role.USER) {
				
				HistoryItem hi = state.deleteLast();
				if(last.getRole()==Role.ASSISTANT) {
					HistoryItem uhi=state.deleteLast();
					if(uhi.getRole()==Role.USER)
						state.refillChatBox(uhi.getContextContent());
					state.minDialogRow();
					if (hi.getLastState() != null) {
						state.getState().set(hi.getLastState());
					}
				}else if(last.getRole()==Role.USER){
					state.refillChatBox(last.getContextContent());
				}
			}
		}
	}

	   /**
     * 从JSON文件读取并构造{@link AISession.ExtraData}对象。
     *
     * @param jsonFile JSON文件
     * @return 解析得到的ExtraData对象
     * @throws JsonSyntaxException 如果JSON格式错误
     * @throws IOException         如果读取文件失败
     */
	public static AISession.ExtraData dataFromJson(File jsonFile) throws JsonSyntaxException, IOException {
		return GsonHelper.getStorageGson().fromJson(FileUtil.readString(jsonFile), AISession.ExtraData.class);
	}
    /**
     * 从JSON文件读取并构造{@link MemoryHistory}对象。
     * 注意：JSON中应包含一个名为"history"的顶级对象。
     *
     * @param jsonFile JSON文件
     * @return 解析得到的MemoryHistory对象
     * @throws JsonSyntaxException 如果JSON格式错误
     * @throws IOException         如果读取文件失败
     */
	public static MemoryHistory historyFromJson(File jsonFile) throws JsonSyntaxException, IOException {
		return GsonHelper.getStorageGson().fromJson(JsonParser.parseString(FileUtil.readString(jsonFile)).getAsJsonObject().get("history").getAsJsonObject(), MemoryHistory.class);
	}
    /**
     * 从JSON文件读取并构造{@link MemoryHistory}对象。
     * 注意：JSON中应包含一个名为"history"的顶级对象。
     *
     * @param jsonFile JSON文件
     * @return 解析得到的MemoryHistory对象
     * @throws JsonSyntaxException 如果JSON格式错误
     * @throws IOException         如果读取文件失败
     */
	public static ISaveData saveDataFromJson(File jsonFile) throws IOException {
		ExtraData data;
		MemoryHistory history;
		if(jsonFile.exists()) {
			String fstr=FileUtil.readString(jsonFile);
			JsonElement je=JsonParser.parseString(fstr);
			JsonObject jo=je.getAsJsonObject();
			data=GsonHelper.getStorageGson().fromJson(jo, AISession.ExtraData.class);
			history=GsonHelper.getStorageGson().fromJson(jo.get("history"),MemoryHistory.class);
		}else {
			jsonFile.getParentFile().mkdirs();
			data=new ExtraData();
			history=new MemoryHistory();
		}
		return new SavedData(data,history,o->{
			try {
				AIApplication.saveToJson(o, jsonFile);
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}
    /**
     * 将AI会话状态保存到JSON文件。
     * 保存的内容包括会话的ExtraData（通过{@link AISession#getData()}）和
     * 历史记录（通过{@link AISession#getHistory()}），其中历史记录会被嵌入到ExtraData的JSON对象中。
     *
     * @param aistate  AI会话对象
     * @param jsonFile 目标JSON文件
     * @throws IOException 如果写入文件失败
     */
	private static void saveToJson(ISaveData aistate, File jsonFile) throws IOException {
		JsonElement je=GsonHelper.getPrettyPrintGson().toJsonTree(aistate.getExtraData());
		je.getAsJsonObject().add("history", GsonHelper.getPrettyPrintGson().toJsonTree(aistate.getHistory()));
		FileUtil.transfer(GsonHelper.getPrettyPrintGson().toJson(je), jsonFile);
	}
    /**
     * 构建会话的历史对话摘要，格式为“角色名：显示内容”的逐行拼接。
     * 此方法通常用于生成对话的日志或传递给AI的上下文。
     *
     * @param state AI会话对象
     * @return 历史对话摘要字符串
     */
	public String constructBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getDisplayContent())
				.append("\n");
		}

		return sb.toString();

	}



	public AIApplication() {
		super();
		handlers.add(checkTokenUse);
	}
    /**
     * 处理用户的输入消息。
     * 该方法会检查会话是否正在生成输出，如果是则发送提示消息并返回；
     * 否则，它会设置生成开始标记，生成一个随机消息ID用于日志，
     * 然后依次调用注册的消息处理器处理输入消息，
     * 最后设置生成完成标记。
     *
     * @param state        当前AI会话状态
     * @param messageInput 用户输入的原始消息字符串
     */
	public void handleSpeech(AISession state,final MessageContents messageInput) {
		if(state.isGenerating()) {
			state.sendNotice("内容生成中，请稍后再试。");
			state.refillChatBox(messageInput);
			return;
		}
		state.onGenerateStart();	
		MessageContents ret=messageInput;
		int mid=(int) UUID.randomUUID().getMostSignificantBits();
		String sid=Integer.toString(mid,16);
		logger.info("message received "+sid);
		try {
			for (MessageHandler i : handlers) {
				try {
					if ((ret=i.apply(state,ret))==null)
						break;
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
		} finally {
			// 本轮的可写入上下文由应用自己用 try-with-resources 结束（写过内容则提交进历史，
			// 只写了思维链的空记录直接撤回），这里只负责收尾生成状态
			logger.info("message handled "+sid);
			state.onGenComplete();
		}
	}
    /**
     * 提供初始提示或启动信息给会话。
     * 这是一个抽象方法，由子类实现具体的初始逻辑（例如发送欢迎消息、初始化场景等）。
     *
     * @param state AI会话状态
     */
	public abstract void provideInitial(AISession state);
    /**
     * 异步处理AI输出的推理内容，把它写进应用当前持有的可写入上下文。
     * <p>
     * 上下文由应用通过 {@link AISession#beginWrite(Role)} 取得（{@link HistoryWriteContext}），
     * 在 try-with-resources 块内传入本方法：思维链写在同一条记录上，
     * 该记录在写入期间不进入历史、不参与上下文与总结清理，也不会落盘。
     * </p>
     * <p>
     * 读流与写历史都在后台线程上进行，本方法立即返回完成信号：推理流往往要等到整轮生成
     * （含工具调用）结束才会关闭，同步读取会把命令线程一直阻塞住，正文只能等推理流结束后
     * 才被处理。改为异步后，思考内容边产生边写入本轮记录（前端仍逐段收到通知），
     * 应用线程同时继续处理正文。
     * </p>
     * <p>
     * 需要读取推理内容的调用方先 {@code join()} 返回的 Future；写入上下文提交、撤回或关闭时
     * 也会自动等待它收尾，因此记录定型后不会再有后台写入。推理流已经读完（例如非流式输出）
     * 时直接同步写入并返回已完成的 Future。
     * </p>
     *
     * @param output AI输出对象（包含推理内容和最终内容）
     * @param state  AI会话状态（用于工具调用通知）
     * @param write  本条记录的可写入上下文
     * @return 后台写入任务的完成信号（读流失败时异常完成），可用于等待写入完成并感知读流失败
     * @throws IOException 如果读取推理内容时发生I/O错误（同步读取路径）
     */
	public CompletableFuture<Void> handleReasonerContent(AIOutput output,AISession state,HistoryWriteContext write) throws IOException {
		final MessageReader br=output.getReasoner();
		if(br==null)
			return null;
		// 推理流已经结束：没有可等待的内容，直接同步写入，调用方拿到的完成信号即刻可用
		if(br.isEnded()) {
			drainReasoner(br,state,write);
			return CompletableFuture.completedFuture(null);
		}
		// 后台线程按段消费推理流：内容段原样写进本轮记录，工具调用额外通知会话
		return state.beginAsyncReasonerWrite(() -> {
			try {
				return br.read();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		},write);
	}

	/**
	 * 同步读完整条推理流并写入指定上下文。
	 *
	 * @param br    推理流读取器
	 * @param state AI会话状态（用于工具调用通知）
	 * @param write 本条记录的可写入上下文
	 * @throws IOException 读取推理内容失败
	 */
	private void drainReasoner(MessageReader br,AISession state,HistoryWriteContext write) throws IOException {
		MessageContent current;
		while((current=br.read())!=null) {
			if(current instanceof ToolCallContent)
				state.onToolcall((ToolCallContent) current);
			write.appendReasoner(current);
		}
	}
    /**
     * 打印AI输出的推理内容到控制台（标准输出），并同时记录日志。
     * 该方法通常用于调试目的。
     *
     * @param output AI输出对象
     * @throws IOException 如果读取推理内容时发生I/O错误
     */
	public void printReasonerContent(AIOutput output) throws IOException {
		
		logger.info("==============Reasoner===============");
		FileUtil.printAndCollectContent(output.getReasoner());
		logger.info("==============Reasoner End===========");
	}
    /**
     * 获取指定角色在会话中的显示名称。
     * 默认实现是调用{@link Role#getName()}，子类可以覆盖以提供自定义名称。
     *
     * @param state 当前会话状态（可能用于上下文相关角色名称）
     * @param role  角色枚举
     * @return 角色的显示名称字符串
     */
	public String getRoleName(AISession state,Role role) {
		return role.getName();
	}

    /**
     * 获取AI应用的名称。
     *
     * @return 应用名称字符串
     */
    public abstract String getName();

    /**
     * 根据当前应用状态构造系统提示字符串。
     * 系统提示通常用于初始化AI模型的对话上下文。
     *
     * @param state 当前应用状态
     * @return 系统提示字符串
     */
    public abstract String constructSystem(ApplicationState state);

    /**
     * 获取会话的简要描述信息，通常用于界面显示或日志。
     *
     * @param state 当前AI会话状态
     * @return 简要描述字符串
     */
    public abstract String getBrief(AISession state);
    
    public abstract String getMemory(AISession state);

    /**
     * 准备场景数据，例如根据当前会话状态加载或构建场景信息。
     * 该方法应设计为只读操作，并支持多线程调用（即线程安全）。
     * 默认实现为空，子类可覆盖以实现具体逻辑。
     *
     * @param state 当前AI会话状态
     */
    public void prepareScene(AISession state) {
        // 默认无操作
    }
	public void onload(AISession state) {
		this.provideInitial(state);
	}
	/**
	 * 本应用是否包含朗读（语音合成）服务。
	 * <p>
	 * 用于前端决定是否展示朗读入口：与语音服务当前是否在线无关，只看应用是否实现了
	 * {@link #generateVoice(AISession, String, String)}。默认没有语音能力，需要朗读的
	 * 应用应同时覆写本方法和 {@link #generateVoice(AISession, String, String)}。
	 * </p>
	 *
	 * @return 包含朗读服务时返回 true
	 */
	public boolean supportsVoice() {
		return false;
	}
	public CompletableFuture<Boolean> generateVoice(AISession state,String orgText,String audioId){
		return CompletableFuture.completedFuture(false);
	}
}