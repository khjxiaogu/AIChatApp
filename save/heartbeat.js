/**
 * 心跳保活（ping/pong）公共实现，galgame / trpg / ptrpg / custom / painter 页面共用。
 *
 * 浏览器定期发送带唯一 id 的 ping，服务端必须回送同一个 id 的 pong；
 * 超过 timeout 未收到同一个 id 的 pong 即判定连接已失效（半开连接不会触发 onclose，
 * 只能靠心跳发现），此时调用 onTimeout，由页面决定如何断开与重连。
 *
 * 用法：
 *   var heartbeat = createHeartbeat({
 *       getSocket: function () { return vm.wssocket; },   // 每次发送时读取当前连接
 *       onTimeout: function () { ...摘掉监听并主动重连... }
 *   });
 *   heartbeat.start();          // 连接建立后（onopen）启动
 *   heartbeat.stop();           // 连接关闭/出错/页面销毁时停止
 *   heartbeat.onPong(param);    // 收到 {type:"pong", id:...} 时调用
 *
 * 主动断开连接时请使用 detachAndCloseSocket(ws)：它会先摘掉所有监听再关闭，
 * 避免触发页面自己的 onclose 重连逻辑。
 */
(function (global) {
	'use strict';

	/** 默认发送间隔 */
	var DEFAULT_INTERVAL = 10000;
	/** 默认等待 pong 的超时时间 */
	var DEFAULT_TIMEOUT = 10000;
	/** WebSocket.OPEN */
	var OPEN = 1;

	/**
	 * 摘掉所有监听后关闭 socket。
	 *
	 * @param {WebSocket} ws 要关闭的连接，可为空
	 */
	function detachAndCloseSocket(ws) {
		if (!ws)
			return;
		try {
			ws.onopen = null;
			ws.onmessage = null;
			ws.onerror = null;
			ws.onclose = null;
			ws.close();
		} catch (err) {
			console.log(err);
		}
	}

	/**
	 * 创建一个心跳实例。
	 *
	 * @param {Object} options 配置
	 * @param {Function} options.getSocket 返回当前连接（每次发送时调用）
	 * @param {Function} options.onTimeout 超时判定断开时回调（此时心跳已自动停止）
	 * @param {Number} [options.interval] 发送间隔，默认 10 秒
	 * @param {Number} [options.timeout] 等待 pong 的超时时间，默认 10 秒
	 * @return {Object} 心跳实例，提供 start/stop/onPong/sendPing/isRunning
	 */
	function createHeartbeat(options) {
		options = options || {};
		var getSocket = options.getSocket || function () { return null; };
		var onTimeout = options.onTimeout || function () {};
		var interval = options.interval || DEFAULT_INTERVAL;
		var timeout = options.timeout || DEFAULT_TIMEOUT;

		var timer = null;      // 发送定时器
		var seq = 0;           // 同一毫秒内的序号，保证 id 唯一
		var pending = {};      // id -> 该次 ping 的超时定时器
		var running = false;

		function sendPing() {
			var ws = getSocket();
			if (!ws || ws.readyState !== OPEN)
				return;
			var id = Date.now().toString(36) + '-' + (++seq);
			pending[id] = setTimeout(function () { onPingTimeout(id); }, timeout);
			try {
				ws.send(JSON.stringify({ type: 'ping', id: id }));
			} catch (err) {
				console.warn('[heartbeat] 发送ping失败', err);
				clearTimeout(pending[id]);
				delete pending[id];
			}
		}

		function onPingTimeout(id) {
			// 已经被 pong 清掉的超时不再处理
			if (!Object.prototype.hasOwnProperty.call(pending, id))
				return;
			delete pending[id];
			console.warn('[heartbeat] ' + timeout + 'ms 内未收到 pong(' + id + ')，判定连接已断开');
			stop();
			onTimeout();
		}

		function start() {
			stop();
			seq = 0;
			pending = {};
			running = true;
			timer = setInterval(sendPing, interval);
			sendPing();   // 建连后立即发一次，尽早发现无效连接
		}

		function stop() {
			running = false;
			if (timer) {
				clearInterval(timer);
				timer = null;
			}
			for (var id in pending) {
				if (Object.prototype.hasOwnProperty.call(pending, id))
					clearTimeout(pending[id]);
			}
			pending = {};
		}

		/**
		 * 收到 pong：只清除 id 完全一致的那次 ping。
		 *
		 * @param {Object} msg 服务端消息，需包含 id
		 */
		function onPong(msg) {
			if (!msg || msg.id == null)
				return;
			// 服务端按原文回送，这里统一按字符串比较，避免数字/字符串类型不一致
			var key = String(msg.id);
			if (Object.prototype.hasOwnProperty.call(pending, key)) {
				clearTimeout(pending[key]);
				delete pending[key];
			}
		}

		return {
			start: start,
			stop: stop,
			onPong: onPong,
			sendPing: sendPing,
			isRunning: function () { return running; }
		};
	}

	global.createHeartbeat = createHeartbeat;
	global.detachAndCloseSocket = detachAndCloseSocket;
})(window);
