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
package reactor.reactivestreams.tck;

import org.junit.Before;
import org.junit.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.BeforeTest;
import reactor.Environment;
import reactor.core.DispatcherSupplier;
import reactor.core.support.Assert;
import reactor.fn.tuple.Tuple1;
import reactor.rx.Stream;
import reactor.rx.Streams;
import reactor.rx.action.combination.CombineAction;
import reactor.rx.stream.Broadcaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Stephane Maldini
 */
@org.testng.annotations.Test
public class StreamIdentityProcessorTests extends org.reactivestreams.tck.IdentityProcessorVerification<Integer> {


	private final Map<Thread, AtomicLong> counters = new ConcurrentHashMap<>();

	private DispatcherSupplier dispatchers;
	private Environment        env;

	public StreamIdentityProcessorTests() {
		super(new TestEnvironment(2500, true), 3500);
	}

	@BeforeTest
	@Before
	public void startEnv() {
		env = Environment.initializeIfEmpty().assignErrorJournal();
		dispatchers = Environment.newCachedDispatchers(2, "test-partition");
	}

	@Override
	public CombineAction<Integer, Integer> createIdentityProcessor(int bufferSize) {

		Stream<String> otherStream = Streams.just("test", "test2", "test3");
		//Dispatcher dispatcherZip = env.getCachedDispatcher();

		final CombineAction<Integer, Integer> integerIntegerCombineAction = Streams.<Integer>broadcast(env)
				.keepAlive(false)
				.capacity(bufferSize)
				.partition(2)
				.flatMap(stream -> stream
								.dispatchOn(env, dispatchers.get())
								.observe(i -> {
									AtomicLong counter = counters.get(Thread.currentThread());
									if (counter == null) {
										counter = new AtomicLong();
										counters.put(Thread.currentThread(), counter);
									}
									counter.incrementAndGet();
								})
								.scan(0, (prev, next) -> next)
								.filter(integer -> integer >= 0)
								.window(1)
								.flatMap(s ->
												s.reduce(0, (acc, i) -> -i)
								)
								.sample(1)
								.map(integer -> -integer)
								.buffer(1024, 200, TimeUnit.MILLISECONDS)
								.<Integer>split()
								.flatMap(i ->
												Streams.zip(Streams.just(i), otherStream, Tuple1::getT1)
										//.log(stream.key() + ":zip")
								)
				)
				.when(Throwable.class, Throwable::printStackTrace)
				.combine();

		/*Streams.period(env.getTimer(), 2, 1)
				.takeWhile(i -> integerIntegerCombineAction.isPublishing())
				.consume(i -> System.out.println(integerIntegerCombineAction.debug()) );*/

		return integerIntegerCombineAction;
	}

	@Override
	public Publisher<Integer> createHelperPublisher(long elements) {
		if (elements < 100 && elements > 0) {
			List<Integer> list = new ArrayList<Integer>();
			for (int i = 1; i <= elements; i++) {
				list.add(i);
			}

			return Streams
					.from(list)
					.log("iterable-publisher")
					.filter(integer -> true)
					.map(integer -> integer);

		} else {
			final Random random = new Random();

			return Streams
					.generate(random::nextInt)
					.log("random-publisher")
					.map(Math::abs);
		}
	}

	@Override
	public Publisher<Integer> createErrorStatePublisher() {
		return Streams.fail(new Exception("oops")).cast(Integer.class);
	}

	@Override
	@org.testng.annotations.Test
	public void spec212_mustNotCallOnSubscribeMoreThanOnceBasedOnObjectEquality_specViolation() throws Throwable {
	}

	@Test
	public void testIdentityProcessor() throws InterruptedException {
		final int elements = 10000;
		CountDownLatch latch = new CountDownLatch(elements);

		CombineAction<Integer, Integer> processor = createIdentityProcessor(1000);

		Broadcaster<Integer> stream = Streams.broadcast(env);

		stream.subscribe(processor);
		System.out.println(processor.debug());
		Thread.sleep(2000);

		processor.subscribe(new Subscriber<Integer>() {
			@Override
			public void onSubscribe(Subscription s) {
				s.request(elements);
			}

			@Override
			public void onNext(Integer integer) {
				latch.countDown();
			}

			@Override
			public void onError(Throwable t) {
				t.printStackTrace();
			}

			@Override
			public void onComplete() {
				System.out.println("completed!");
				latch.countDown();
			}
		});


		for (int i = 0; i < elements; i++) {
			stream.onNext(i);
		}
		//stream.broadcastComplete();

		latch.await(8, TimeUnit.SECONDS);
		System.out.println(stream.debug());

		System.out.println(counters);
		long count = latch.getCount();
		Assert.state(latch.getCount() == 0, "Count > 0 : " + count + " , Running on " + Environment.PROCESSORS + " CPU");

	}
}
