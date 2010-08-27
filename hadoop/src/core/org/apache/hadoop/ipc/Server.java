/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ipc;

import ibis.smartsockets.virtual.VirtualSocketAddress;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;

import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import javax.security.auth.Subject;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.ipc.metrics.RpcMetrics;
import org.apache.hadoop.security.authorize.AuthorizationException;

/**
 * An abstract IPC service. IPC calls take a single {@link Writable} as a
 * parameter, and return a {@link Writable} as their value. A service runs on a
 * port and is defined by a parameter class and a value class.
 * 
 * @see Client
 */
public abstract class Server {

	/**
	 * The first four bytes of Hadoop RPC connections
	 */
	public static final ByteBuffer HEADER = ByteBuffer.wrap("hrpc".getBytes());

	// 1 : Introduce ping and server does not throw away RPCs
	// 3 : Introduce the protocol into the RPC connection header
	public static final byte CURRENT_VERSION = 3;

	/**
	 * How many calls/handler are allowed in the queue.
	 */
	private static final int MAX_QUEUE_SIZE_PER_HANDLER = 100;

	public static final Log LOG = LogFactory.getLog(Server.class);

	private static final ThreadLocal<Server> SERVER = new ThreadLocal<Server>();

	private static final Map<String, Class<?>> PROTOCOL_CACHE = new ConcurrentHashMap<String, Class<?>>();

	static Class<?> getProtocolClass(String protocolName, Configuration conf)
			throws ClassNotFoundException {
		Class<?> protocol = PROTOCOL_CACHE.get(protocolName);
		if (protocol == null) {
			protocol = conf.getClassByName(protocolName);
			PROTOCOL_CACHE.put(protocolName, protocol);
		}
		return protocol;
	}

	/**
	 * Returns the server instance called under or null. May be called under
	 * {@link #call(Writable, long)} implementations, and under {@link Writable}
	 * methods of paramters and return values. Permits applications to access
	 * the server context.
	 */
	public static Server get() {
		return SERVER.get();
	}

	private int port; // port we listen on
	private int handlerCount; // number of handler threads
	private Class<? extends Writable> paramClass; // class of call parameters

	protected RpcMetrics rpcMetrics;

	private Configuration conf;

	volatile private boolean running = true; // true while server runs

	protected Server(int port, Class<? extends Writable> paramClass,
			int handlerCount, Configuration conf) throws IOException {
		this(port, paramClass, handlerCount, conf, Integer.toString(port));
	}

	/**
	 * Constructs a server listening on the named port and address. Parameters
	 * passed must be of the named class. The
	 * <code>handlerCount</handlerCount> determines
   * the number of handler threads that will be used to process calls.
	 * 
	 */
	protected Server(int port, Class<? extends Writable> paramClass,
			int handlerCount, Configuration conf, String serverName)
			throws IOException {
		this.conf = conf;
		this.port = port;
		this.paramClass = paramClass;
		this.handlerCount = handlerCount;
	}

	Configuration getConf() {
		return conf;
	}

	/** Starts the service. Must be called before any calls will be handled. */
	public synchronized void start() throws IOException {

	}

	/** Stops the service. No new calls will be handled after this is called. */
	public synchronized void stop() {

	}

	/**
	 * Wait for the server to be stopped. Does not wait for all subthreads to
	 * finish. See {@link #stop()}.
	 */
	public synchronized void join() throws InterruptedException {
		while (running) {
			wait();
		}
	}

	/**
	 * Called for each call.
	 * 
	 * @deprecated Use {@link #call(Class, Writable, long)} instead
	 */
	@Deprecated
	public Writable call(Writable param, long receiveTime) throws IOException {
		return call(null, param, receiveTime);
	}

	/** Called for each call. */
	public abstract Writable call(Class<?> protocol, Writable param,
			long receiveTime) throws IOException;

	/**
	 * Authorize the incoming client connection.
	 * 
	 * @param user
	 *            client user
	 * @param connection
	 *            incoming connection
	 * @throws AuthorizationException
	 *             when the client isn't authorized to talk the protocol
	 */
	public void authorize(Subject user, ConnectionHeader connection)
			throws AuthorizationException {
	}

	public int getPort() {
		// TODO Auto-generated method stub
		return 0;
	}

}