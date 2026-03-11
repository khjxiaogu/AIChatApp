package com.khjxiaogu.aiwuxia.respscheme;

import java.util.List;

import com.khjxiaogu.aiwuxia.llm.AIOutput;

public class RespScheme {
	public List<Choice> choices;
	public Usage usage;
	public AIOutput toOutput(){
		return new AIOutput.FilledAIOutput(choices.get(0).message.reasoning_content,choices.get(0).message.content,usage);

	}
}
