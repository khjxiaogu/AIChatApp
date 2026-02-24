var maxPictureSize=600;
var pageQueries={};
var searchStr = location.toString();
searchStr = searchStr.substr(searchStr.indexOf("?")+1);
searchStr=searchStr.replace("#","");
var searchs = searchStr.split("&");
for(var i=0;i<searchs.length;i++){
	var rq = searchs[i].split("=");
	pageQueries[rq[0]]=rq[1];
}
function autoScroll(){messages.scrollTop=messages.scrollHeight}
function ChatClient(url){
	var _this=this;
	var msgs=new Vue({
	  el: '#messages',
	  data: {
		list: [
		],
		user:"你",
		opened:false
	  },
	  computed: {
		computedList: function () {
		  return this.list;
		}
	  },
	  methods: {
		beforeEnter: function (el) {
		 // el.style.opacity = 0
		  //el.style.translateX="-100%"
		},
		setOpened(b){
			opened=b;
			if(b){
				input.style.display="";
			}else{
				input.style.display="none";
			}
		},
		enter: function (el, done) {
			var vm = this;
			if(el.dataset.index==vm.list.length-1){input.focus();setTimeout(autoScroll,200)};
			/*Velocity(
			  el,
			  { opacity: 1, translateX: "0" },
			  { duration: 600,complete: function(){done();} }
			)*/
		},
		leave: function (el, done) {
			/*Velocity(
			  el,
			  { opacity: 0 ,translateX: "200%"},
			  { complete: done }
			)*/
		}
	  }
	});

	function pushmsg(id,name,msg){
		if(name!="")
			msgs.list.push({title: name,msg:msg,id:id});
		else
			msgs.list.push({msg:msg,id:id});
	}
	function appendMsg(id,msg){
		for(var i=0;i<msgs.list.length;i++){
			if(msgs.list[i].id==id){
				msgs.list[i].msg+=msg;
				break;
			}
		}
	}
	function remMsg(id){
		for(var i=0;i<msgs.list.length;i++){
			if(msgs.list[i].id==id){
				msgs.list.splice(i,1);
				break;
			}
		}
	}
	function error(msg){
		alert(msg);
	}
	function broadcast(msg){
		alert(msg);
	}
	this.sendMessage=function(msg){
		//var m=msg.replace(/\n/gi,"[b]");
		speak(msg);
		return false;
	}
	this.sendMessageHttp=function(msg){
		var m=msg.replace(/\n/gi,"[b]");
		messagepool.push(m);
		return false;
	}
	var wssocket;
	var prepared=0;
	var eventpool=[];
	var messagepool=[];
	var chatId;
	var appId;
	this.clear=function(){msgs.list=[];};
	this.sendRegister=function (value){
		if(prepared)
			error("服务器正在响应，请稍候");
		if(value.length<=0)
			error("用户名不能为空！");
		else{
			eventpool.push({msg:JSON.stringify({username:value}),method:"REGISTER"});
			prepared=1;
		}
		return false;
	}
	this.sendHead=function(){
		var xhr=new XMLHttpRequest();
		xhr.open("POST",url,true);
		xhr.setRequestHeader("Content-Type",headinput.files[0].type);
		xhr.setRequestHeader("method","SETHEAD");
		headselect.style.display="none";
		headpreview.visibility="hidden";
		xhr.onloadend=function(){if(xhr.status==200){alert("上传成功，头像可能不会立即更新，请清理浏览器缓存或者稍等片刻。");setTimeout(function(){var sl=userhead.src;userhead.src="";userhead.src=sl;},240000)}else alert("上传失败："+xhr.status);};
		CompressHead(headinput.files[0],function(data){xhr.send(data);});
		headinput.value="";
	}
	function buf2hex(buffer) { // buffer is an ArrayBuffer
		return Array.prototype.map.call(new Uint8Array(buffer), x => ('00' + x.toString(16)).slice(-2)).join('');
	}
	var usernames;
	this.sendLogin=function(value){
		if(prepared)
			error("服务器正在响应，请稍候");
		usernames=value;
		eventpool.push({msg:JSON.stringify({username:value}),method:"LOGIN"});
		prepared=1;
		return false;
	}
	function send(msg){

	}
	
	function speak(sent){
		if(sent=="")return;
		//messagepool.push(sent);
		wssocket.send(sent);
	}
	function open(done){
		wssocket=new WebSocket("ws://"+document.location.host+url+"/chatsocket?app="+appId+"&chatid="+chatId+"&userId="+pageQueries.userId);
		wssocket.onopen=function(){prepared=1;
			window.onbeforeunload = function() {
			    wssocket.onclose = function () {}; // disable onclose handler first
			    wssocket.close();
			};
			wssocket.onerror=function(ev){
				wssocket.onerror=function(){};
				console.info(ev);
				setTimeout(open,500);
				msgs.setOpened(false);
				
			}
			msgs.setOpened(true);
			if(done)
				done();
		};
		wssocket.onclose=function(ev){var rev=ev?ev:event;msgs.setOpened(false);if(rev.reason!="")alert(rev.reason);}
		wssocket.onerror=function(ev){console.info(ev);msgs.setOpened(false);alert("连接聊天服务器失败！");};
		wssocket.onmessage = function(ev) {
			var rev=ev?ev:event;
			var param=eval("("+rev.data+")");
			if(param.message!=undefined)
				pushmsg(param.id,param.title,param.message.replace(/\n/gi,"<br />"));
			if(param.messages!=undefined)
				for(var i=0;i<param.messages.length;i++){
					pushmsg(param.messages[i].id,param.messages[i].title,param.messages[i].message.replace(/\n/gi,"<br />"));
				}
			if(param.delta!=undefined){
				appendMsg(param.id,param.delta.replace(/\n/gi,"<br />"));
			}
			if(param.remove!=undefined){
				remMsg(param.remove);
			}
		};
	}
	this.open=function(app,id,done){
		if(wssocket){
			wssocket.onclose=function(){};
			wssocket.close();
			}
		chatId=id;
		appId=app;
		msgs.list.splice(0,msgs.list.length);
		open(done);
	};
	var _this=this;
	var chats=new Vue({
	  el: '#chats',
	  data: {
		list: [
		],
		applist:[],
		selectedApp:""
	  },
	  computed: {
		computedList: function () {
		  return this.list;
		}
	  },
	  methods: {
		changeChat:function(id){
			_this.open(appId,id);
		},
		createApp:function(){
			var sel=this.selectedApp;
			var __this=this;
			loadJSON("/aichat/createid",function(e){
				_this.open(sel,e,__this.reloadChats);
				console.info(sel);
				console.info(e);
				//__this.reloadChats();
			});
		},
		reloadChats:function(){
			loadJSON("/aichat/chatlist?uid="+pageQueries.userId,e=>this.list=eval(e));
		}
	  }
	});
	chats.reloadChats()
	setTimeout(chats.reloadChats,5000);
	loadJSON("/aichat/applist",e=>chats.applist=eval(e));
	return this;
}
function CompressHead(file,done){
    if(file.type.match(/image.*/)) {
        readAsDataURL(file,function (readerEvent) {
				var image = new Image();
				image.onload = function (imageEvent) {
					// Resize the image
					var canvas = document.createElement('canvas');
					canvas.width = 48;
					canvas.height = 48;
					canvas.getContext('2d').drawImage(image, 0, 0,48,48);
					done(dataURLToBlob(canvas.toDataURL('image/png')));
				}
				image.src = readerEvent.target.result;
			});
    }
}
function CompressImage(file,done){
    if(file.type.match(/image.*/)) {
        readAsDataURL(file,function (readerEvent) {
				var image = new Image();
				image.onload = function (imageEvent) {
					// Resize the image
					var canvas = document.createElement('canvas');
					var width=image.width;
					var height=image.height;
					var max=maxPictureSize;
					var maxside;
					if(image.width>image.height){
						if(image.width>max){
							height=image.height/image.width*max;
							width=max;
						}
						maxside=width;
					}else{
						if(image.height>max){
							width=image.width/image.height*max;
							height=max;
						}
						maxside=height;
					}
					var q=0.9*max/maxside;
					canvas.width = width;
					canvas.height = height;
					canvas.getContext('2d').drawImage(image, 0, 0,width,height);
					done(canvas.toDataURL('image/jpeg',q>1?1:q));
				}
				image.src = readerEvent.target.result;
			});
    }
}
function readAsDataURL(file,done){
	var reader = new FileReader();
	reader.onload = done;
	reader.readAsDataURL(file);
}
function dataURLToBlob(dataURL) {
    var BASE64_MARKER = ';base64,';
    if (dataURL.indexOf(BASE64_MARKER) == -1) {
        var parts = dataURL.split(',');
        var contentType = parts[0].split(':')[1];
        var raw = parts[1];

        return new Blob([raw], {type: contentType});
    }

    var parts = dataURL.split(BASE64_MARKER);
    var contentType = parts[0].split(':')[1];
    var raw = window.atob(parts[1]);
    var rawLength = raw.length;

    var uInt8Array = new Uint8Array(rawLength);

    for (var i = 0; i < rawLength; ++i) {
        uInt8Array[i] = raw.charCodeAt(i);
    }

    return new Blob([uInt8Array], {type: contentType});
}