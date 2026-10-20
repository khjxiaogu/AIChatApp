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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.providers.grok.GrokUsage;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.utils.MCPTools;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.UsageTracker;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * AI会话类，管理与特定用户的对话历史、推理过程和状态。 该类提供了添加消息、管理历史条目、处理推理内容以及控制会话状态的功能。
 */
public class AISession implements ISaveData,AutoCloseable{

	/**
	 * 对话会话的额外状态数据，封装了与 UI 状态、对话轮次、使用统计等相关的信息。
	 */
	public static class ExtraData {
		/** 应用程序的当前状态（如界面状态） */
		private ApplicationState state = new ApplicationState();
		/** 额外的自定义数据映射，用于存储会话相关的任意键值对 */
		public Map<String, String> extraData = new HashMap<>();
		/** 当前对话的轮次/回合数，从 0 开始递增，表示已进行的对话轮次 */
		private int row;
		/** 支持撤回的最早对话轮次，即用户允许撤回的对话起始轮次，早于此轮次的条目不可撤回 */
		private int minRow = 0;
		/** 当前对话阶段（如初始化、进行中、结束等） */
		private ApplicationStage stage = ApplicationStage.INITIALIZE;
		/** 会话的使用量统计（如 token 消耗、费用等） */
		private UsageTracker usage = UsageTracker.create();
		/** 标记是否为音频会话，影响处理逻辑（如语音识别、语音合成） */
		public boolean isAudioSession = false;
		
		public String modelHint="";
		
		public String voiceModel="";
	}

	/** 会话的额外状态数据，包含轮次、阶段、使用统计等 */
	protected ISaveData data;
	/** 关联的 AI 应用程序实例，用于处理具体的 AI 逻辑（如生成回复） */
	protected AIApplication aiapp;
	/**
	 * 当前打开的历史写入上下文。
	 * <p>
	 * 应用通过 {@link #beginWrite(Role)} / {@link #continueWrite(Role)} 取得可写入上下文，
	 * 并用 try-with-resources 完成写入，会话本身不再提供任何"追加到历史记录"的方法。
	 * 生成过程中其它组件（例如工具）可以先用 {@link #getCurrentWrite()} 取得该上下文再写入。
	 * </p>
	 */
	protected SessionWriteContext currentWrite;
	/** 当前会话关联的用户标识 */
	public final String user;
	/**
	 * 全部会话共享的命令任务线程池。
	 * <p>
	 * 每个会话把一个排空器任务交给本池（见 {@link SerialExecutor}），池里实际运行的线程数因此
	 * 由"正在执行命令任务的会话数"决定：命令任务绝大部分时间都在等模型 I/O，按会话各起一条常驻
	 * 线程会随会话数线性占用线程，排队中的任务也不再各占一条线程；空闲线程 60 秒后回收。
	 * </p>
	 * <p>
	 * 池本身不保证任何顺序，每个会话自己的先后由 {@link SerialExecutor} 的任务队列保证。
	 * 线程名按当前执行的会话设置，便于排查问题。
	 * </p>
	 */
	private static final ExecutorService COMMAND_EXEC = Executors.newCachedThreadPool(new ThreadFactory() {
		private final AtomicInteger id = new AtomicInteger();

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "ai-session-" + id.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});
	/**
	 * 后台读流写入线程池：思维链读取会长时间阻塞在模型的推理流上（工具调用时是整轮工具循环），
	 * 因此用独立的非固定大小线程池而不是共享的公共池，线程全部为守护线程，不影响进程退出。
	 *
	 * @see #beginAsyncReasonerWrite(Supplier, HistoryWriteContext)
	 */
	private static final ExecutorService REASONER_WRITE_EXEC = Executors.newCachedThreadPool(new ThreadFactory() {
		private final AtomicInteger id = new AtomicInteger();

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "reasoner-write-" + id.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});
	/**
	 * 本会话已经启动、尚未收尾的后台思维链写入。
	 * <p>
	 * 写入上下文结束时只会等待自己启动的那些任务；本集合用于兜底：某次写入被丢弃
	 * （例如应用又开启了新的写入上下文）而任务仍在跑时，新的写入开始前也会把它们等完，
	 * 避免后台任务往已经结束的记录上写。
	 * </p>
	 */
	protected final Set<CompletableFuture<Void>> pendingReasonerWrites = ConcurrentHashMap.newKeySet();
	/**
	 * 会话的命令任务执行器：任务本身仍串行、按提交顺序执行（语义与原来的单线程执行器一致），
	 * 但线程取自共享缓存池 {@link #COMMAND_EXEC}，不再每个会话常驻一条线程。
	 */
	protected final ExecutorService commandExec;
	/** 标记当前是否正在生成 AI 输出（用于避免重复触发或状态冲突） */
	volatile transient boolean isGenerating;

	/** 本轮生成开始时的累计配额用量快照，用于算出本轮增量 */
	private double turnUsageBase;
	/** 本轮新增的配额用量（累计用量相比开始时的增加值），生成结束时更新 */
	private volatile double lastTurnTokens;
	
	volatile transient AIOutput currentOutput;

