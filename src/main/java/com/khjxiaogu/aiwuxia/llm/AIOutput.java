package com.khjxiaogu.aiwuxia.llm;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.utils.BlockingReader;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

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
    public Reader getReasoner();

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
    public default String getReasonerText() throws IOException {
        return FileUtil.readAll(getReasoner());
    }

    /**
     * 添加一个监听器，用于接收输出完成后的总使用情况信息。
     * 使用情况通常包括token消耗数量、API调用成本等指标，具体由{@link Usage}类定义。
     *
     * @param listener 一个{@link Consumer}，用于处理{@link Usage}对象。当有新的使用数据可用时会被调用。
     */
    public void addUsageListener(Consumer<Usage> listener);
	public static class StreamedAIOutput implements AIOutput{
		public final BlockingReader reasoner;
		public final BlockingReader content;
		private Usage usage;
		private boolean interrupted;
		private List<Consumer<Usage>> usageListener=new ArrayList<>();
		public StreamedAIOutput() {
			reasoner=new BlockingReader();
			content=new BlockingReader();
		}
		public void putReasoner(String object) {
			reasoner.putCh(object);
		}
		public void putContent(String object) {
			if(!reasoner.isEnded())
			reasoner.setEnded();
			content.putCh(object);
		}
		public void endContent() {
			reasoner.setEnded();
			content.setEnded();
		}
		public boolean isEnded() {
			return content.isEnded();
		}
		@Override
		public Reader getReasoner() {
			return reasoner;
		}
		@Override
		public Reader getContent() {
			return content;
		}
		public void setUsage(Usage usage) {
			this.usage=usage;
			synchronized(usageListener) {
				usageListener.forEach(t->t.accept(usage));
			}
		}
		public void addUsageListener(Consumer<Usage> listener) {
			if(this.usage!=null)
				listener.accept(usage);
			else
				synchronized(usageListener) {
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
		public final Reader reasoner;
		public final Reader content;
		private final Usage usage;
		public FilledAIOutput(String reasoning,String content,Usage usage) {
			this.reasoner=new StringReader(reasoning);
			this.content=new StringReader(content);
			this.usage=usage;
		}
		@Override
		public Reader getReasoner() {
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
		public void addUsageListener(Consumer<Usage> listener) {
			listener.accept(usage);
		}
		@Override
		public void interrupt() {
			// noop since generation is already finished.
		}
	}
}
