package com.khjxiaogu.aiwuxia.state;

import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.utils.BlockingReader;

import java.io.Reader;
import java.io.StringReader;

public interface AIOutput {
	public Reader getReasoner();
	public Reader getContent();
	public static class StreamedAIOutput implements AIOutput{
		public final BlockingReader reasoner;
		public final BlockingReader content;
		public StreamedAIOutput() {
			reasoner=new BlockingReader();
			content=new BlockingReader();
		}
		public void putReasoner(String object) {
			reasoner.putCh(object);
		}
		public void putContent(String object) {
			reasoner.setEnded();
			content.putCh(object);
		}
		public void endContent() {
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
	}
	public static class FilledAIOutput implements AIOutput{
		public final Reader reasoner;
		public final Reader content;
		public FilledAIOutput(RespScheme resp) {
			reasoner=new StringReader(resp.choices.get(0).message.reasoning_content);
			content=new StringReader(resp.choices.get(0).message.content);
		}
		@Override
		public Reader getReasoner() {
			return reasoner;
		}
		@Override
		public Reader getContent() {
			return content;
		}
	}
}
