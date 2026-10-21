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
package com.khjxiaogu.aiwuxia.llm;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.utils.BlockingReader;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.MessageReader;

/**
 * 表示AI模型输出的接口，提供对模型生成的推理内容和最终内容的读取访问。
 * 该接口支持流式输出，可以通过{@link #getReasoner()}和{@link #getContent()}获取相应的输入流，
 * 并能够监控输出是否结束、中断输出以及监听使用情况（如token消耗）。
 */
public interface AIOutput {

    /**
     * 获取推理内容的读取器。
     * 推理内容通常指模型在生成最终答案之前的思考过程、中间步骤或内部推理文本。
     *
     * @return 用于读取推理内容的{@link Reader}对象
     */
    public MessageReader getReasoner();

    /**
     * 获取最终输出内容的读取器。
     * 这是模型生成的最终回答或结果，通常直接呈现给用户。
     *
     * @return 用于读取最终内容的{@link Reader}对象
     */
    public Reader getContent();

    /**
     * 判断输出是否已经结束。
     * 当所有内容（包括推理和最终内容）都已生成完毕时，返回true。
     *
     * @return 如果输出已结束则返回true，否则返回false
     */
    public boolean isEnded();

    /**
     * 中断当前的输出生成过程。
     * 调用此方法可以提前终止模型的输出，后续的读取操作可能会立即结束或抛出异常。
     */
    public void interrupt();

    /**
     * 将最终输出内容作为字符串读取。
     * 这是一个默认方法，内部通过{@link #getContent()}获取Reader并利用{@link FileUtil#readAll(Reader)}读取所有内容。
     *
     * @return 最终输出内容的完整字符串
     * @throws IOException 如果读取过程中发生I/O错误
     */
    public default String getContentText() throws IOException {
        return FileUtil.readAll(getContent());
    }

    /**
     * 将推理内容作为字符串读取。
     * 这是一个默认方法，内部通过{@link #getReasoner()}获取Reader并利用{@link FileUtil#readAll(Reader)}读取所有内容。
     *
     * @return 推理内容的完整字符串
     * @throws IOException 如果读取过程中发生I/O错误
     */
    public default MessageContents getReasonerText() throws IOException {
    	MessageReader reasoner=getReasoner();
    	MutableMessageContents contents=new MutableMessageContents();
    	while(!reasoner.isEnded()) {
    		MessageContent current=reasoner.read();
    		if(current==null)
    			break;
    		contents.add(current);
    	}
        return contents;
    }

    /**
     * 添加一个用量监听器。
     * <p>
     * 监听器会先收到输出对象中已经累计的全部用量，之后每当 {@link #addUsage(UsageIntf)}
     * 追加新用量时继续收到通知。会话通常在这里接 {@code state::addUsage} 完成计费与统计。
     * </p>
     *
     * @param listener 用量接收方，null 忽略
     */
    public void addUsageListener(Consumer<UsageIntf<?>> listener);

    /**
     * 追加一条用量。
     * <p>
     * 模型提供者在流结束时写入模型自身的用量；工具在执行过程中也可以写入自己产生的用量
     * （图片上传、搜索、额外模型调用等），会话通过 {@link #addUsageListener} 统一记账。
     * </p>
     *
     * @param usage 本次产生的用量，null 忽略
     */
    public void addUsage(UsageIntf<?> usage);

    /**
     * 当前累计的全部用量（按加入顺序）。
     *
     * @return 只读快照，永不为 null
     */
    public List<UsageIntf<?>> getUsages();