	/**
	 * 本会话持有的工具调用管理器（工具注册表 + 调用次数/权限/许可规则），创建后长期复用。
	 * <p>
	 * 管理器本身可变：工具直接注册进去，规则在构造时由 {@link #applyToolCallRules(ToolCallManager)}
	 * 写入（基类留空，子类覆盖），之后随时可直接修改管理器，不需要任何失效或重建。
	 * {@code AIRequest} 直接取用本管理器，不再自行维护工具列表。
	 * </p>
	 */
	private final ToolCallManager toolCallManager = new ToolCallManager();

	/**
	 * 注册一个工具，由本会话的工具调用管理器统一处理。
	 * <p>
	 * 直接写入管理器，不触发 {@link #applyToolCallRules(ToolCallManager)}：规则在第一次取用
	 * {@link #getToolCallManager()} 时才写入，因此会话构造期间注册工具不会提前调用子类重写的规则方法。
	 * </p>
	 *
	 * @param tool 工具定义
	 * @return 本会话，便于链式注册
	 */
	public AISession registerTool(ToolData tool) {
		toolCallManager.registerTool(tool);
		return this;
	}

	/**
	 * 批量注册工具，由本会话的工具调用管理器统一处理。
	 *
	 * @param tools 工具定义集合
	 * @return 本会话，便于链式注册
	 */
	public AISession registerTools(Collection<ToolData> tools) {
		toolCallManager.registerTools(tools);
		return this;
	}

	/**
	 * 注册一个 MCP 模块提供的全部工具。
	 *
	 * @param tools MCP 模块的工具集合
	 * @return 本会话，便于链式注册
	 */
	public AISession registerTools(MCPTools tools) {
		if (tools != null) {
			registerTools(tools.getTools());
		}
		return this;
	}

	/**
	 * 获取本会话的工具调用管理器，{@code AIRequest} 直接取用。
	 * <p>
	 * 管理器由已注册的工具（{@link #registerTool}/{@link #registerTools}）与构造时
	 * {@link #applyToolCallRules(ToolCallManager)} 写入的规则（调用次数、权限监听器、许可）组成；
	 * 管理器可变，需要调整时直接改它即可。
	 * </p>
	 *
	 * @return 工具调用管理器，无工具时其工具表为空
	 */
	public ToolCallManager getToolCallManager() {
		return toolCallManager;
	}

	/**
	 * 把本会话的工具调用规则写入管理器，基类留空，供子类覆盖。
	 * <p>
	 * 基类不写任何内容：管理器保持 {@link ToolCallManager} 自身的默认规则
	 * （{@link ToolCallManager#DEFAULT_MAX_ROUNDS} 轮、不限总次数、全部允许、自动许可）。
	 * 子类按需重写本方法配置规则，例如：
	 * </p>
	 * <pre>{@code
	 * @Override
	 * protected void applyToolCallRules(ToolCallManager manager) {
	 *     manager.setMaxRounds(20)
	 *            .setUnlimitedTools(UNLIMITED_TOOLS)
	 *            .addPermissionListener(ToolCallPermission.requireApproval("高危工具需确认", "shell"));
	 * }
	 * }</pre>
	 * <p>
	 * 本方法在会话构造时调用一次，因此实现只能使用常量或静态数据，不要读取子类实例字段
	 * （此时子类字段尚未初始化）；构造完成后要再调整规则，直接改
	 * {@link #getToolCallManager()} 返回的管理器。
	 * </p>
	 *
	 * @param manager 本会话的工具调用管理器
	 */
	protected void applyToolCallRules(ToolCallManager manager) {
	}

	/**
	 * 获取一次工具调用的用户友好显示。
	 * <p>
	 * 生成过程中已将显示算好并缓存在 {@link ToolCallContent} 上，直接返回；
	 * 历史从存储重新加载后显示信息不存在（不持久化），此时按当前注册的工具重算，
	 * 保证加载后工具调用简述与具体内容同样可见。
	 * </p>
	 *
	 * @param content 工具调用内容
	 * @param index   工具调用下标
	 * @return 用户友好显示，永不为 null
	 */
	public ToolCallDisplay getToolCallDisplay(ToolCallContent content, int index) {
		if (content == null) {
			return ToolCallDisplay.EMPTY;
		}
		if (content.hasDisplays()) {
			return content.getDisplay(index);
		}
		List<ToolCall> calls = content.getToolCalls();
		if (index < 0 || index >= calls.size()) {
			return ToolCallDisplay.EMPTY;
		}
		return getToolCallManager().displayFor(calls.get(index));
	}

	/**
	 * 获取一次工具结果的用户友好显示。
	 * <p>
	 * 生成过程中已算好的显示直接返回；历史重新加载后按当前注册的工具重算，
	 * 需要提供对应的原始调用（可按 {@link ToolContent#getToolId()} 与
	 * {@link ToolCallContent#getToolCalls()} 配对）。
	 * </p>
	 *
	 * @param call   对应的原始工具调用，可为 null
	 * @param result 工具结果内容
	 * @return 用户友好显示，永不为 null
	 */
	public ToolCallDisplay getToolResultDisplay(ToolCall call, ToolContent result) {
		if (result == null) {
			return ToolCallDisplay.EMPTY;
		}
		if (result.hasDisplay()) {
			return result.getDisplay();
		}
		if (call == null) {
			return result.getDisplay();
		}
		ToolCallManager manager = getToolCallManager();
		return manager.resultDisplayFor(call, manager.displayFor(call), result.getResult());
	}

