/*******************************************************************************
 * Copyright (c) 2026 Siemens AG.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.laeubi.copilot.cli.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jface.util.PropertyChangeEvent;

class TransportTest {
	private static final ILog LOG = ILog.class.cast(java.lang.reflect.Proxy.newProxyInstance(
			ILog.class.getClassLoader(), new Class<?>[] { ILog.class }, (proxy, method, args) -> null));
	private final ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon().factory());

	@AfterEach
	void cleanupWorkers() {
		workers.shutdownNow();
	}

	private <T> Future<T> async(Callable<T> work) {
		return workers.submit(work);
	}

	private static <T> T result(Future<T> work) throws Exception {
		return work.get(5, TimeUnit.SECONDS);
	}

	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "Condition did not become true");
			Thread.sleep(5);
		}
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = (owner instanceof McpServer ? McpServer.class : owner.getClass()).getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static int connectionCount(McpServer server) {
		synchronized (server) {
			try {
				return ((Set<?>) field(server, "connections")).size();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
	}

	private static String pipeName() {
		return "\\\\.\\pipe\\copilot-transport-test-" + UUID.randomUUID();
	}

	private static class ScriptedListener implements McpServer.Listener {
		final LinkedBlockingQueue<Object> actions = new LinkedBlockingQueue<>();
		final List<Long> callTimes = new CopyOnWriteArrayList<>();
		final AtomicInteger attempts = new AtomicInteger();
		volatile boolean open = true;

		@Override
		public McpServer.Connection accept() throws IOException {
			callTimes.add(System.nanoTime());
			attempts.incrementAndGet();
			Object action;
			try {
				action = actions.take();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Accept interrupted", e);
			}
			if (action instanceof IOException failure) {
				throw failure;
			}
			if (action instanceof RuntimeException failure) {
				throw failure;
			}
			if (action instanceof Error failure) {
				throw failure;
			}
			return (McpServer.Connection) action;
		}

		@Override
		public boolean isOpen() {
			return open;
		}

		@Override
		public void close() {
			open = false;
			actions.offer(new IOException("Listener closed"));
		}
	}

	private static class IdleConnection implements McpServer.Connection {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch closed = new CountDownLatch(1);
		final AtomicInteger closes = new AtomicInteger();
		private final InputStream input = new InputStream() {
			@Override
			public int read() throws IOException {
				entered.countDown();
				try {
					closed.await();
					return -1;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			}
		};

		public InputStream in() { return input; }
		public OutputStream out() { return OutputStream.nullOutputStream(); }
		public boolean isOpen() { return closed.getCount() != 0; }
		public void close() { closes.incrementAndGet(); closed.countDown(); }
	}

	private static McpServer scriptedServer(ScriptedListener listener) {
		return scriptedServer(listener, LOG);
	}

	private static McpServer scriptedServer(ScriptedListener listener, ILog log) {
		return new McpServer(null, null, "test", (tool, args) -> null, log) {
			@Override
			Listener createListener() {
				return listener;
			}
		};
	}

	@Test
	void persistentAcceptFailuresLogOnceAtStartAndCapThenReportRecovery() throws Exception {
		List<String> warnings = new CopyOnWriteArrayList<>();
		List<String> recoveries = new CopyOnWriteArrayList<>();
		AtomicInteger traces = new AtomicInteger();
		ILog log = ILog.class.cast(java.lang.reflect.Proxy.newProxyInstance(
				ILog.class.getClassLoader(), new Class<?>[] { ILog.class }, (proxy, method, args) -> {
					if (method.getName().equals("warn")) {
						warnings.add((String) args[0]);
						if (args.length == 2) {
							traces.incrementAndGet();
						}
					} else if (method.getName().equals("info") && ((String) args[0]).contains("acceptance recovered")) {
						recoveries.add((String) args[0]);
					}
					return null;
				}));
		ScriptedListener listener = new ScriptedListener();
		McpServer server = scriptedServer(listener, log);
		IdleConnection first = new IdleConnection();
		IdleConnection second = new IdleConnection();
		for (int i = 0; i < 6; i++) {
			listener.actions.add(new IOException("Persistent resource exhaustion"));
		}
		listener.actions.add(first);
		try {
			server.start();
			assertTrue(first.entered.await(10, TimeUnit.SECONDS));
			await(() -> recoveries.size() == 1);
			assertEquals(2, warnings.size());
			assertEquals(1, traces.get());
			assertTrue(warnings.get(1).contains("1000ms"));
			listener.actions.add(new IOException("Another failure episode"));
			listener.actions.add(second);
			assertTrue(second.entered.await(5, TimeUnit.SECONDS));
			await(() -> recoveries.size() == 2);
			assertEquals(3, warnings.size());
			assertEquals(2, traces.get());
			assertTrue(warnings.get(2).contains("100ms"), "Success must reset backoff and logging");
		} finally {
			server.stop();
		}
	}

	@Test
	void recoverableAcceptFailurePreservesConnectionsAndDiscovery(@TempDir Path directory) throws Exception {
		ScriptedListener listener = new ScriptedListener();
		McpServer server = scriptedServer(listener);
		Path path = directory.resolve("ide.lock");
		LockFileManager lock = new LockFileManager(path, "test", "nonce", "endpoint", List::of, LOG);
		AtomicInteger stops = new AtomicInteger();
		server.setStopHandler(() -> { stops.incrementAndGet(); lock.deleteLockFile(); });
		IdleConnection first = new IdleConnection();
		IdleConnection second = new IdleConnection();
		try {
			synchronized (server) {
				server.start();
				lock.writeLockFile();
			}
			listener.actions.add(first);
			assertTrue(first.entered.await(5, TimeUnit.SECONDS));
			listener.actions.add(new IOException("Temporary resource exhaustion"));
			listener.actions.add(second);
			assertTrue(second.entered.await(5, TimeUnit.SECONDS));
			assertEquals(2, connectionCount(server));
			assertTrue(first.isOpen());
			assertTrue(server.isRunning());
			assertTrue(Files.exists(path));
			assertEquals(0, stops.get());
		} finally {
			server.stop();
		}
		assertEquals(1, stops.get());
		assertFalse(Files.exists(path));
	}

	@Test
	void repeatedAcceptFailuresBackOffAndShutdownInterruptsRetry() throws Exception {
		ScriptedListener listener = new ScriptedListener();
		McpServer server = scriptedServer(listener);
		for (int i = 0; i < 100; i++) {
			listener.actions.add(new IOException("Resource exhaustion"));
		}
		try {
			server.start();
			Thread accept = (Thread) field(server, "acceptThread");
			await(() -> listener.attempts.get() >= 4);
			assertTrue(server.isRunning());
			assertTrue(listener.attempts.get() < 10, "Failures must not cause a busy loop");
			for (int i = 1; i <= 3; i++) {
				long intervalMs = TimeUnit.NANOSECONDS.toMillis(listener.callTimes.get(i) - listener.callTimes.get(i - 1));
				assertTrue(intervalMs >= 80L << (i - 1), "Retry intervals must grow exponentially");
			}
			server.stop();
			accept.join(500);
			assertFalse(accept.isAlive(), "Shutdown must interrupt retry sleep");
		} finally {
			server.stop();
		}
	}

	@Test
	void closedListenerStopsConnectionsAndDiscovery(@TempDir Path directory) throws Exception {
		ScriptedListener listener = new ScriptedListener();
		McpServer server = scriptedServer(listener);
		Path path = directory.resolve("ide.lock");
		LockFileManager lock = new LockFileManager(path, "test", "nonce", "endpoint", List::of, LOG);
		CountDownLatch stopped = new CountDownLatch(1);
		server.setStopHandler(() -> { lock.deleteLockFile(); stopped.countDown(); });
		IdleConnection connection = new IdleConnection();
		try {
			synchronized (server) {
				server.start();
				lock.writeLockFile();
			}
			listener.actions.add(connection);
			assertTrue(connection.entered.await(5, TimeUnit.SECONDS));
			listener.open = false;
			listener.actions.add(new IOException("Listener became unusable"));
			assertTrue(stopped.await(5, TimeUnit.SECONDS));
			assertFalse(server.isRunning());
			assertFalse(connection.isOpen());
			assertEquals(0, connectionCount(server));
			assertFalse(Files.exists(path));
			lock.propertyChange(new PropertyChangeEvent(lock, WorkspaceFolderStyle.PREF_KEY, "PROJECTS", "WORKSPACE"));
			assertFalse(Files.exists(path), "Queued preference events must not recreate discovery");
		} finally {
			server.stop();
		}
	}

	@Test
	void startupPublicationCannotRaceFatalAcceptCleanup(@TempDir Path directory) throws Exception {
		ScriptedListener listener = new ScriptedListener();
		listener.actions.add(new IllegalStateException("Immediate listener failure"));
		McpServer server = scriptedServer(listener);
		Path path = directory.resolve("ide.lock");
		LockFileManager lock = new LockFileManager(path, "test", "nonce", "endpoint", List::of, LOG);
		CountDownLatch stopped = new CountDownLatch(1);
		server.setStopHandler(() -> { lock.deleteLockFile(); stopped.countDown(); });
		try {
			synchronized (server) {
				server.start();
				lock.writeLockFile();
				assertTrue(Files.exists(path));
				assertEquals(1, stopped.getCount(), "Startup must exclude fatal cleanup while publishing");
			}
			assertTrue(stopped.await(5, TimeUnit.SECONDS));
			assertFalse(Files.exists(path));
			assertFalse(server.isRunning());
		} finally {
			server.stop();
		}
	}

	@Test
	void failedListenerStartupNotifiesStopOnce() throws Exception {
		McpServer server = new McpServer(null, null, "test", (tool, args) -> null, LOG) {
			@Override
			Listener createListener() throws IOException {
				throw new IOException("Cannot create listener");
			}
		};
		AtomicInteger stops = new AtomicInteger();
		server.setStopHandler(stops::incrementAndGet);
		assertThrows(IOException.class, server::start);
		assertFalse(server.isRunning());
		assertNull(field(server, "listener"));
		assertNull(field(server, "acceptThread"));
		assertEquals(1, stops.get());
		server.stop();
		assertEquals(1, stops.get());
	}

	@Test
	void startupFailurePreservesCauseWhenStopHandlerThrows() {
		IOException failure = new IOException("Cannot create listener");
		IllegalStateException cleanup = new IllegalStateException("Stop handler failed");
		McpServer server = new McpServer(null, null, "test", (tool, args) -> null, LOG) {
			@Override
			Listener createListener() throws IOException { throw failure; }
		};
		server.setStopHandler(() -> { throw cleanup; });
		assertSame(failure, assertThrows(IOException.class, server::start));
		assertArrayEquals(new Throwable[] { cleanup }, failure.getSuppressed());
		assertFalse(server.isRunning());
	}

	@Test
	void teardownFailuresStillCloseOtherConnectionsAndRemoveDiscovery(@TempDir Path directory) throws Exception {
		ScriptedListener listener = new ScriptedListener() {
			@Override
			public void close() {
				super.close();
				throw new IllegalStateException("Listener close failed");
			}
		};
		IdleConnection broken = new IdleConnection() {
			@Override
			public void close() {
				super.close();
				throw new IllegalStateException("Connection close failed");
			}
		};
		IdleConnection normal = new IdleConnection();
		McpServer server = scriptedServer(listener);
		Path path = directory.resolve("ide.lock");
		LockFileManager lock = new LockFileManager(path, "test", "nonce", "endpoint", List::of, LOG);
		AtomicInteger stops = new AtomicInteger();
		server.setStopHandler(() -> { stops.incrementAndGet(); lock.deleteLockFile(); });
		try {
			synchronized (server) {
				server.start();
				lock.writeLockFile();
			}
			Thread accept = (Thread) field(server, "acceptThread");
			listener.actions.add(broken);
			listener.actions.add(normal);
			assertTrue(broken.entered.await(5, TimeUnit.SECONDS));
			assertTrue(normal.entered.await(5, TimeUnit.SECONDS));
			server.stop();
			accept.join(5000);
			assertFalse(accept.isAlive());
			assertFalse(normal.isOpen());
			assertFalse(broken.isOpen());
			assertFalse(Files.exists(path));
			assertNull(field(server, "listener"));
			assertNull(field(server, "acceptThread"));
			assertEquals(0, connectionCount(server));
			assertEquals(1, stops.get());
		} finally {
			server.stop();
		}
	}

	@Test
	void teardownFailureIsReportedWithStopHandlerFailureSuppressed() throws Exception {
		IllegalStateException teardown = new IllegalStateException("Teardown failed");
		IllegalStateException handler = new IllegalStateException("Stop handler failed");
		ILog failingLog = ILog.class.cast(java.lang.reflect.Proxy.newProxyInstance(
				ILog.class.getClassLoader(), new Class<?>[] { ILog.class }, (proxy, method, args) -> {
					if (method.getName().equals("info") && "[MCP] Server stopped".equals(args[0])) {
						throw teardown;
					}
					return null;
				}));
		ScriptedListener listener = new ScriptedListener();
		McpServer server = scriptedServer(listener, failingLog);
		AtomicInteger stops = new AtomicInteger();
		server.setStopHandler(() -> {
			stops.incrementAndGet();
			throw handler;
		});
		server.start();
		assertSame(teardown, assertThrows(IllegalStateException.class, server::stop));
		assertArrayEquals(new Throwable[] { handler }, teardown.getSuppressed());
		assertEquals(1, stops.get(), "Stop handler must still run after a teardown failure");
		assertFalse(server.isRunning());
		assertFalse(listener.isOpen());
	}

	@Test
	void stopHandlerFailureIsReportedWhenTeardownSucceeds() throws Exception {
		IllegalStateException handler = new IllegalStateException("Stop handler failed");
		McpServer server = scriptedServer(new ScriptedListener());
		server.setStopHandler(() -> { throw handler; });
		server.start();
		assertSame(handler, assertThrows(IllegalStateException.class, server::stop));
		assertEquals(0, handler.getSuppressed().length);
		assertFalse(server.isRunning());
	}

	@Test
	void displayActionIgnoresOnlyConcurrentDisposal() {
		DisposedDisplays.ignoreDisposed(() -> {
			throw new org.eclipse.swt.SWTException(org.eclipse.swt.SWT.ERROR_DEVICE_DISPOSED);
		});
		var other = assertThrows(org.eclipse.swt.SWTException.class, () -> DisposedDisplays.ignoreDisposed(() -> {
			throw new org.eclipse.swt.SWTException(org.eclipse.swt.SWT.ERROR_THREAD_INVALID_ACCESS);
		}));
		assertEquals(org.eclipse.swt.SWT.ERROR_THREAD_INVALID_ACCESS, other.code);
		IllegalStateException unrelated = new IllegalStateException("Not an SWT failure");
		assertSame(unrelated, assertThrows(IllegalStateException.class,
				() -> DisposedDisplays.ignoreDisposed(() -> { throw unrelated; })));
		AtomicInteger ran = new AtomicInteger();
		DisposedDisplays.ignoreDisposed(ran::incrementAndGet);
		assertEquals(1, ran.get());
	}

	@Test
	void fatalAcceptCleanupDoesNotInterruptItself() throws Exception {
		ScriptedListener listener = new ScriptedListener();
		listener.actions.add(new IllegalStateException("Fatal acceptance failure"));
		McpServer server = scriptedServer(listener);
		var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
		CountDownLatch stopped = new CountDownLatch(1);
		server.setStopHandler(() -> {
			interrupted.set(Thread.currentThread().isInterrupted());
			stopped.countDown();
		});
		try {
			server.start();
			assertTrue(stopped.await(5, TimeUnit.SECONDS));
			assertFalse(interrupted.get(), "Cleanup must not inherit a self-interrupt");
		} finally {
			server.stop();
		}
	}

	@Test
	void workerStartupFailureStopsServerAndDiscovery(@TempDir Path directory) throws Exception {
		ScriptedListener listener = new ScriptedListener();
		AtomicInteger starts = new AtomicInteger();
		McpServer server = new McpServer(null, null, "test", (tool, args) -> null, LOG) {
			@Override
			Listener createListener() { return listener; }
			@Override
			void startConnectionThread(Runnable handler, long connId) {
				if (starts.incrementAndGet() == 2) {
					throw new OutOfMemoryError("Simulated worker startup failure");
				}
				super.startConnectionThread(handler, connId);
			}
		};
		Path path = directory.resolve("ide.lock");
		LockFileManager lock = new LockFileManager(path, "test", "nonce", "endpoint", List::of, LOG);
		AtomicInteger stops = new AtomicInteger();
		CountDownLatch stopped = new CountDownLatch(1);
		server.setStopHandler(() -> { stops.incrementAndGet(); lock.deleteLockFile(); stopped.countDown(); });
		IdleConnection first = new IdleConnection();
		IdleConnection failed = new IdleConnection();
		try {
			synchronized (server) {
				server.start();
				lock.writeLockFile();
			}
			listener.actions.add(first);
			assertTrue(first.entered.await(5, TimeUnit.SECONDS));
			listener.actions.add(failed);
			assertTrue(stopped.await(5, TimeUnit.SECONDS));
			assertFalse(server.isRunning());
			assertFalse(listener.isOpen());
			assertFalse(first.isOpen());
			assertEquals(1, failed.closes.get());
			assertFalse(Files.exists(path));
			assertEquals(0, connectionCount(server));
			server.stop();
			assertEquals(1, stops.get());
		} finally {
			server.stop();
		}
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 2 })
	void staleAcceptResultCannotAffectRestartedServer(int resultType) throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		IdleConnection stale = new IdleConnection();
		ScriptedListener old = new ScriptedListener() {
			@Override
			public McpServer.Connection accept() throws IOException {
				entered.countDown();
				boolean interrupted = false;
				while (release.getCount() != 0) {
					try {
						release.await();
					} catch (InterruptedException e) {
						interrupted = true;
					}
				}
				if (interrupted) {
					Thread.currentThread().interrupt();
				}
				return switch (resultType) {
				case 0 -> throw new IOException("Late I/O error from obsolete listener");
				case 1 -> throw new IllegalStateException("Late fatal error from obsolete listener");
				default -> stale;
				};
			}
		};
		ScriptedListener current = new ScriptedListener();
		AtomicInteger generations = new AtomicInteger();
		AtomicInteger stops = new AtomicInteger();
		McpServer server = new McpServer(null, null, "test", (tool, args) -> null, LOG) {
			@Override
			Listener createListener() { return generations.getAndIncrement() == 0 ? old : current; }
		};
		server.setStopHandler(stops::incrementAndGet);
		try {
			server.start();
			Thread obsolete = (Thread) field(server, "acceptThread");
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			server.stop();
			server.start();
			release.countDown();
			obsolete.join(5000);
			assertFalse(obsolete.isAlive());
			assertTrue(server.isRunning());
			assertTrue(current.isOpen());
			assertEquals(1, stops.get());
			if (resultType == 2) {
				assertEquals(1, stale.closes.get());
			}
			IdleConnection connection = new IdleConnection();
			current.actions.add(connection);
			assertTrue(connection.entered.await(5, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			server.stop();
		}
	}

	@Test
	void discoveryDeletionWaitsForActiveWriteAndPreventsLaterWrites(@TempDir Path directory) throws Exception {
		CountDownLatch writing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Path path = directory.resolve("ide.lock");
		LockFileManager lock = new LockFileManager(path, "test", "nonce", "endpoint", () -> {
			writing.countDown();
			try {
				assertTrue(release.await(5, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				throw new AssertionError(e);
			}
			return List.of("workspace");
		}, LOG);
		Future<Boolean> writer = async(() -> { lock.writeLockFile(); return true; });
		try {
			assertTrue(writing.await(5, TimeUnit.SECONDS));
			var deletingThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
			Future<Boolean> delete = async(() -> {
				deletingThread.set(Thread.currentThread());
				lock.deleteLockFile();
				return true;
			});
			await(() -> deletingThread.get() != null && deletingThread.get().getState() == Thread.State.BLOCKED);
			assertFalse(delete.isDone(), "Deletion must wait for the active writer");
			release.countDown();
			assertTrue(result(writer));
			assertTrue(result(delete));
			assertFalse(Files.exists(path));
			lock.writeLockFile();
			assertFalse(Files.exists(path));
		} finally {
			release.countDown();
			lock.deleteLockFile();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void acceptRetriesClientDisconnectedBeforeAccept() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Object oldHandle = field(pipe, "pending");
		try {
			try (RandomAccessFile client = new RandomAccessFile(name, "rw")) {
				// Disconnect before the server issues ConnectNamedPipe.
			}
			Future<McpServer.Connection> accept = async(pipe::accept);
			await(() -> {
				synchronized (pipe) {
					try {
						Object pending = field(pipe, "pending");
						return pending != null && pending != oldHandle;
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				}
			});
			try (RandomAccessFile client = new RandomAccessFile(name, "rw");
					McpServer.Connection connection = result(accept)) {
				client.write(42);
				assertEquals(42, connection.in().read());
				synchronized (oldHandle) {
					assertEquals(false, field(oldHandle, "open"));
					assertEquals(0, operationCount(oldHandle));
				}
			}
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void earlyDisconnectRetriesKeepPipeNameAvailable() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		var stopping = new java.util.concurrent.atomic.AtomicBoolean();
		Future<Boolean> accept = async(() -> {
			try {
				while (!stopping.get()) {
					try (McpServer.Connection connection = pipe.accept()) {
					}
				}
			} catch (IOException e) {
				if (!stopping.get()) {
					throw e;
				}
			}
			return true;
		});
		try (Arena arena = Arena.ofConfined()) {
			var lookup = SymbolLookup.libraryLookup("kernel32", arena);
			var stateLayout = Linker.Option.captureStateLayout();
			var lastError = stateLayout.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));
			var createFile = Linker.nativeLinker().downcallHandle(lookup.find("CreateFileW").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
							ValueLayout.ADDRESS),
					Linker.Option.captureCallState("GetLastError"));
			var close = Linker.nativeLinker().downcallHandle(lookup.find("CloseHandle").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			MemorySegment state = arena.allocate(stateLayout);
			MemorySegment pipeName = arena.allocateFrom(ValueLayout.JAVA_BYTE,
					(name + '\0').getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
			int connected = 0;
			for (int i = 0; i < 10000; i++) {
				try {
					MemorySegment client = (MemorySegment) createFile.invokeExact(state, pipeName, 0xC0000000, 0,
							MemorySegment.NULL, 3, 0, MemorySegment.NULL);
					if (client.address() == -1L) {
						assertEquals(231, (int) lastError.get(state, 0L),
								"Only ERROR_PIPE_BUSY is allowed while the listener is open");
					} else {
						assertEquals(1, (int) close.invokeExact(client));
						connected++;
					}
				} catch (Throwable t) {
					throw new AssertionError(t);
				}
			}
			assertTrue(connected > 1, "Multiple clients must connect to exercise instance replacement");
		} finally {
			stopping.set(true);
			pipe.close();
			assertTrue(result(accept));
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void closeCancelsAccept() throws Exception {
		NamedPipe pipe = new NamedPipe(pipeName());
		Object handle = field(pipe, "pending");
		Future<Boolean> accept = async(() -> {
			assertThrows(IOException.class, pipe::accept);
			return true;
		});
		try {
			await(() -> operationCount(handle) == 1);
		} finally {
			pipe.close();
		}
		assertTrue(result(accept));
		assertHandleClosed(handle);
	}

	private static int operationCount(Object handle) {
		synchronized (handle) {
			try {
				return (int) field(handle, "operations");
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
	}

	private static void assertHandleClosed(Object handle) throws Exception {
		assertEquals(0, operationCount(handle));
		assertFalse(nativeHandleOpen(handle), "Native pipe handle remains open");
	}

	private static boolean nativeHandleOpen(Object handle) throws Exception {
		try (Arena arena = Arena.ofConfined()) {
			var lookup = SymbolLookup.libraryLookup("kernel32", arena);
			// A closed handle value may already have been reused for a non-pipe resource.
			var info = Linker.nativeLinker().downcallHandle(lookup.find("GetNamedPipeInfo").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
							ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			try {
				int success = (int) info.invokeExact((MemorySegment) field(handle, "value"),
						MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
				return success != 0;
			} catch (Throwable t) {
				throw new AssertionError(t);
			}
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void closeCancelsConcurrentReadAndWrite() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Future<McpServer.Connection> accept = async(pipe::accept);
		try (RandomAccessFile client = new RandomAccessFile(name, "rw");
				McpServer.Connection connection = result(accept)) {
			Object handle = field(connection, "handle");
			Future<Boolean> read = async(() -> {
				try {
					connection.in().read();
				} catch (IOException expected) {
					assertFalse(connection.isOpen());
				}
				return true;
			});
			Future<Boolean> write = async(() -> {
				assertThrows(IOException.class, () -> connection.out().write(new byte[8 * 1024 * 1024]));
				return true;
			});
			await(() -> operationCount(handle) == 2);
			connection.close();
			assertTrue(result(read));
			assertTrue(result(write));
			assertHandleClosed(handle);
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void writesCompleteWhileReadIsPending() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Future<McpServer.Connection> accept = async(pipe::accept);
		try (RandomAccessFile client = new RandomAccessFile(name, "rw");
				McpServer.Connection connection = result(accept)) {
			Object handle = field(connection, "handle");
			Future<Integer> read = async(() -> connection.in().read());
			await(() -> operationCount(handle) == 1);
			result(async(() -> {
				connection.out().write(42);
				return true;
			}));
			int received = result(async(client::read));
			assertEquals(42, received);
			client.write(17);
			assertEquals(17, result(read));
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void writeFailureClosesHandle() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Future<McpServer.Connection> accept = async(pipe::accept);
		try {
			RandomAccessFile client = new RandomAccessFile(name, "rw");
			try (McpServer.Connection connection = result(accept)) {
				Object handle = field(connection, "handle");
				client.close();
				assertThrows(IOException.class, () -> connection.out().write(1));
				assertFalse(connection.isOpen());
				assertHandleClosed(handle);
			} finally {
				client.close();
			}
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void replacementFailureClosesConnectedInstance() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Object handle = field(pipe, "pending");
		Field nameField = NamedPipe.class.getDeclaredField("name");
		nameField.setAccessible(true);
		nameField.set(pipe, "not-a-valid-pipe-name");
		Future<Boolean> accept = async(() -> {
			assertThrows(IOException.class, pipe::accept);
			return true;
		});
		try (RandomAccessFile client = new RandomAccessFile(name, "rw")) {
			assertTrue(result(accept));
			assertHandleClosed(handle);
			assertNull(field(pipe, "pending"));
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void nativeCloseReportsWin32Failure() {
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> NamedPipe.closeHandle(MemorySegment.NULL));
		assertEquals("CloseHandle failed: 6", failure.getMessage());
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void nativeClosePreservesUncheckedInvocationFailuresAndWrapsCheckedFailures() {
		for (Throwable failure : List.of(new IllegalArgumentException("bad invocation"),
				new AssertionError("native linkage failure"), new IOException("checked invocation failure"))) {
			var throwing = MethodHandles.throwException(int.class, failure.getClass()).bindTo(failure);
			var close = MethodHandles.dropArguments(throwing, 0, MemorySegment.class, MemorySegment.class);
			Class<? extends Throwable> expected = failure instanceof IOException ? IllegalStateException.class
					: failure.getClass();
			Throwable reported = assertThrows(expected, () -> NamedPipe.closeHandle(MemorySegment.NULL, close));
			if (failure instanceof IOException) {
				assertEquals("CloseHandle invocation failed", reported.getMessage());
				assertSame(failure, reported.getCause());
			} else {
				assertSame(failure, reported);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	@EnabledOnOs(OS.WINDOWS)
	void operationFailurePreservesCauseWhenHandleReleaseThrows(boolean write) throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Future<McpServer.Connection> accept = async(pipe::accept);
		IllegalStateException cleanupFailure = new IllegalStateException("release observer failed");
		AtomicInteger nativeStarts = new AtomicInteger();
		AtomicInteger nativeCloses = new AtomicInteger();
		try (RandomAccessFile client = new RandomAccessFile(name, "rw");
				McpServer.Connection connection = result(accept)) {
			Object handle = field(connection, "handle");
			NamedPipe.installOperationHook(connection, new NamedPipe.OperationHook() {
				@Override
				public void registered() {
					assertDoesNotThrow(connection::close);
				}

				@Override
				public void starting() {
					nativeStarts.incrementAndGet();
				}

				@Override
				public void closed() {
					nativeCloses.incrementAndGet();
					throw cleanupFailure;
				}
			});
			IOException failure = assertThrows(IOException.class, () -> {
				if (write) {
					connection.out().write(42);
				} else {
					connection.in().read();
				}
			});
			assertEquals("Pipe closed", failure.getMessage());
			assertArrayEquals(new Throwable[] { cleanupFailure }, failure.getSuppressed());
			assertEquals(0, nativeStarts.get());
			assertEquals(1, nativeCloses.get());
			assertHandleClosed(handle);
			connection.close();
			assertEquals(1, nativeCloses.get());
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void pipeAcceptanceRecoversAfterReplacementCreationFailure() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Field nameField = NamedPipe.class.getDeclaredField("name");
		nameField.setAccessible(true);
		nameField.set(pipe, "not-a-valid-pipe-name");
		Future<Boolean> failed = async(() -> {
			assertThrows(IOException.class, pipe::accept);
			return true;
		});
		try {
			try (RandomAccessFile client = new RandomAccessFile(name, "rw")) {
				assertTrue(result(failed));
			}
			assertTrue(pipe.isOpen());
			assertNull(field(pipe, "pending"));
			nameField.set(pipe, name);
			Future<McpServer.Connection> retry = async(pipe::accept);
			await(() -> {
				synchronized (pipe) {
					try {
						return field(pipe, "pending") != null;
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				}
			});
			try (RandomAccessFile client = new RandomAccessFile(name, "rw");
					McpServer.Connection connection = result(retry)) {
				client.write(42);
				assertEquals(42, connection.in().read());
			}
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void closeRacingOperationStartNeverUsesClosedHandle() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Future<McpServer.Connection> accept = async(pipe::accept);
		CountDownLatch readRegistered = new CountDownLatch(1);
		CountDownLatch writeRegistered = new CountDownLatch(1);
		CountDownLatch releaseRead = new CountDownLatch(1);
		CountDownLatch releaseWrite = new CountDownLatch(1);
		AtomicInteger registered = new AtomicInteger();
		AtomicInteger nativeStarts = new AtomicInteger();
		AtomicInteger nativeCloses = new AtomicInteger();
		try (RandomAccessFile client = new RandomAccessFile(name, "rw");
				McpServer.Connection connection = result(accept)) {
			Object handle = field(connection, "handle");
			NamedPipe.installOperationHook(connection, new NamedPipe.OperationHook() {
				@Override
				public void registered() {
					boolean read = registered.incrementAndGet() == 1;
					(read ? readRegistered : writeRegistered).countDown();
					try {
						assertTrue((read ? releaseRead : releaseWrite).await(5, TimeUnit.SECONDS),
								"Registered operation was not released");
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new AssertionError(e);
					}
				}

				@Override
				public void starting() {
					nativeStarts.incrementAndGet();
				}

				@Override
				public void closed() {
					nativeCloses.incrementAndGet();
				}
			});
			try {
				Future<Boolean> read = async(() -> {
					assertThrows(IOException.class, () -> connection.in().read());
					return true;
				});
				assertTrue(readRegistered.await(5, TimeUnit.SECONDS));
				Future<Boolean> write = async(() -> {
					assertThrows(IOException.class, () -> connection.out().write(42));
					return true;
				});
				assertTrue(writeRegistered.await(5, TimeUnit.SECONDS));
				assertEquals(2, operationCount(handle));
				connection.close();
				assertFalse(connection.isOpen());
				assertTrue(nativeHandleOpen(handle), "Close must retain registered operations' handle");
				assertEquals(0, nativeStarts.get());
				assertEquals(0, nativeCloses.get());
				releaseRead.countDown();
				assertTrue(result(read));
				assertEquals(1, operationCount(handle));
				assertTrue(nativeHandleOpen(handle), "The last registered operation still owns the handle");
				assertEquals(0, nativeStarts.get());
				assertEquals(0, nativeCloses.get());
				releaseWrite.countDown();
				assertTrue(result(write));
				assertHandleClosed(handle);
				assertEquals(0, nativeStarts.get(), "No native operation may start after close");
				assertEquals(1, nativeCloses.get());
				connection.close();
				assertEquals(1, nativeCloses.get(), "Repeated close must not release the handle twice");
			} finally {
				releaseRead.countDown();
				releaseWrite.countDown();
			}
		} finally {
			pipe.close();
		}
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void failedUnixBindReleasesListener() throws Exception {
		Path directory = Files.createTempDirectory("mcp-bind-");
		McpServer server = new McpServer(directory.resolve("x".repeat(200)), null, "test", (tool, args) -> null, LOG);
		try {
			assertThrows(IOException.class, server::start);
			assertFalse(server.isRunning());
			assertNull(field(server, "listener"));
		} finally {
			server.stop();
			Files.delete(directory);
		}
	}

	@Test
	void shutdownClosesIdleAndIncompleteRequests() throws Exception {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		Path path = Path.of(System.getProperty("java.io.tmpdir"), "mcp-" + UUID.randomUUID() + ".sock");
		String name = pipeName();
		McpServer server = new McpServer(windows ? null : path, windows ? name : null, "test",
				(tool, args) -> null, LOG);
		server.start();
		try (var idle = connect(windows, name, path)) {
			await(() -> connectionCount(server) == 1);
			try (var partial = connect(windows, name, path)) {
				partial.out().write("POST /mcp HTTP/1.1\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
				partial.out().flush();
				await(() -> connectionCount(server) == 2);
				server.stop();
				assertTrue(result(async(() -> disconnected(idle.in()))));
				assertTrue(result(async(() -> disconnected(partial.in()))));
				await(() -> connectionCount(server) == 0);
				await(() -> Thread.getAllStackTraces().keySet().stream()
						.noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("MCP #")));
			}
		} finally {
			server.stop();
		}
	}

	@Test
	void shutdownInterruptsWorkerWaitingForToolResult() throws Exception {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		Path path = Path.of(System.getProperty("java.io.tmpdir"), "mcp-" + UUID.randomUUID() + ".sock");
		String name = pipeName();
		var entered = new java.util.concurrent.CountDownLatch(1);
		var interrupted = new java.util.concurrent.CountDownLatch(1);
		var diffResult = new java.util.concurrent.CompletableFuture<Object>();
		McpServer server = new McpServer(windows ? null : path, windows ? name : null, "test", (tool, args) -> {
			assertEquals("open_diff", tool);
			assertEquals(!windows, Thread.currentThread().isVirtual(),
					"Unix workers must be virtual; native pipe workers must be platform threads");
			entered.countDown();
			try {
				return diffResult.get();
			} catch (InterruptedException e) {
				interrupted.countDown();
				throw e;
			}
		}, LOG);
		server.start();
		try (var client = connect(windows, name, path)) {
			String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
					+ "\"params\":{\"name\":\"open_diff\",\"arguments\":{}}}";
			String request = "POST /mcp HTTP/1.1\r\nAuthorization: Nonce test\r\nContent-Length: "
					+ body.length() + "\r\n\r\n" + body;
			client.out().write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			client.out().flush();
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			server.stop();
			assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Tool-result wait was not interrupted");
			await(() -> {
				synchronized (server) {
					try {
						return ((Set<?>) field(server, "workers")).isEmpty();
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				}
			});
			assertEquals(0, connectionCount(server));
		} finally {
			server.stop();
			diffResult.complete(null);
		}
	}

	@Test
	void completedHandlerUnregistersConnection() throws Exception {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		Path path = Path.of(System.getProperty("java.io.tmpdir"), "mcp-" + UUID.randomUUID() + ".sock");
		String name = pipeName();
		McpServer server = new McpServer(windows ? null : path, windows ? name : null, "test",
				(tool, args) -> null, LOG);
		server.start();
		try (var client = connect(windows, name, path)) {
			await(() -> connectionCount(server) == 1);
			client.out().write("GET /mcp HTTP/1.1\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			client.out().flush();
			String response = result(async(() -> new String(client.in().readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8)));
			assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
			await(() -> connectionCount(server) == 0);
		} finally {
			server.stop();
		}
	}

	private static boolean disconnected(InputStream in) throws IOException {
		try {
			return in.read() == -1;
		} catch (IOException expected) {
			return true;
		}
	}

	private static McpServer.Connection connect(boolean windows, String name, Path path) throws IOException {
		if (windows) {
			RandomAccessFile file = new RandomAccessFile(name, "rw");
			return new McpServer.Connection() {
				public InputStream in() { return java.nio.channels.Channels.newInputStream(file.getChannel()); }
				public OutputStream out() { return java.nio.channels.Channels.newOutputStream(file.getChannel()); }
				public boolean isOpen() { return file.getChannel().isOpen(); }
				public void close() throws IOException { file.close(); }
			};
		}
		SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			socket.connect(UnixDomainSocketAddress.of(path));
		} catch (IOException | RuntimeException e) {
			socket.close();
			throw e;
		}
		return new McpServer.Connection() {
			public InputStream in() { return java.nio.channels.Channels.newInputStream(socket); }
			public OutputStream out() { return java.nio.channels.Channels.newOutputStream(socket); }
			public boolean isOpen() { return socket.isOpen(); }
			public void close() throws IOException { socket.close(); }
		};
	}

	@Test
	void workerStartupFailureClosesAcceptedConnection() throws Exception {
		McpServer server = new McpServer(Path.of("unused"), null, "test", (tool, args) -> null, LOG) {
			@Override
			void startConnectionThread(Runnable handler, long connId) {
				throw new OutOfMemoryError("Simulated worker startup failure");
			}
		};
		assertDispatchCloses(server, true);
	}

	@Test
	void lateAcceptedConnectionIsClosedAfterShutdown() throws Exception {
		McpServer server = new McpServer(Path.of("unused"), null, "test", (tool, args) -> null, LOG);
		assertDispatchCloses(server, false);
	}

	private static void assertDispatchCloses(McpServer server, boolean running) throws Exception {
		((java.util.concurrent.atomic.AtomicBoolean) field(server, "running")).set(running);
		boolean[] closed = { false };
		McpServer.Connection client = new McpServer.Connection() {
			public InputStream in() { return InputStream.nullInputStream(); }
			public OutputStream out() { return OutputStream.nullOutputStream(); }
			public boolean isOpen() { return !closed[0]; }
			public void close() { closed[0] = true; }
		};
		Class<?> listener = Class.forName(McpServer.class.getName() + "$Listener");
		Method dispatch = McpServer.class.getDeclaredMethod("dispatchConnection", McpServer.Connection.class, listener);
		dispatch.setAccessible(true);
		if (running) {
			var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
					() -> dispatch.invoke(server, client, null));
			assertInstanceOf(OutOfMemoryError.class, thrown.getCause());
		} else {
			dispatch.invoke(server, client, null);
		}
		assertTrue(closed[0]);
		assertEquals(0, connectionCount(server));
	}

	private record Logged(String level, String message, Throwable failure) {
	}

	private static ILog recordingLog(List<Logged> logged) {
		return ILog.class.cast(java.lang.reflect.Proxy.newProxyInstance(ILog.class.getClassLoader(),
				new Class<?>[] { ILog.class }, (proxy, method, args) -> {
					if (args != null && args.length > 0 && args[0] instanceof String message) {
						Throwable failure = args.length > 1 && args[1] instanceof Throwable t ? t : null;
						logged.add(new Logged(method.getName(), message, failure));
					}
					return null;
				}));
	}

	private static McpServer.Connection failingClose(InputStream in, RuntimeException closeFailure, boolean[] closed) {
		return new McpServer.Connection() {
			public InputStream in() { return in; }
			public OutputStream out() { return OutputStream.nullOutputStream(); }
			public boolean isOpen() { return !closed[0]; }
			public void close() {
				closed[0] = true;
				throw closeFailure;
			}
		};
	}

	@Test
	@SuppressWarnings("unchecked")
	void stopContinuesAfterReleaseFailures() throws Exception {
		List<Logged> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
		McpServer server = new McpServer(null, "unused", "test", (tool, args) -> null, recordingLog(logged));
		IllegalStateException listenerFailure = new IllegalStateException("Listener release failed");
		IllegalStateException connectionFailure = new IllegalStateException("Connection release failed");
		Class<?> listenerType = Class.forName(McpServer.class.getName() + "$Listener");
		Object listener = java.lang.reflect.Proxy.newProxyInstance(listenerType.getClassLoader(),
				new Class<?>[] { listenerType }, (proxy, method, args) -> {
					if (method.getName().equals("close")) {
						throw listenerFailure;
					}
					throw new UnsupportedOperationException(method.getName());
				});
		boolean[] brokenClosed = { false };
		boolean[] normalClosed = { false };
		McpServer.Connection broken = failingClose(InputStream.nullInputStream(), connectionFailure, brokenClosed);
		McpServer.Connection normal = new McpServer.Connection() {
			public InputStream in() { return InputStream.nullInputStream(); }
			public OutputStream out() { return OutputStream.nullOutputStream(); }
			public boolean isOpen() { return !normalClosed[0]; }
			public void close() { normalClosed[0] = true; }
		};
		CountDownLatch interrupted = new CountDownLatch(1);
		Thread worker = Thread.ofPlatform().daemon().start(() -> {
			try {
				Thread.sleep(TimeUnit.SECONDS.toMillis(30));
			} catch (InterruptedException e) {
				interrupted.countDown();
			}
		});
		synchronized (server) {
			((java.util.concurrent.atomic.AtomicBoolean) field(server, "running")).set(true);
			Field listenerField = McpServer.class.getDeclaredField("listener");
			listenerField.setAccessible(true);
			listenerField.set(server, listener);
			((Set<McpServer.Connection>) field(server, "connections")).add(broken);
			((Set<McpServer.Connection>) field(server, "connections")).add(normal);
			((Set<Thread>) field(server, "workers")).add(worker);
		}
		server.stop();
		assertTrue(brokenClosed[0]);
		assertTrue(normalClosed[0]);
		assertNull(field(server, "listener"));
		assertEquals(0, connectionCount(server));
		assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Release failures must not skip worker interruption");
		assertTrue(logged.stream().anyMatch(entry -> entry.failure() == listenerFailure));
		assertTrue(logged.stream().anyMatch(entry -> entry.failure() == connectionFailure));
	}

	@Test
	void connectionReleaseFailureIsLogged() throws Exception {
		List<Logged> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
		McpServer server = new McpServer(null, "unused", "test", (tool, args) -> null, recordingLog(logged));
		((java.util.concurrent.atomic.AtomicBoolean) field(server, "running")).set(true);
		Method handle = McpServer.class.getDeclaredMethod("handleConnection", McpServer.Connection.class, long.class);
		handle.setAccessible(true);

		IllegalStateException afterEof = new IllegalStateException("Release after EOF failed");
		boolean[] closed = { false };
		handle.invoke(server, failingClose(InputStream.nullInputStream(), afterEof, closed), 1L);
		assertTrue(closed[0]);
		assertTrue(logged.stream().anyMatch(entry -> entry.failure() == afterEof));

		IOException readFailure = new IOException("Read failed");
		IllegalStateException afterError = new IllegalStateException("Release after read failure failed");
		InputStream failingRead = new InputStream() {
			@Override
			public int read() throws IOException {
				throw readFailure;
			}
		};
		handle.invoke(server, failingClose(failingRead, afterError, new boolean[1]), 2L);
		assertTrue(logged.stream().anyMatch(entry -> entry.failure() == readFailure));
		assertArrayEquals(new Throwable[] { afterError }, readFailure.getSuppressed());
		assertEquals(0, connectionCount(server));
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void disconnectedInstanceReleaseFailureIsRecoverable() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Object oldHandle = field(pipe, "pending");
		IllegalStateException releaseFailure = new IllegalStateException("Disconnected instance release failed");
		Field hook = oldHandle.getClass().getDeclaredField("operationHook");
		hook.setAccessible(true);
		synchronized (oldHandle) {
			hook.set(oldHandle, new NamedPipe.OperationHook() {
				@Override
				public void registered() {
				}

				@Override
				public void starting() {
				}

				@Override
				public void closed() {
					throw releaseFailure;
				}
			});
		}
		try {
			try (RandomAccessFile client = new RandomAccessFile(name, "rw")) {
				// Disconnect before the server issues ConnectNamedPipe.
			}
			IOException failure = assertThrows(IOException.class, pipe::accept);
			assertSame(releaseFailure, failure.getCause());
			assertHandleClosed(oldHandle);
			synchronized (pipe) {
				assertTrue((boolean) field(pipe, "open"));
				assertNotNull(field(pipe, "pending"));
			}
			Future<McpServer.Connection> accept = async(pipe::accept);
			try (RandomAccessFile client = new RandomAccessFile(name, "rw");
					McpServer.Connection connection = result(accept)) {
				client.write(42);
				assertEquals(42, connection.in().read());
			}
		} finally {
			pipe.close();
		}
	}
}
