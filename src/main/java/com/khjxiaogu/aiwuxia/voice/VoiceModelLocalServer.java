package com.khjxiaogu.aiwuxia.voice;

import com.khjxiaogu.webserver.annotations.Adapter;
import com.khjxiaogu.webserver.annotations.GetBy;
import com.khjxiaogu.webserver.annotations.HttpMethod;
import com.khjxiaogu.webserver.annotations.HttpPath;
import com.khjxiaogu.webserver.annotations.Query;
import com.khjxiaogu.webserver.web.AbstractServiceClass;
import com.khjxiaogu.webserver.web.lowlayer.Request;
import com.khjxiaogu.webserver.web.lowlayer.Response;
import com.khjxiaogu.webserver.wrappers.ResultDTO;
import com.khjxiaogu.webserver.wrappers.inadapters.DataIn;

public class VoiceModelLocalServer extends AbstractServiceClass {
	@HttpPath("/kh$localModelDeploy")
	public void voiceWebSocket(Request req, Response res) {
		res.suscribeWebsocketEvents(LocalVoiceModel.lhs);
		
	}
	@HttpPath("/kh$localModelDeployData")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO voicePost(@Query("reqid")String reqid,@Query("type")String type,@GetBy(DataIn.class)byte[] data) {
		LocalVoiceModel.lhs.onMessage(reqid, data);
		return new ResultDTO(200);
	}
	
	
	@Override
	public String getName() {
		return "local audio";
	}

}