	public AIOutput getCurrentOutput() {
		return currentOutput;
	}

	public void setCurrentOutput(AIOutput currentOutput) {
		this.currentOutput = currentOutput;
	}

	/**
	 * 构造一个 AI 会话实例。
	 *
	 * @param user     用户标识
	 * @param historym 历史条目容器（不能为 null）
	 * @param data     额外状态数据（不能为 null）
	 * @param aiapp    关联的 AI 应用实例
	 */
	public AISession(String user, ISaveData data, AIApplication aiapp) {
		super();
		this.data = data;
		this.user = user;
		this.aiapp = aiapp;
		// 工具调用规则只在构造时写入一次；管理器可变，之后需要调整直接改管理器
		applyToolCallRules(toolCallManager);
		// 工具不再持有会话：需要把图片等内容追加到当前消息时，通过管理器交回给会话
		toolCallManager.setContentSink(content -> {
			HistoryWriteContext write = getCurrentWrite();
			if (write != null) {
				write.append(content, false);
			}
		});
		commandExec = new SerialExecutor(COMMAND_EXEC, this);
	}

	/**
	 * 把命令任务排队执行：每个会话一个 FIFO 任务队列，同一时刻最多有一个"排空器"在跑。
	 * <p>
	 * 与"每个会话一条单线程执行器"的语义等价：任务既不重叠，也不乱序；区别是线程来自共享池，
	 * 且<b>排队中的任务不占线程</b>——提交时只把任务放进队列，队列已有排空器时直接返回，
	 * 不会像单线程执行器那样每个排队任务都占住一条线程。
	 * </p>
	 * <p>
	 * 会话内串行是必需的：多处代码（历史写入、重生成、初始提示）都依赖"上一个命令任务已经结束"
	 * 或"生成任务跑完才轮到自己"，并行执行会破坏状态一致性或直接死锁。
	 * </p>
	 */
	private static class SerialExecutor extends AbstractExecutorService {
		/** 共享线程池，真正提供执行线程 */
		private final ExecutorService pool;
		/** 所属会话，仅用于日志里的线程名 */
		private final AISession session;
		/** 待执行任务队列（FIFO）：保证按提交顺序执行 */
		private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
		/** 是否有排空器正在跑（正在执行任务或即将执行） */
		private boolean running;
		/** 正在执行的任务数 */
		private int active;
		/** 是否已经停止接受新任务 */
		private volatile boolean closed;
		/** 结束状态锁：保护上面几个字段，并用于唤醒等待结束的线程 */
		private final ReentrantLock stateLock = new ReentrantLock();
		/** 队列排空且没有任务在执行时唤醒等待者（等待结束的语义） */
		private final Condition idle = stateLock.newCondition();

		SerialExecutor(ExecutorService pool, AISession session) {
			this.pool = pool;
			this.session = session;
		}

		@Override
		public void execute(Runnable command) {
			if (closed)
				throw new RejectedExecutionException("会话已释放，无法再提交命令任务");
			boolean startWorker;
			stateLock.lock();
			try {
				queue.addLast(command);
				// 已有排空器在跑：它会把新任务一并执行，本次提交不必再占线程
				startWorker = !running;
				if (startWorker)
					running = true;
			} finally {
				stateLock.unlock();
			}
			if (startWorker)
				startWorker();
		}

		/**
		 * 为本次提交启动排空器。
		 * <p>
		 * 共享池不可用（池已关闭，通常只发生在进程退出阶段，且它的线程都是守护线程）时不再重试：
		 * 只把队列清掉并交回失败，既不把长命令任务退到调用线程上跑（会占住 Netty 线程），
		 * 也不留一个永远没人排空的队列。
		 * </p>
		 */
		private void startWorker() {
			try {
				pool.execute(this::drain);
			} catch (RejectedExecutionException e) {
				stateLock.lock();
				try {
					queue.clear();
					finishDrain();
				} finally {
					stateLock.unlock();
				}
				throw e;
			}
		}

		/**
		 * 依次执行队列中的任务，直到队列为空。
		 * <p>
		 * 执行期间不持有结束状态锁（任务里会做历史写入、模型调用等耗时操作），因此任务不会重叠；
		 * 每次取任务前都重新判断队列，所以任务执行期间入队的任务会自动接在后面执行，不另起排空器。
		 * </p>
		 * <p>
		 * 已经提交的任务一定会执行完：{@link #shutdown()} 之后队列仍然继续排空，只有
		 * {@link #shutdownNow()} 会清掉队列、让未开始的任务不再执行。
		 * </p>
		 */
		private void drain() {
			while (true) {
				Runnable task;
				stateLock.lock();
				try {
					task = queue.pollFirst();
					if (task == null) {
						finishDrain();
						return;
					}
					active++;
				} finally {
					stateLock.unlock();
				}
				try {
					runCommandTask(session, task);
				} finally {
					stateLock.lock();
					try {
						active--;
					} finally {
						stateLock.unlock();
					}
				}
			}
		}

		/**
		 * 排空器收工：清掉运行标记，并在已经关闭时唤醒等待结束的线程。
		 * <p>
		 * 只在持有 {@link #stateLock} 时调用。
		 * </p>
		 */
		private void finishDrain() {
			running = false;
			if (closed)
				idle.signalAll();
		}

