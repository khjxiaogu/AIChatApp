package com.khjxiaogu.aiwuxia;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.respscheme.Choice.Message;
import com.khjxiaogu.aiwuxia.state.AIOutput;
import com.khjxiaogu.aiwuxia.state.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public abstract class ModelApi {
	public static class ModelEffort{
		public enum ReasonerEffort{
			NONE,MIN,MID,HIGH;
		}
		final boolean isReasoner;
		final ReasonerEffort reasonerEffort;
		
		public ModelEffort(boolean isReasoner, ReasonerEffort reasonerEffort) {
			super();
			this.isReasoner = isReasoner;
			this.reasonerEffort = reasonerEffort;
		}

		public static final ModelEffort NORMAL=new ModelEffort(false,ReasonerEffort.NONE);
		public static final ModelEffort REASONER_NONE=new ModelEffort(true,ReasonerEffort.NONE);
		public static final ModelEffort REASONER_MIN=new ModelEffort(true,ReasonerEffort.MIN);
		public static final ModelEffort REASONER_MID=new ModelEffort(true,ReasonerEffort.MID);
		public static final ModelEffort REASONER_HIGH=new ModelEffort(true,ReasonerEffort.HIGH);
	}
	public static ExecutorService exc=Executors.newCachedThreadPool();
	protected SimpleLogger logger;
	public ModelApi(String name) {
		logger=new SimpleLogger(name);
	}
	public abstract String getModel(ModelEffort effort) ;
	public abstract String getToken(String model) ;
	public abstract String getUrl(String model) ;
	public abstract String getHost(String model) ;
	protected Gson gs = new Gson();
	public RespScheme sendAIRequest(JsonObject req,ModelEffort effort) throws IOException {
		String model=getModel(effort);
		req.addProperty("model", model);
		setModelProperty(req);
		String tosend = gs.toJson(req);
		
		logger.info("trigger generation");
		JsonObject retjs = modifier(HttpRequestBuilder.create(getHost(model)).url(getUrl(model))
				.header("Content-Type", "application/json"),model)
				
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
	public HttpRequestBuilder modifier(HttpRequestBuilder in,String model) {
		return in.header("Authorization", "Bearer "+getToken(model));
	};
	public abstract void setModelProperty(JsonObject model);
	public AIOutput sendAIStreamedRequest(JsonObject req, ModelEffort effort, Consumer<Usage> gainUsage) throws IOException {
		
		String model=getModel(effort);
		req.addProperty("model", model);
		req.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		setModelProperty(req);
		req.addProperty("stream", true);
		logger.info("trigger generation");
		String tosend = gs.toJson(req);
		StreamedAIOutput readable=new StreamedAIOutput();
		Usage usage=new Usage();
		modifier(HttpRequestBuilder.create(getHost(model)).url(getUrl(model))
				.header("Content-Type", "application/json"),model)
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
						usage.set(scheme.usage);
					
				});
	
	
		return readable;
	}

}
