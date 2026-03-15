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

public interface AIOutput {
	public Reader getReasoner();
	public Reader getContent();
	public boolean isEnded();
	public void interrupt();
	public default String getContentText() throws IOException {
		return FileUtil.readAll(getContent());
	}
	public default String getReasonerText() throws IOException {
		return FileUtil.readAll(getReasoner());
	}
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