		@Override
		public void shutdown() {
			// 只停止接受本会话的新任务：共享池由进程持有，不能按会话关闭
			stateLock.lock();
			try {
				closed = true;
				// 等待者等的是"队列空且没有任务在执行"，队列里还有任务时先不唤醒，等排空器收工
				if (queue.isEmpty() && active == 0)
					idle.signalAll();
			} finally {
				stateLock.unlock();
			}
		}

		@Override
		public List<Runnable> shutdownNow() {
			stateLock.lock();
			try {
				closed = true;
				List<Runnable> pending = new ArrayList<>(queue);
				queue.clear();
				// 已经不在队列里的任务仍会执行完，因此只有没有任务在执行时才算结束
				if (active == 0)
					idle.signalAll();
				return pending;
			} finally {
				stateLock.unlock();
			}
		}

		@Override
		public boolean isShutdown() {
			return closed;
		}

		@Override
		public boolean isTerminated() {
			stateLock.lock();
			try {
				return isIdle();
			} finally {
				stateLock.unlock();
			}
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			long remainNanos = unit.toNanos(timeout);
			stateLock.lock();
			try {
				while (!isIdle()) {
					if (remainNanos <= 0L) {
						// 超时返回 false；但已关闭且刚好排空时仍按结束处理
						return isIdle();
					}
					remainNanos = idle.awaitNanos(remainNanos);
				}
				return true;
			} finally {
				stateLock.unlock();
			}
		}

		/**
		 * 是否已经结束：已关闭、没有排空器在跑，也没有任务正在执行。
		 * <p>
		 * 只在持有 {@link #stateLock} 时调用。
		 * </p>
		 */
		private boolean isIdle() {
			return closed && !running && active == 0;
		}
	}

	/**
	 * 会话命令任务的统一包装：把线程名标记成当前调用的会话，便于在日志中区分并发会话。
	 * <p>
	 * 共享池里的线程名在创建时生成、之后被反复复用，因此对执行期间的线程名按实际所属会话重设一次。
	 * </p>
	 *
	 * @param session 当前执行命令任务的会话，可为 null（此时只执行任务，不设置线程名）
	 * @param command 命令任务
	 */
	private static void runCommandTask(AISession session, Runnable command) {
		if (session == null) {
			command.run();
			return;
		}
		Thread thread = Thread.currentThread();
		String oldName = thread.getName();
		thread.setName("ai-session-" + session.user);
		try {
			command.run();
		} finally {
			thread.setName(oldName);
		}
	}

	/**
	 * 获取历史条目容器。
	 *
	 * @return {@link HistoryHolder} 实例
	 */
	public HistoryHolder getHistory() {
		return data.getHistory();
	}

	/**
	 * 开始一次历史写入，返回可写入上下文。
	 * <p>
	 * 每次调用都会新起一条记录：History 在创建时即分配好标识符，但记录尚未进入历史容器，
	 * 因此不参与上下文构建、总结清理与历史分页，也不会落盘。应用用 try-with-resources
	 * 持有该上下文写入本轮内容，块结束时由 {@link HistoryWriteContext#close()} 结算：
	 * 写过内容就提交进历史，只写了思维链的空记录直接撤回。
	 * </p>
	 * <p>
	 * 会话同一时间只保留一个打开的写入上下文：调用本方法时若上一个还没结束，会先按内容结算它。
	 * </p>
	 *
	 * @param role 本条记录的角色
	 * @return 可写入上下文
	 */
	public HistoryWriteContext beginWrite(Role role) {
		closeCurrentWrite();
		currentWrite = new SessionWriteContext(this, getHistory().beginPending(role, true), true);
		setUpdated();
		return currentWrite;
	}

	/**
	 * 续写最后一条同角色记录，返回可写入上下文。
	 * <p>
	 * 用于同一条记录由多次到达的输入拼成的场景（例如绘画前端先发文本、再发图片）：
	 * 最后一条历史条目与角色相同且仍是有效上下文时就地续写它，否则等价于
	 * {@link #beginWrite(Role)} 新起一条记录。续写的记录本来就在历史里，
	 * 因此 {@link HistoryWriteContext#close()} 不会提交或撤回任何内容。
	 * </p>
	 *
	 * @param role 要写入的角色
	 * @return 可写入上下文
	 */
	public HistoryWriteContext continueWrite(Role role) {
		HistoryItem last = getHistory().peekLast();
		// 已删除的条目不算数：撤回后容器里可能只剩软删除条目，peekLast() 此时返回 null
		if (last != null && last.getRole() == role && last.isValidContext()) {
			closeCurrentWrite();
			currentWrite = new SessionWriteContext(this, last, false);
			return currentWrite;
		}
		return beginWrite(role);
	}

	/**
	 * 获取当前打开的写入上下文。
	 * <p>
	 * 生成过程中需要往本轮记录里追加内容的组件（例如执行工具时发送图片）通过本方法取得上下文，
	 * 再调用其写入方法；没有正在进行的写入时返回 null。
	 * </p>
	 *
	 * @return 当前写入上下文，没有时返回 null
	 */
	public HistoryWriteContext getCurrentWrite() {
		return currentWrite;
	}

