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
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import org.eclipse.core.runtime.ILog;

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
