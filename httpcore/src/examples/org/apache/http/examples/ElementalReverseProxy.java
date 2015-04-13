/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.examples;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.net.Socket;

import org.apache.http.ConnectionClosedException;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpClientConnection;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpServerConnection;
import org.apache.http.impl.DefaultBHttpClientConnection;
import org.apache.http.impl.DefaultBHttpServerConnection;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.protocol.UriHttpRequestHandlerMapper;

/**
 * Elemental HTTP/1.1 reverse proxy.
 */
public class ElementalReverseProxy {

    private static final String HTTP_IN_CONN = "http.proxy.in-conn";
    private static final String HTTP_OUT_CONN = "http.proxy.out-conn";
    private static final String HTTP_CONN_KEEPALIVE = "http.proxy.conn-keepalive";

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Please specified target hostname and port");
            System.exit(1);
        }
        final String hostname = args[0];
        int port = 80;
        if (args.length > 1) {
            port = Integer.parseInt(args[1]);
        }
        final HttpHost target = new HttpHost(hostname, port);

        final Thread t = new RequestListenerThread(8888, target);
        t.setDaemon(false);
        t.start();
    }

    static class ProxyHandler implements HttpRequestHandler  {

        private final HttpHost target;
        private final HttpProcessor httpproc;
        private final HttpRequestExecutor httpexecutor;
        private final ConnectionReuseStrategy connStrategy;

        public ProxyHandler(
                final HttpHost target,
                final HttpProcessor httpproc,
                final HttpRequestExecutor httpexecutor) {
            super();
            this.target = target;
            this.httpproc = httpproc;
            this.httpexecutor = httpexecutor;
            this.connStrategy = DefaultConnectionReuseStrategy.INSTANCE;
        }

        @Override
        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {

            final HttpClientConnection conn = (HttpClientConnection) context.getAttribute(
                    HTTP_OUT_CONN);

            context.setAttribute(HttpCoreContext.HTTP_CONNECTION, conn);
            context.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, this.target);

            System.out.println(">> Request URI: " + request.getRequestLine().getUri());

            // Remove hop-by-hop headers
            request.removeHeaders(HttpHeaders.CONTENT_LENGTH);
            request.removeHeaders(HttpHeaders.TRANSFER_ENCODING);
            request.removeHeaders(HttpHeaders.CONNECTION);
            request.removeHeaders("Keep-Alive");
            request.removeHeaders("Proxy-Authenticate");
            request.removeHeaders(HttpHeaders.TE);
            request.removeHeaders(HttpHeaders.TRAILER);
            request.removeHeaders(HttpHeaders.UPGRADE);

            this.httpexecutor.preProcess(request, this.httpproc, context);
            final HttpResponse targetResponse = this.httpexecutor.execute(request, conn, context);
            this.httpexecutor.postProcess(response, this.httpproc, context);

            // Remove hop-by-hop headers
            targetResponse.removeHeaders(HttpHeaders.CONTENT_LENGTH);
            targetResponse.removeHeaders(HttpHeaders.TRANSFER_ENCODING);
            targetResponse.removeHeaders(HttpHeaders.CONNECTION);
            targetResponse.removeHeaders("Keep-Alive");
            targetResponse.removeHeaders("TE");
            targetResponse.removeHeaders("Trailers");
            targetResponse.removeHeaders("Upgrade");

            response.setStatusLine(targetResponse.getStatusLine());
            response.setHeaders(targetResponse.getAllHeaders());
            response.setEntity(targetResponse.getEntity());

            System.out.println("<< Response: " + response.getStatusLine());

            final boolean keepalive = this.connStrategy.keepAlive(request, response, context);
            context.setAttribute(HTTP_CONN_KEEPALIVE, new Boolean(keepalive));
        }

    }

    static class RequestListenerThread extends Thread {

        private final HttpHost target;
        private final ServerSocket serversocket;
        private final HttpService httpService;

        public RequestListenerThread(final int port, final HttpHost target) throws IOException {
            this.target = target;
            this.serversocket = new ServerSocket(port);

            // Set up HTTP protocol processor for incoming connections
            final HttpProcessor inhttpproc = new ImmutableHttpProcessor(
                    new HttpRequestInterceptor[] {
                            new RequestContent(),
                            new RequestTargetHost(),
                            new RequestConnControl(),
                            new RequestUserAgent("Test/1.1"),
                            new RequestExpectContinue()
             });

            // Set up HTTP protocol processor for outgoing connections
            final HttpProcessor outhttpproc = new ImmutableHttpProcessor(
                    new HttpResponseInterceptor[] {
                            new ResponseDate(),
                            new ResponseServer("Test/1.1"),
                            new ResponseContent(),
                            new ResponseConnControl()
            });

            // Set up outgoing request executor
            final HttpRequestExecutor httpexecutor = new HttpRequestExecutor();

            // Set up incoming request handler
            final UriHttpRequestHandlerMapper reqistry = new UriHttpRequestHandlerMapper();
            reqistry.register("*", new ProxyHandler(
                    this.target,
                    outhttpproc,
                    httpexecutor));

            // Set up the HTTP service
            this.httpService = new HttpService(inhttpproc, reqistry);
        }

        @Override
        public void run() {
            System.out.println("Listening on port " + this.serversocket.getLocalPort());
            while (!Thread.interrupted()) {
                try {
                    final int bufsize = 8 * 1024;
                    // Set up incoming HTTP connection
                    final Socket insocket = this.serversocket.accept();
                    final DefaultBHttpServerConnection inconn = new DefaultBHttpServerConnection(bufsize);
                    System.out.println("Incoming connection from " + insocket.getInetAddress());
                    inconn.bind(insocket);

                    // Set up outgoing HTTP connection
                    final Socket outsocket = new Socket(this.target.getHostName(), this.target.getPort());
                    final DefaultBHttpClientConnection outconn = new DefaultBHttpClientConnection(bufsize);
                    outconn.bind(outsocket);
                    System.out.println("Outgoing connection to " + outsocket.getInetAddress());

                    // Start worker thread
                    final Thread t = new ProxyThread(this.httpService, inconn, outconn);
                    t.setDaemon(true);
                    t.start();
                } catch (final InterruptedIOException ex) {
                    break;
                } catch (final IOException e) {
                    System.err.println("I/O error initialising connection thread: "
                            + e.getMessage());
                    break;
                }
            }
        }
    }

    static class ProxyThread extends Thread {

        private final HttpService httpservice;
        private final HttpServerConnection inconn;
        private final HttpClientConnection outconn;

        public ProxyThread(
                final HttpService httpservice,
                final HttpServerConnection inconn,
                final HttpClientConnection outconn) {
            super();
            this.httpservice = httpservice;
            this.inconn = inconn;
            this.outconn = outconn;
        }

        @Override
        public void run() {
            System.out.println("New connection thread");
            final HttpContext context = new BasicHttpContext(null);

            // Bind connection objects to the execution context
            context.setAttribute(HTTP_IN_CONN, this.inconn);
            context.setAttribute(HTTP_OUT_CONN, this.outconn);

            try {
                while (!Thread.interrupted()) {
                    if (!this.inconn.isOpen()) {
                        this.outconn.close();
                        break;
                    }

                    this.httpservice.handleRequest(this.inconn, context);

                    final Boolean keepalive = (Boolean) context.getAttribute(HTTP_CONN_KEEPALIVE);
                    if (!Boolean.TRUE.equals(keepalive)) {
                        this.outconn.close();
                        this.inconn.close();
                        break;
                    }
                }
            } catch (final ConnectionClosedException ex) {
                System.err.println("Client closed connection");
            } catch (final IOException ex) {
                System.err.println("I/O error: " + ex.getMessage());
            } catch (final HttpException ex) {
                System.err.println("Unrecoverable HTTP protocol violation: " + ex.getMessage());
            } finally {
                try {
                    this.inconn.shutdown();
                } catch (final IOException ignore) {}
                try {
                    this.outconn.shutdown();
                } catch (final IOException ignore) {}
            }
        }

    }

}