	/**
	 * 在后台线程上逐段消费一条推理流，并异步写入指定的历史写入上下文。
	 * <p>
	 * 思维链读取会阻塞在模型输出上（工具调用时整轮工具循环结束才会关闭推理流），
	 * 因此这里把读流与写历史都丢到后台线程，调用方拿到 {@link CompletableFuture} 后可以立刻
	 * 继续处理回复正文，思考内容则一边产生一边进入本轮记录
	 * （{@link #onReasonerAppend(MessageContent)} 照常逐段回调）。
	 * </p>
	 * <p>
	 * 内容段由 {@code source} 原样提供（纯文本、工具调用等），本方法不再拆装内容：
	 * 每个内容段都通过 {@link HistoryWriteContext#appendReasonerIfOpen(MessageContent)} 写入，
	 * 工具调用额外通知会话（{@link #onToolcall(ToolCallContent)}）。本轮记录已经提交或撤回时
	 * 后台任务自行结束，不会写到别的记录上；会话同时记录该任务，写入上下文结束（提交/撤回/关闭）
	 * 与开启下一次写入前都会等待它收尾，读流失败体现在返回的 Future 上（异常完成），
	 * 调用方 {@code join()} 即可拿到。
	 * </p>
	 *
	 * @param source 依次提供推理内容段的来源，返回 null 表示流已结束
	 * @param write  推理内容要写入的可写入上下文
	 * @return 后台写入任务的完成信号
	 */
	public CompletableFuture<Void> beginAsyncReasonerWrite(final Supplier<MessageContent> source,
			final HistoryWriteContext write) {
		// 用 CompletableFuture 管理任务本身（完成/失败信号），执行仍交给本会话专用的思维链线程池：
		// runAsync 不传线程池时会落到 ForkJoinPool.commonPool，而这里每轮生成都要长时间阻塞在
		// 模型输出上（工具调用时是整轮循环），不该占用进程内共享的公共池，线程名也便于排查问题
		final CompletableFuture<Void> done = CompletableFuture.runAsync(() -> {
			MessageContent current;
			while ((current = source.get()) != null) {
				if (!write.appendReasonerIfOpen(current))
					return;
				if (current instanceof ToolCallContent)
					onToolcall((ToolCallContent) current);
			}
		}, REASONER_WRITE_EXEC);
		// 记录该任务：写入上下文结束与开启下一次写入前都要等它收尾；收尾后自行注销
		pendingReasonerWrites.add(done);
		done.whenComplete((value, error) -> onReasonerWriteResolved(done));
		return done;
	}

	/**
	 * 等待本会话全部后台思维链写入收尾（读流成功或失败都算收尾）。
	 * <p>
	 * 提交、撤回或关闭本轮写入上下文前调用，确保记录定型之后不会再有后台写入。
	 * 读流失败按"内容可能不完整"处理，不影响本轮记录定型，因此不在此抛出异常；
	 * 需要感知失败的调用方自行 {@code join()} 它拿到的那个 Future。
	 * </p>
	 */
	void awaitReasonerWrites() {
		for (CompletableFuture<Void> writeTask : pendingReasonerWrites) {
			try {
				writeTask.join();
			} catch (CompletionException e) {
				// 读流失败：内容可能不完整，但不影响本轮记录定型，由持有该任务的调用方负责上报
			} catch (CancellationException e) {
				// 任务被取消：同上
			}
			// 后台任务收尾时会自行注销，这里只在它没来得及注销时兜底清理
			pendingReasonerWrites.remove(writeTask);
		}
	}

	/**
	 * 结束当前打开的写入上下文（按其内容提交或撤回），没有打开时不做任何处理。
	 * <p>
	 * 换到下一次写入之前先把还在跑的后台思维链写入等完，避免它们挂到新的记录上。
	 * </p>
	 */
	private void closeCurrentWrite() {
		SessionWriteContext write = currentWrite;
		if (write != null && !write.isResolved()) {
			write.close();
		}
		awaitReasonerWrites();
	}

	/**
	 * 后台思维链写入收尾时由写入上下文回调，把它从会话的待收尾集合中移除。
	 *
	 * @param writeTask 已经收尾的后台写入任务
	 */
	void onReasonerWriteResolved(CompletableFuture<Void> writeTask) {
		if (writeTask != null) {
			pendingReasonerWrites.remove(writeTask);
		}
	}

	/**
	 * 写入上下文结束（提交、撤回或关闭）时的回调。
	 *
	 * @param write 结束的上下文
	 */
	void onWriteResolved(SessionWriteContext write) {
		if (currentWrite == write) {
			currentWrite = null;
		}
		setUpdated();
	}

	/**
	 * 思维链写入钩子。
	 * <p>
	 * 可写入上下文每次追加思维链时调用。需要把思考过程实时呈现给前端的会话可以重写本方法
	 * （例如按自己的帧格式下发 thinking 帧），默认不做任何处理。
	 * </p>
	 *
	 * @param reasoner 本次追加的思维链内容
	 */
	protected void onReasonerAppend(MessageContent reasoner) {

	}

	/**
	 * 根据标识符移除指定的历史条目；标识符指向本轮正在写入的记录时改为撤回该次写入。
	 *
	 * @param num 要移除的条目的标识符（参见 {@link HistoryItem#getIdentifier()}）
	 */
	public void removeOf(int num) {
		SessionWriteContext write = currentWrite;
		if (write != null && write.getIdentifier() == num) {
			write.rollback();
			return;
		}
		getHistory().removeOf(num);
		delMessage(num);
	}

