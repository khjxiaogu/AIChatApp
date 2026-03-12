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
/**
 * 本地部署模型服务器
 * 私有部署通过websocket client提供模型调用服务，服务器通过websocket发布任务，并通过HTTP POST接收模型产出，该服务对于调用者完全无感。
 * 该网络模型可以方便于穿透NAT，动态IP等网络而不需要公网映射，能极大方便部署，也方便与python的调用。
 * */
public class VoiceModelLocalServer extends AbstractServiceClass {
	@HttpPath("/kh$localModelDeploy")
	public void voiceWebSocket(Request req, Response res) {
		res.suscribeWebsocketEvents(LocalVoiceModel.lhs);
		
	}
	@HttpPath("/kh$localModelDeployData")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO dataPost(@Query("reqid")String reqid,@Query("type")String type,@GetBy(DataIn.class)byte[] data) {
		LocalVoiceModel.lhs.onMessage(reqid, data);
		return new ResultDTO(200);
	}
	
	
	@Override
	public String getName() {
		return "local audio";
	}

}
