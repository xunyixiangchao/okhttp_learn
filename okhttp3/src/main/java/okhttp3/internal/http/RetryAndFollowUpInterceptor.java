/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpRetryException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Address;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.connection.RouteException;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http2.ConnectionShutdownException;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

/**
 * todo 负责失败重试以及重定向
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * {@link IOException} if the call was canceled.
 */
public final class RetryAndFollowUpInterceptor implements Interceptor {
    /**
     * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects;
     * Firefox,
     * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
     */
    private static final int MAX_FOLLOW_UPS = 20;

    private final OkHttpClient client;
    private final boolean forWebSocket;
    private volatile StreamAllocation streamAllocation;
    private Object callStackTrace;
    private volatile boolean canceled;

    public RetryAndFollowUpInterceptor(OkHttpClient client, boolean forWebSocket) {
        this.client = client;
        this.forWebSocket = forWebSocket;
    }

    /**
     * Immediately closes the socket connection if it's currently held. Use this to interrupt an
     * in-flight request from any thread. It's the caller's responsibility to close the request body
     * and response body streams; otherwise resources may be leaked.
     *
     * <p>This method is safe to be called concurrently, but provides limited guarantees. If a
     * transport layer connection has been established (such as a HTTP/2 stream) that is terminated.
     * Otherwise if a socket connection is being established, that is terminated.
     */
    public void cancel() {
        canceled = true;
        StreamAllocation streamAllocation = this.streamAllocation;
        if (streamAllocation != null) streamAllocation.cancel();
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCallStackTrace(Object callStackTrace) {
        this.callStackTrace = callStackTrace;
    }

    public StreamAllocation streamAllocation() {
        return streamAllocation;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {

        Request request = chain.request();
        RealInterceptorChain realChain = (RealInterceptorChain) chain;
        Call call = realChain.call();
        EventListener eventListener = realChain.eventListener();

        /**
         * todo  管理类，维护了 与服务器的连接、数据流与请求三者的关系。真正使用的拦截器为 Connect
         * 其中Address 是什么？  代理与路由：
         * 1、使用Okhttpclient时候我们自己设置的代理服务器（proxy()配置） java Proxy 共三种Type： DIRECT（无代理）、HTTP
         * (完成TCP数据收发的代理，并处理HTTP)、SOCKS(完成TCP数据收发的代理)
         *
         * 2、通过代理选择器获得的所有代理(proxySelecor,默认获得系统所有代理)
         * 测试:代理选择器获得了一个代理：DIRECT（无代理），开启ss后获得一个代理:SOCKS
         *
         * 代理的作用为转发，发起请求是向代理发起，然后代理发给真实服务器，得到响应，再由代理转发给我们。
         * 在交给 StreamAllocation 后，会创建一个路由选择器(RouteSelector)。如果我们配置了自己的代理，就不会管proxyslector了。
         * 在连接时，先请求配置的DNS服务器，默认为：InetAddress.getAllByName(hostname)像网络商查询(如：电信) dns的作用为
         * 解析host为ip（寻址）
         *
         * 整个流程为:
         * 1、没有设置代理的情况下，直接与HTTP服务器建立TCP连接，然后进行HTTP请求/响应的交互。
         * 2、设置了SOCKS代理的情况下，创建Socket时，为其传入proxy（new Socket(proxy)），连接时(connect方法)还是以HTTP服务器为目标地址。
         * 3、设置了HTTP代理时，使用配置的dns解析代理服务器，与HTTP代理服务器建立TCP连接，
         *      首次发送代理服务器 "CONNECT 目标地址 HTTP/1.1" 的请求，同时包含Host 为目标地址
         *      连接成功后，后续就由代理为我们向目标地址转发http数据。
         */
        StreamAllocation streamAllocation = new StreamAllocation(client.connectionPool(),
                createAddress(request.url()), call, eventListener, callStackTrace);
        this.streamAllocation = streamAllocation;

        int followUpCount = 0;
        Response priorResponse = null;
        while (true) {
            if (canceled) {
                streamAllocation.release();
                throw new IOException("Canceled");
            }

            Response response;
            boolean releaseConnection = true;
            try {
                //todo 请求出现了异常，那么releaseConnection依旧为true。
                response = realChain.proceed(request, streamAllocation, null, null);
                releaseConnection = false;

            } catch (RouteException e) {
                //todo 路由异常，连接未成功，请求还没发出去
                //The attempt to connect via a route failed. The request will not have been sent.
                // todo: 重试
                if (!recover(e.getLastConnectException(), streamAllocation, false, request)) {
                    throw e.getLastConnectException();
                }
                releaseConnection = false;
                //重试
                continue;
            } catch (IOException e) {
                //todo 请求发出去了，但是和服务器通信失败了。(socket流正在读写数据的时候断开连接)
                // ConnectionShutdownException只对HTTP2存在。假定它就是false
                //An attempt to communicate with a server failed. The request may have been sent.
                boolean requestSendStarted = !(e instanceof ConnectionShutdownException);
                // todo: 重试
                if (!recover(e, streamAllocation, requestSendStarted, request)) throw e;
                releaseConnection = false;
                continue;
            } finally {
                // We're throwing an unchecked exception. Release any resources.
                //todo 不是前两种的失败，那直接关闭清理所有资源
                if (releaseConnection) {
                    streamAllocation.streamFailed(null);
                    streamAllocation.release();
                }
            }
            //todo 如果进过重试/重定向才成功的，则在本次响应中记录上次响应的情况
            //Attach the prior response if it exists. Such responses never have a body.
            if (priorResponse != null) {
                response = response.newBuilder()
                        .priorResponse(
                                priorResponse.newBuilder()
                                        .body(null)
                                        .build()
                        )
                        .build();
            }
            // todo: 重定向
            //todo 处理3和4xx的一些状态码，如301 302重定向
            Request followUp = followUpRequest(response, streamAllocation.route());
            if (followUp == null) {
                if (!forWebSocket) {
                    streamAllocation.release();
                }
                return response;
            }

            closeQuietly(response.body());

            //todo 限制最大 followup 次数为20次
            if (++followUpCount > MAX_FOLLOW_UPS) {
                streamAllocation.release();
                throw new ProtocolException("Too many follow-up requests: " + followUpCount);
            }

            if (followUp.body() instanceof UnrepeatableRequestBody) {
                streamAllocation.release();
                throw new HttpRetryException("Cannot retry streamed HTTP body", response.code());
            }
            //todo 判断是不是可以复用同一份连接
            if (!sameConnection(response, followUp.url())) {
                streamAllocation.release();
                streamAllocation = new StreamAllocation(client.connectionPool(),
                        createAddress(followUp.url()), call, eventListener, callStackTrace);
                this.streamAllocation = streamAllocation;
            } else if (streamAllocation.codec() != null) {
                throw new IllegalStateException("Closing the body of " + response
                        + " didn't close its backing stream. Bad interceptor?");
            }

            request = followUp;
            priorResponse = response;
        }
    }

    private Address createAddress(HttpUrl url) {
        SSLSocketFactory sslSocketFactory = null;
        HostnameVerifier hostnameVerifier = null;
        CertificatePinner certificatePinner = null;
        if (url.isHttps()) {
            sslSocketFactory = client.sslSocketFactory();
            hostnameVerifier = client.hostnameVerifier();
            certificatePinner = client.certificatePinner();
        }

        return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
                sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
                client.proxy(), client.protocols(), client.connectionSpecs(),
                client.proxySelector());
    }

    /**
     * Report and attempt to recover from a failure to communicate with a server. Returns true if
     * {@code e} is recoverable, or false if the failure is permanent. Requests with a body can only
     * be recovered if the body is buffered or if the failure occurred before the request has been
     * sent.
     */
    private boolean recover(IOException e, StreamAllocation streamAllocation,
                            boolean requestSendStarted, Request userRequest) {
        streamAllocation.streamFailed(e);

        //todo 1、在配置OkhttpClient是设置了不允许重试（默认允许），则一旦发生请求失败就不再重试
        //The application layer has forbidden retries.
        if (!client.retryOnConnectionFailure()) return false;

        //todo 2、由于requestSendStarted只在http2的io异常中为true，先不管http2
        //We can't send the request body again.
        if (requestSendStarted && userRequest.body() instanceof UnrepeatableRequestBody)
            return false;

        //todo 3、判断是不是属于重试的异常
        //This exception is fatal.
        if (!isRecoverable(e, requestSendStarted)) return false;

        //todo 4、不存在更多的路线
        //No more routes to attempt.
        if (!streamAllocation.hasMoreRoutes()) return false;

        // For failure recovery, use the same route selector with a new connection.
        return true;
    }

    private boolean isRecoverable(IOException e, boolean requestSendStarted) {
        // 1.是不是协议异常（code为204,205代表没有响应体，同时响应数据长度还大于0两都冲突，参照CallServerInterceptor中
        // if ((code == 204 || code == 205) && response.body().contentLength() > 0)）
        //：不重试
        if (e instanceof ProtocolException) {
            return false;
        }

        //2.socket超时异常 返回true:重试
        if (e instanceof InterruptedIOException) {
            return e instanceof SocketTimeoutException && !requestSendStarted;
        }

        //SSL证书不正确  可能证书格式损坏 有问题：不重试
        if (e instanceof SSLHandshakeException) {
            // If the problem was a CertificateException from the X509TrustManager,
            // do not retry.
            if (e.getCause() instanceof CertificateException) {
                return false;
            }
        }
        //SSL证书校验：不重试
        if (e instanceof SSLPeerUnverifiedException) {
            return false;
        }

        return true;
    }

    /**
     * Figures out the HTTP request to make in response to receiving {@code userResponse}. This will
     * either add authentication headers, follow redirects or handle a client request timeout. If a
     * follow-up is either unnecessary or not applicable, this returns null.
     */
    private Request followUpRequest(Response userResponse, Route route) throws IOException {
        if (userResponse == null) throw new IllegalStateException();
        int responseCode = userResponse.code();

        final String method = userResponse.request().method();
        switch (responseCode) {
            // 407 客户端使用了HTTP代理服务器，在请求头中添加 “Proxy-Authorization”，让代理服务器授权
            case HTTP_PROXY_AUTH:
                Proxy selectedProxy = route != null
                        ? route.proxy()
                        : client.proxy();
                if (selectedProxy.type() != Proxy.Type.HTTP) {
                    throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not " +
                            "using proxy");
                }
                //用户没有设置就返回null,重定向就结束了
                return client.proxyAuthenticator().authenticate(route, userResponse);
            // 401 需要身份验证 有些服务器接口需要验证使用者身份 在请求头中添加 “Authorization”
            case HTTP_UNAUTHORIZED:
                //类似407身份验证，设置authenticator()
                return client.authenticator().authenticate(route, userResponse);
            // 308 永久重定向
            // 307 临时重定向
            case HTTP_PERM_REDIRECT:
            case HTTP_TEMP_REDIRECT:
                // 如果请求方式不是GET或者HEAD，框架不会自动重定向请求
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    return null;
                }
                // 300 301 302 303
            case HTTP_MULT_CHOICE:
            case HTTP_MOVED_PERM:
            case HTTP_MOVED_TEMP:
            case HTTP_SEE_OTHER:
                // 如果用户不允许重定向，那就返回null
                if (!client.followRedirects()) return null;
                // 从响应头取出location
                String location = userResponse.header("Location");
                if (location == null) return null;
                // 根据location 配置新的请求 url
                HttpUrl url = userResponse.request().url().resolve(location);
                // 如果为null，说明协议有问题，取不出来HttpUrl，那就返回null，不进行重定向
                if (url == null) return null;
                // 如果重定向在http到https之间切换，需要检查用户是不是允许(默认允许)
                boolean sameScheme = url.scheme().equals(userResponse.request().url().scheme());
                if (!sameScheme && !client.followSslRedirects()) return null;


                Request.Builder requestBuilder = userResponse.request().newBuilder();
                /**
                 *  重定向请求中 只要不是 PROPFIND 请求，无论是POST还是其他的方法都要改为GET请求方式，
                 *  即只有 PROPFIND 请求才能有请求体
                 */
                //请求不是get与head
                if (HttpMethod.permitsRequestBody(method)) {
                    final boolean maintainBody = HttpMethod.redirectsWithBody(method);
                    // 除了 PROPFIND 请求之外都改成GET请求
                    if (HttpMethod.redirectsToGet(method)) {
                        requestBuilder.method("GET", null);
                    } else {
                        RequestBody requestBody = maintainBody ? userResponse.request().body() : null;
                        requestBuilder.method(method, requestBody);
                    }
                    // 不是 PROPFIND 的请求，把请求头中关于请求体的数据删掉
                    if (!maintainBody) {
                        requestBuilder.removeHeader("Transfer-Encoding");
                        requestBuilder.removeHeader("Content-Length");
                        requestBuilder.removeHeader("Content-Type");
                    }
                }

                // 在跨主机重定向时，删除身份验证请求头
                if (!sameConnection(userResponse, url)) {
                    requestBuilder.removeHeader("Authorization");
                }

                return requestBuilder.url(url).build();
            // 408 客户端请求超时
            case HTTP_CLIENT_TIMEOUT:
                // 408 算是连接失败了，所以判断用户是不是允许重试
                if (!client.retryOnConnectionFailure()) {
                    return null;
                }
                // UnrepeatableRequestBody实际并没发现有其他地方用到
                if (userResponse.request().body() instanceof UnrepeatableRequestBody) {
                    return null;
                }
                // 如果是本身这次的响应就是重新请求的产物同时上一次之所以重请求还是因为408，那我们这次不再重请求了
                if (userResponse.priorResponse() != null
                        && userResponse.priorResponse().code() == HTTP_CLIENT_TIMEOUT) {
                    return null;
                }
                // 如果服务器告诉我们了 Retry-After 多久后重试，那框架不管了。
                if (retryAfter(userResponse, 0) > 0) {
                    return null;
                }
                return userResponse.request();
            // 503 服务不可用 和408差不多，但是只在服务器告诉你 Retry-After：0（意思就是立即重试） 才重请求
            case HTTP_UNAVAILABLE:
                if (userResponse.priorResponse() != null
                        && userResponse.priorResponse().code() == HTTP_UNAVAILABLE) {
                    return null;
                }

                if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) {
                    return userResponse.request();
                }

                return null;
            default:
                return null;
        }
    }

    private int retryAfter(Response userResponse, int defaultDelay) {
        String header = userResponse.header("Retry-After");

        if (header == null) {
            return defaultDelay;
        }

        // https://tools.ietf.org/html/rfc7231#section-7.1.3
        // currently ignores a HTTP-date, and assumes any non int 0 is a delay
        if (header.matches("\\d+")) {
            return Integer.valueOf(header);
        }

        return Integer.MAX_VALUE;
    }

    /**
     * Returns true if an HTTP request for {@code followUp} can reuse the connection used by this
     * engine.
     */
    private boolean sameConnection(Response response, HttpUrl followUp) {
        HttpUrl url = response.request().url();
        return url.host().equals(followUp.host())
                && url.port() == followUp.port()
                && url.scheme().equals(followUp.scheme());
    }
}