	/**
	 * 移除并返回最后一个历史条目；本轮正在写入的记录尚未提交时改为撤回该次写入。
	 *
	 * @return 被移除的最后一个 {@link HistoryItem} 对象
	 */
	public HistoryItem removeLast() {
		SessionWriteContext write = currentWrite;
		if (write != null) {
			HistoryItem removed = write.item();
			write.rollback();
			return removed;
		}
		HistoryItem removed = getHistory().removeLast();
		delMessage(removed.getIdentifier());
		return removed;
	}
	/**
	 * 撤回并返回最后一个历史条目；本轮正在写入的记录尚未提交时改为撤回该次写入。
	 *
	 * @return 被移除的最后一个 {@link HistoryItem} 对象
	 */
	public HistoryItem deleteLast() {
		SessionWriteContext write = currentWrite;
		if (write != null) {
			HistoryItem removed = write.item();
			write.rollback();
			return removed;
		}
		HistoryItem removed = getHistory().deleteLast();
		delMessage(removed.getIdentifier());
		return removed;
	}
	/**
	 * 获取最后一个历史条目（不移除）。
	 * <p>
	 * 本轮存在打开的写入上下文时返回它正在写入的记录：该记录可能尚未提交进历史，
	 * 因此不会随历史分页下发，也不会落盘。
	 * </p>
	 *
	 * @return 最后一个 {@link HistoryItem}，如果历史为空则返回 null
	 */
	public HistoryItem getLast() {
		SessionWriteContext write = currentWrite;
		if (write != null)
			return write.item();
		return getHistory().peekLast();
	}

	/**
	 * 当新消息添加后调用，用于通知外部（如 UI）更新。 子类可重写此方法以实现自定义处理。
	 *
	 * @param id      消息的标识符
	 * @param role    消息的角色
	 * @param message 消息的显示内容
	 */
	public void postMessage(int id, Role role, MessageContents message) {
		setUpdated();
	}

	/**
	 * 当现有消息被追加内容时调用，用于通知外部更新。
	 *
	 * @param id    消息标识符
	 * @param title 追加的内容
	 */
	public void appendMessage(int id, String title) {
		setUpdated();
	}

	/**
	 * 当现有消息被追加多模态内容时调用，用于通知外部更新。
	 * <p>
	 * 默认实现将内容转为文本后委托给 {@link #appendMessage(int, String)}。
	 * 子类可重写此方法以支持图片、视频等非文本内容的传输。
	 * </p>
	 *
	 * @param id      消息标识符
	 * @param content 要追加的多模态内容
	 */
	public void appendMessage(int id, MessageContent content) {
		appendMessage(id, content.toText());
	}

	/**
	 * 当消息被删除时调用，用于通知外部。
	 *
	 * @param id 被删除消息的标识符
	 */
	public void delMessage(int id) {
		setUpdated();
	}

	/** 标记会话是否已更新（用于外部检测变化） */
	boolean isUpdated;

	/**
	 * 检查并清除更新标记。通常由外部轮询调用以判断会话是否有变化。
	 *
	 * @return 如果会话在上次检查后有更新则返回 true，否则 false
	 */
	public boolean checkAndUnsetUpdated() {
		boolean res = isUpdated;
		isUpdated = false;
		return res;
	}

	/**
	 * 设置更新标记为 true，表示会话内容发生了变化。
	 */
	public void setUpdated() {
		isUpdated = true;
	}
	/**
	 * @param text  
	 */
	public void refillChatBox(MessageContents text) {
		
	}

	/**
	 * 当生成完成时调用，结算本轮配额用量，清除生成标记并设置更新标记。
	 */
	public void onGenComplete() {
		// 本轮配额用量 = 累计用量相比开始时快照的增加值；按 0.01 取整，避免浮点误差带出尾巴
		double delta = getExtraData().usage.getTotalEquivantTokens() - turnUsageBase;
		lastTurnTokens = delta > 0 ? Math.round(delta * 100d) / 100d : 0;
		// 把本轮配额记到本轮回复（最后一条助手记录）上：历史分页/重新加载后仍能显示
		HistoryItem reply = getLast();
		if (reply != null && reply.getRole() == Role.ASSISTANT)
			setTokenCost(reply, lastTurnTokens);
		setUpdated();
		isGenerating = false;
		this.currentOutput=null;
		data.flush();
	}
	public boolean isAvailable() {
		return true;
	}

	/**
	 * 发送场景内容（可能用于传递特定类型的数据到 UI 或其他组件）。
	 *
	 * @param type  内容类型
	 * @param value 内容值
	 */
	public void sendSceneContent(String type, String value) {
		// 默认空实现，子类可覆盖
	}

	/**
	 * 累加使用量统计。
	 *
	 * @param usage 要累加的 {@link GrokUsage} 对象
	 */
	public void addUsage(UsageIntf<?> usage) {
		addStatUsage(usage);
	}
	public void addStatUsage(UsageIntf<?> usage) {
		getExtraData().usage.add(usage);
	}
	public boolean canGenerate() {
		return true;
	}
	/**
	 * 获取使用量统计的字符串表示。
	 *
	 * @return 使用量信息字符串
	 */
	public String getUsage() {
		return getExtraData().usage.toString();
	}

