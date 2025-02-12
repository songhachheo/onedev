package io.onedev.server.web.websocket;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.onedev.server.event.CommitIndexed;
import io.onedev.server.event.pubsub.Listen;

@Singleton
public class CommitIndexedBroadcaster {

	private final WebSocketManager webSocketManager;
	
	@Inject
	public CommitIndexedBroadcaster(WebSocketManager webSocketManager) {
		this.webSocketManager = webSocketManager;
	}
	
	@Listen
	public void on(CommitIndexed event) {
		webSocketManager.notifyObservableChange(CommitIndexed.getWebSocketObservable(event.getCommitId().name()));
	}

}