	public static class StreamedAIOutput implements AIOutput{
		/**
		 * 后台流式请求的任务体，允许抛出任意异常（包括受检异常）。
		 * <p>
		 * 与 {@link StreamedAIOutput#runGuarded(ExecutorService, StreamTask)} 配套使用，
		 * 由后者保证任务体的失败不会丢失、输出流一定会结束。
		 * </p>
		 */
		@FunctionalInterface
		public interface StreamTask {
			void run() throws Exception;
		}
		public final MessageReader reasoner;
		public final BlockingReader content;
		private final List<UsageIntf<?>> usages = new ArrayList<>();
		private final List<Consumer<UsageIntf<?>>> usageListener = new ArrayList<>();
		private boolean interrupted;
		public StreamedAIOutput() {
			reasoner=new MessageReader();
			content=new BlockingReader();
		}
		public void putReasoner(MessageContent object) {
			reasoner.putCh(object);
		}
		public void putContent(String object) {
			content.putCh(object);
		}
		public void endContent() {
			reasoner.setEnded();
			content.setEnded();
		}
		public void exception(IOException ex) {
			reasoner.putException(ex);
			content.putException(ex);
		}
		/**
		 * 以流的形式报告失败：非 {@link IOException} 会被包装成 IOException 保留原始原因。
		 * <p>
		 * 读流任务里出现 RuntimeException（例如 NPE、协议解析错误）时同样要让读取方感知到，
		 * 否则读取方会一直阻塞在 {@code read()} 上。
		 * </p>
		 *
		 * @param ex 失败原因
		 */
		public void exception(Throwable ex) {
			exception(ex instanceof IOException ? (IOException) ex : new IOException(String.valueOf(ex), ex));
		}
		/**
		 * 在后台线程执行一次流式请求任务，并保证这条输出流一定会结束。
		 * <p>
		 * 任务体无论是正常返回、抛 IOException 还是抛任何 RuntimeException/Error，
		 * 本方法都会把失败写入输出流（{@link #exception(Throwable)}）并在最后调用
		 * {@link #endContent()}。这一点是硬性要求：同步等待模型输出的调用方
		 * （例如工具内部的 {@code FileUtil.printAndCollectContent}）会一直阻塞在
		 * {@code BlockingReader.read()} 上，只有结束流才能唤醒它们；否则一次断流、
		 * 一次读超时或一次解析异常就会让整轮对话永久卡死。
		 * </p>
		 *
		 * @param executor 执行线程池
		 * @param task     任务体（通常内部调用 {@code readSSE}）
		 * @return 任务完成信号；任务体失败已转成输出流上的异常，因此该信号不会异常完成
		 */
		public CompletableFuture<Void> runGuarded(ExecutorService executor, StreamTask task) {
			return CompletableFuture.runAsync(() -> {
				try {
					task.run();
				} catch (Throwable t) {
					exception(t);
				} finally {
					endContent();
				}
			}, executor);
		}
		public boolean isEnded() {
			return content.isEnded();
		}
		@Override
		public MessageReader getReasoner() {
			return reasoner;
		}
		@Override
		public Reader getContent() {
			return content;
		}
		@Override
		public void addUsage(UsageIntf<?> usage) {
			if (usage == null) {
				return;
			}
			synchronized (usages) {
				usages.add(usage);
				for (Consumer<UsageIntf<?>> listener : usageListener) {
					listener.accept(usage);
				}
			}
		}
		@Override
		public List<UsageIntf<?>> getUsages() {
			synchronized (usages) {
				return new ArrayList<>(usages);
			}
		}
		@Override
		public void addUsageListener(Consumer<UsageIntf<?>> listener) {
			if (listener == null) {
				return;
			}
			synchronized (usages) {
				for (UsageIntf<?> usage : usages) {
					listener.accept(usage);
				}
				usageListener.add(listener);
			}
		}
		public boolean isInterrupted() {
			return interrupted;
		}
		@Override
		public void interrupt() {
			interrupted=true;
		}
		
	}
	public static class FilledAIOutput implements AIOutput{
		public final MessageReader reasoner;
		public final Reader content;
		private final List<UsageIntf<?>> usages = new ArrayList<>();
		private final List<Consumer<UsageIntf<?>>> usageListener = new ArrayList<>();
		public FilledAIOutput(MessageContents reasoning,String content,UsageIntf<?> usage) {
			this.reasoner=new MessageReader(reasoning);
			this.content=new StringReader(content);
			if (usage != null) {
				this.usages.add(usage);
			}
		}
		@Override
		public MessageReader getReasoner() {
			return reasoner;
		}
		@Override
		public Reader getContent() {
			return content;
		}
		public boolean isEnded() {
			return true;
		}
		@Override
		public void addUsage(UsageIntf<?> usage) {
			if (usage == null) {
				return;
			}
			synchronized (usages) {
				usages.add(usage);
				for (Consumer<UsageIntf<?>> listener : usageListener) {
					listener.accept(usage);
				}
			}
		}
		@Override
		public List<UsageIntf<?>> getUsages() {
			synchronized (usages) {
				return new ArrayList<>(usages);
			}
		}
		@Override
		public void addUsageListener(Consumer<UsageIntf<?>> listener) {
			if (listener == null) {
				return;
			}
			synchronized (usages) {
				for (UsageIntf<?> usage : usages) {
					listener.accept(usage);
				}
				usageListener.add(listener);
			}
		}
		@Override
		public void interrupt() {
			// noop since generation is already finished.
		}
	}
}