	/**
	 * 计算并获取使用量对应的价格字符串。
	 *
	 * @return 价格字符串
	 */
	public String getPrice() {
		return getExtraData().usage.calculatePrice();
	}
	public float getTokens() {
		return getExtraData().usage.getTotalTokenPrice();
	}

	/**
	 * 获取累计配额用量（全部用量对象的 {@code getEquivantTokens()} 合计）。
	 *
	 * @return 累计配额用量
	 */
	public double getTotalEquivantTokens() {
		return getExtraData().usage.getTotalEquivantTokens();
	}

	/**
	 * 获取本轮对话新增的配额用量。
	 * <p>
	 * 即本轮生成期间 {@code getEquivantTokens()} 总和的增加值：{@link #onGenerateStart()} 记下当时的累计值，
	 * {@link #onGenComplete()} 用新的累计值相减得到。没有详情，只给一个数；还没生成过时为 0。
	 * </p>
	 *
	 * @return 本轮新增配额用量
	 */
	public double getLastTurnTokens() {
		return lastTurnTokens;
	}
	/**
	 * 获取当前对话阶段。
	 *
	 * @return {@link ApplicationStage} 枚举值
	 */
	public ApplicationStage getStage() {
		return getExtraData().stage;
	}

	/**
	 * 获取当前会话应当使用的模型标识。
	 * <p>
	 * 基类直接返回会话中保存的模型提示（可能为空，表示未指定）；具体会话实现可重写本方法，
	 * 在未指定时回退到应用级默认模型。AI请求统一通过本方法取模型标识。
	 * </p>
	 *
	 * @return 模型标识（按 "/" 分层的完整路径），空字符串表示未指定
	 */
	public String getModelHint() {
		String hint = getExtraData().modelHint;
		return hint == null ? "" : hint;
	}

	Map<String,Consumer<String>> pendingInputs=new ConcurrentHashMap<>();
	/**
	 * @param prompt  
	 */
	public void requestUserInput(String input,String prompt,Consumer<String> consumer) {
		pendingInputs.put(input, consumer);
	}
	/**
	 * @param msg  
	 */
	public void sendNotice(String msg) {
		
	}
	public void handleUserInput(String input,String value) {
		Consumer<String> csm=pendingInputs.remove(input);
		if(csm!=null)
			csm.accept(value);
	}
	/**
	 * 获取额外数据映射。
	 *
	 * @return 键值对映射，可用于存储自定义数据
	 */
	public Map<String, String> getExtra() {
		return getExtraData().extraData;
	}
	public Map<String, String> getHistoryState() {
		return getExtraData().extraData;
	}
	public String getUserName() {
		return getExtraData().extraData.get("name");
	}
	public void setUserName(String name) {
		getExtraData().extraData.put("name", name);
	}
	public String getLastSummary() {
		return getHistoryState().get("lastSummary");
	}
	public void setLastSummary(String lastSummary) {
		getHistoryState().put("lastSummary",lastSummary);
	}
	public String getBackground() {
		return getExtraData().extraData.get("back");
	}
	public void setBackground(String back) {
		getExtraData().extraData.put("back",back);
	}
	public String getCharacter() {
		return getExtraData().extraData.get("chara");
	}
	public void setCharacter(String chara) {
		getExtraData().extraData.put("chara",chara);
	}
	/**
	 * 设置当前对话阶段。
	 *
	 * @param stage 新的阶段
	 */
	public void setStage(ApplicationStage stage) {
		getExtraData().stage = stage;
	}

	/**
	 * 增加对话轮次计数（row + 1）。 每次完成一轮完整的对话交互后调用。
	 */
	public void addDialogRow() {
		getExtraData().row++;
	}

	/**
	 * 减少对话轮次计数（row - 1）。 当撤回一轮对话时调用。
	 */
	public void minDialogRow() {
		getExtraData().row--;
	}

	/**
	 * 获取支持撤回的最早对话轮次。
	 *
	 * @return 最小轮次数
	 */
	public int getMinDialogRow() {
		return getExtraData().minRow;
	}

	/**
	 * 设置支持撤回的最早对话轮次。
	 *
	 * @param minRow 新的最小轮次数
	 */
	public void setMinDialogRow(int minRow) {
		getExtraData().minRow = minRow;
	}

	/**
	 * 获取当前对话轮次。
	 *
	 * @return 当前轮次数
	 */
	public int getDialogRow() {
		return getExtraData().row;
	}

	/**
	 * 获取应用状态对象（可能包含 UI 或会话状态快照）。
	 *
	 * @return {@link ApplicationState} 实例
	 */
	public ApplicationState getState() {
		return getExtraData().state;
	}

	/**
	 * 标记生成过程开始，设置 {@link #isGenerating} 为 true，并记下本轮开始时的累计配额用量。
	 * <p>
	 * 记下的值用于在 {@link #onGenComplete()} 时算出本轮新增的配额用量
	 * （{@link #getLastTurnTokens()}）。
	 * </p>
	 */
	public void onGenerateStart() {
		// 先取快照再置生成标记：本轮后续产生的用量都算在这一轮里
		turnUsageBase = getExtraData().usage.getTotalEquivantTokens();
		lastTurnTokens = 0;
		isGenerating = true;
	}

