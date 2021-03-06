/*
 * Copyright (c) 2011-2015 Pivotal Software Inc., Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.rx.action.control;

import org.reactivestreams.Publisher;
import reactor.core.Dispatcher;
import reactor.fn.Consumer;
import reactor.rx.action.Action;

/**
 * @author Stephane Maldini
 * @since 2.0
 */
public class RepeatAction<T> extends Action<T, T> {

	private final long                 numRetries;
	private long currentNumRetries = 0;
	private final Publisher<? extends T> rootPublisher;
	private       long                   pendingRequests;

	public RepeatAction(Dispatcher dispatcher, int numRetries, Publisher<? extends T> parentStream) {
		super(dispatcher);
		this.numRetries = numRetries;
		this.rootPublisher = parentStream;
	}

	@Override
	protected void requestUpstream(long capacity, boolean terminated, long elements) {
		if ((pendingRequests += elements) < 0) pendingRequests = Long.MAX_VALUE;
		super.requestUpstream(capacity, terminated, elements);
	}

	@Override
	protected void doNext(T ev) {
		if (pendingRequests > 0l && pendingRequests != Long.MAX_VALUE) {
			pendingRequests--;
		}
		broadcastNext(ev);
	}

	@Override
	public void onComplete() {
		trySyncDispatch(null, new Consumer<Void>() {
			@Override
			public void accept(Void nothing) {
				if (numRetries != -1 && ++currentNumRetries > numRetries) {
					doComplete();
					currentNumRetries = 0;
				} else {
					if (upstreamSubscription != null) {
						if (rootPublisher != null) {
							cancel();
							rootPublisher.subscribe(RepeatAction.this);
						}
						if (pendingRequests > 0) {
							upstreamSubscription.request(pendingRequests);
						}
					}
				}

			}
		});
	}
}
