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

package org.apache.http.testserver;

import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.nio.DefaultNHttpClientConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.pool.BasicNIOConnFactory;
import org.apache.http.impl.nio.pool.BasicNIOConnPool;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.protocol.HttpAsyncRequester;
import org.apache.http.params.HttpCoreConfigBuilder;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpProcessor;
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
import org.junit.After;

/**
 * Base class for all HttpCore NIO tests
 *
 */
public abstract class HttpCoreNIOTestBase {

    protected HttpParams serverParams;
    protected HttpParams clientParams;
    protected HttpServerNio server;
    protected HttpClientNio client;
    protected HttpProcessor serverHttpProc;
    protected HttpProcessor clientHttpProc;
    protected BasicNIOConnPool connpool;
    protected HttpAsyncRequester executor;

    protected abstract NHttpConnectionFactory<DefaultNHttpServerConnection>
        createServerConnectionFactory() throws Exception;

    protected abstract NHttpConnectionFactory<DefaultNHttpClientConnection>
        createClientConnectionFactory() throws Exception;

    public void initServer() throws Exception {
        this.serverParams = new HttpCoreConfigBuilder()
            .setSocketTimeout(60000)
            .setSocketBufferSize(8 * 1024)
            .setTcpNoDelay(true)
            .setOriginServer("TEST-SERVER/1.1").build();
        this.server = new HttpServerNio(createServerConnectionFactory());
        this.server.setExceptionHandler(new SimpleIOReactorExceptionHandler());
        this.serverHttpProc = new ImmutableHttpProcessor(new HttpResponseInterceptor[] {
                new ResponseDate(),
                new ResponseServer(),
                new ResponseContent(),
                new ResponseConnControl()
        });
    }

    public void initClient() throws Exception {
        this.clientParams = new HttpCoreConfigBuilder()
            .setSocketTimeout(60000)
            .setConnectTimeout(60000)
            .setSocketBufferSize(8 * 1024)
            .setTcpNoDelay(true)
            .setUserAgent("TEST-CLIENT/1.1").build();
        this.client = new HttpClientNio(createClientConnectionFactory());
        this.client.setExceptionHandler(new SimpleIOReactorExceptionHandler());
        this.clientHttpProc = new ImmutableHttpProcessor(new HttpRequestInterceptor[] {
                new RequestContent(),
                new RequestTargetHost(),
                new RequestConnControl(),
                new RequestUserAgent(),
                new RequestExpectContinue()});
    }

    public void initConnPool() throws Exception {
        this.connpool = new BasicNIOConnPool(
                this.client.getIoReactor(),
                new BasicNIOConnFactory(createClientConnectionFactory()),
                this.clientParams);
        this.executor = new HttpAsyncRequester(
                this.clientHttpProc,
                DefaultConnectionReuseStrategy.INSTANCE,
                this.clientParams);
    }

    @After
    public void shutDownConnPool() throws Exception {
        if (this.connpool != null) {
            this.connpool.shutdown(2000);
            this.connpool = null;
        }
    }

    @After
    public void shutDownClient() throws Exception {
        if (this.client != null) {
            this.client.shutdown();
            this.client = null;
        }
    }

    @After
    public void shutDownServer() throws Exception {
        if (this.server != null) {
            this.server.shutdown();
            this.server = null;
        }
    }

}