	/**
	 * 批量发布历史条目更新通知。
	 *
	 * @param items 更新的条目列表
	 */
	public void postMessages(List<HistoryItem> items) {
		setUpdated();
	}

	/**
	 * 批量减少对话轮次计数。
	 *
	 * @param rows 要减少的轮次数
	 */
	public void minDialogRows(int rows) {
		getExtraData().row -= rows;
	}
	public void setDialogRows(int rows) {
		getExtraData().row = rows;
	}

	/**
	 * 判断是否为音频会话。
	 *
	 * @return 如果是音频会话则返回 true
	 */
	public boolean isAudioSession() {
		return getExtraData().isAudioSession;
	}

	/**
	 * 获取当前的推理内容。
	 * <p>
	 * 本轮存在打开的写入上下文时返回它已经写入的思维链；否则如果最后一个条目是助手且包含推理内容，
	 * 则返回该推理内容；都没有时返回 null。
	 * </p>
	 *
	 * @return 推理内容，可能为 null
	 */
	public MessageContents getReasonerContent() {
		SessionWriteContext write = currentWrite;
		if (write != null)
			return write.getReasoningContent();
		HistoryItem last = getHistory().peekLast();
		if (last!=null&&last.getRole() == Role.ASSISTANT) {
			MessageContents reasoner=last.getReasoningContent();
			return reasoner==null?null:reasoner;
		}
		return null;
	}

	/**
	 * 通知音频完成，关联音频 ID 到指定的历史条目。
	 *
	 * @param id      历史条目标识符
	 * @param audioId 音频标识符
	 */
	public void postAudioComplete(int id, String audioId) {
		// 默认空实现，子类可覆盖
	}
	/**
	 * 提供初始提示（例如在会话开始时由 AI 应用触发）。 通过 {@link #commandExec} 提交任务以确保顺序执行。
	 */
	public Future<?> provideInitialHint() {
		return getCommandExec().submit(() -> getAiapp().provideInitial(this));
	}

	/**
	 * 处理用户输入的消息。 通过 {@link #commandExec} 提交任务以确保顺序执行。
	 *
	 * @param message 语音识别后的文本消息
	 */
	public Future<?> handleUserSpeech(MessageContents message) {
		return getCommandExec().submit(() -> getAiapp().handleSpeech(this, message));
	}

	/**
	 * 获取关联的 AI 应用实例。
	 *
	 * @return {@link AIApplication} 对象
	 */
	public AIApplication getAiapp() {
		return aiapp;
	}

	/**
	 * 获取命令执行器（单线程），用于提交需要串行执行的任务。
	 *
	 * @return {@link ExecutorService} 实例
	 */
	public ExecutorService getCommandExec() {
		return commandExec;
	}

	/**
	 * 检查当前是否正在生成输出。
	 *
	 * @return 如果正在生成则返回 true
	 */
	public boolean isGenerating() {
		return isGenerating;
	}

	/**
	 * 获取会话的额外数据对象。
	 *
	 * @return {@link ExtraData} 实例
	 */
	public ExtraData getExtraData() {
		return data.getExtraData();
	}

	public Future<?> onLoad() {
		return getCommandExec().submit(()->this.aiapp.onload(this));
	}
	public String constructBackLog() {
		return aiapp.constructBackLog(this);
	}
	public String getBrief() {
		return aiapp.getBrief(this);
	}

	public String getMemory() {
		return aiapp.getMemory(this);
	}
	
	public String getRoleName(Role role) {
		return aiapp.getRoleName(this, role);
	}
	/**
	 * 工具调用通知钩子。
	 * <p>
	 * 当 {@code ToolCallManager} 决定发起一批工具调用并写入推理流时被调用，
	 * 仅用于对外通知/展示，工具的执行与计数由 {@code ToolCallManager} 负责。
	 * </p>
	 *
	 * @param content 本批工具调用内容
	 */
	public void onToolcall(ToolCallContent content) {
		
	}
	public void setValidContext(HistoryItem hi, boolean sendable) {
		getHistory().setValidContext(hi, sendable);
	}
	public void setDeleted(HistoryItem hi, boolean sendable) {
		getHistory().setDeleted(hi, sendable);
	}
	public void setAudioId(HistoryItem hi, String audioId) {
		getHistory().setAudioId(hi, audioId);
	}
	public void setSendReasoner(HistoryItem hi, boolean sendReasoner) {
		getHistory().setSendReasoner(hi, sendReasoner);
	}
	public void setTokenLength(HistoryItem hi, long l) {
		getHistory().setTokenLength(hi, l);
	}
	/**
	 * 设置指定历史条目所属对话轮次消耗的配额（token）。
	 *
	 * @param hi        历史条目
	 * @param tokenCost 本轮配额用量
	 */
	public void setTokenCost(HistoryItem hi, double tokenCost) {
		getHistory().setTokenCost(hi, tokenCost);
	}


	@Override
	public void flush() {
		data.flush();
	}

	public boolean onModifyAttempt() {
		return true;
	}

	public void onModifyComplete() {
		flush();
	}

	@Override
	public void close(){
		// 会话结束前把后台思维链写入等完，避免任务在会话释放后继续写历史
		awaitReasonerWrites();
		commandExec.shutdown();
	}
}