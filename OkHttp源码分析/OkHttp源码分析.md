# OkHttp源码分析

- OkHttp请求流程

- 高并发请求分发器与线程池

- 责任链模式请求与响应拦截

## OkHttp介绍

由Square公司贡献的一个处理网络请求的开源项目，是目前Android使用最广泛的网络框架，从Android4。4开始HttpURLConnection的底层实现采用的是OkHttp。

- 支持HTTP/2并允许对同一主机的所有请求共享一个套接字
- 通过连接池，减少了请求延迟
- 默认通过GZip压缩数据
- 响应缓存，以免了重复请求的网络
- 请求失败自动重试主机的其他ip，自动重定向
- ......

## 简单使用：

```java
OkHttpClient okHttpClient = new OkHttpClient();
Request request = new Request.Builder().url("http://www.baidu.com").build();
Call call = okHttpClient.newCall(request);
try {
    //同步请求
    Response execute = call.execute();
} catch (IOException e) {
    e.printStackTrace();
}
//异步请求
call.enqueue(new Callback() {
    @Override
    public void onFailure(Call call, IOException e) {

    }

    @Override
    public void onResponse(Call call, Response response) throws IOException {

    }
});
```

## 使用流程：



![使用流程](.\使用流程.png)

## 调用流程：

OkHttp请求过程中最少需要接触OkHttpClient、Request、Call、Response，但是框架内部进行大量的逻辑处理。

所有的逻辑大部分集中在拦截器中，但是在进入拦截器之前还需要依靠分发器来调配请求任务。

**分发器：**内部维护队列与线程池，完成请求调配；Dispatcher

**拦截器：**五大默认拦截器完成整个请求过程； Interceptors

## 分发器：

### 异步请求工作流程：

![1596634272799](.\异步请求工作.png)

1. Q：如何决定将请求放入ready还是running?
2. Q：从running移动到ready的条件是什么？
3. Q：分发器线程池的工作行为？

**Dispatcher中**

1.  A:  client.dispatcher().enqueue(new AsyncCall(responseCallback));

   ```java
   synchronized void enqueue(AsyncCall call) {
       //1.running队列数小于最大请求数64（正在请求的的数量是有限制的，自己配置分发器时可以修改）
       //2.同一域名正在请求的个数也是有限制的小于5
       //PS:最大同时请求数64，与同一台服务器请求数5
       if (runningAsyncCalls.size() < maxRequests && runningCallsForHost(call) < maxRequestsPerHost) {
           //添加到running队列
           runningAsyncCalls.add(call);
           //将runnable（call）提交到线程池当中
           executorService().execute(call);
       } else {
           //不符合上面请求就加入到等待队列
           readyAsyncCalls.add(call);
       }
   }
   ```

2.  A:client.dispatcher().finished(this);-> promoteCalls()移动队列

```java
private void promoteCalls() {
    //正在执行队列数
    if (runningAsyncCalls.size() >= maxRequests) return; // Already running max capacity.
    //等待队列数得不为空
    if (readyAsyncCalls.isEmpty()) return; // No ready calls to promote.

    for (Iterator<AsyncCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) {
        AsyncCall call = i.next();
        //如果拿到的等待请求host，在请求的列表中已经存在5个
        if (runningCallsForHost(call) < maxRequestsPerHost) {
            //等待移除
            i.remove();
            //加入running
            runningAsyncCalls.add(call);
            //加入线程池
            executorService().execute(call);
        }
        //判断正在执行队列数
        if (runningAsyncCalls.size() >= maxRequests) return; // Reached max capacity.
    }
}
```

3. A:ThreadPoolExecutor

![线程池](.\线程池.png)

当一个任务通过execute(Runnable)方法添加到线程池时：

- 线程数量小于corePoolSize，新建线程(核心)来处理被添加的任务；

- 线程数量大于等于 corePoolSize，存在空闲线程，使用空闲线程执行新任务；-
-  线程数量大于等于 corePoolSize，不存在空闲线程，新任务被添加到等待队列，添加成功则等待空闲线程，添加失败：
  - 线程数量小于maximumPoolSize，新建线程执行新任务；
  - 线程数量等于maximumPoolSize，拒绝此任务。 

```java
public synchronized ExecutorService executorService() {
    if (executorService == null) {
        //corePoolSize:核心线程数  0 不缓存线程，（0和1的表现是一样的）不用时就不占用线程，闲置60就会回收掉
        //maximumPoolSize最大线程数（包括核心）
        //keepAliveTime 缓存60秒
        //workQueue 队列
        //threadFactory 创建一个thread
        //PS:和Executors.newCachedThreadPool();创建的线程池一样
        executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher",
                false));
    }
    return executorService;
}
```

**SynchronousQueue** implements BlockingQueue  是个阻塞队列

PS:三种阻塞队列

`ArrayBlockingQueue`：基于数组的阻塞队列，初始化需要指定固定大小。

`LinkedBlockingQueue`：基于链表实现的阻塞队列，初始化可以指定大小，也可以不指定。

`SynchronousQueue` : 无容量的队列。

往队列中添加元素一定是失败的。

### OkHttp线程池的特点：

**OkHttp提交请求，一定是往队列里提交，往队列中添加是一定是失败的，马上新建线程（没有到最大线程数），**

**不需要等待。获得最大的并发量**

#### AsyncCall

继承NamedRunnable类实现Runnable接口

```java
public abstract class NamedRunnable implements Runnable {
    protected final String name;

    public NamedRunnable(String format, Object... args) {
        this.name = Util.format(format, args);
    }
	/**
     * run方法其实调用的AsyncCall的execute()
     */
    @Override
    public final void run() {
        String oldName = Thread.currentThread().getName();
        Thread.currentThread().setName(name);
        try {
            execute();
        } finally {
            Thread.currentThread().setName(oldName);
        }
    }

    protected abstract void execute();
}
```

### 同步请求

加入队列，执行完移除队列

```java
synchronized void executed(RealCall call) {
    //同步直接加入running队列，这里的running是同步队列不是异步的
    runningSyncCalls.add(call);
}
```

## 拦截器：

默认五大拦截器：（责任链模式）
**重定向与重试，**
**Header、Body处理，**
**缓存处理，**
**连接处理，**
**服务器通讯**
![五大拦截器](.\五大拦截器.png)

请求是顺序的，响应是逆序的

### 获取响应：

无论同步还是异常都是通过getResponseWithInterceptorChain 获得请求结果：Response

```java
Response getResponseWithInterceptorChain() throws IOException {
    // 拦截器集合
    List<Interceptor> interceptors = new ArrayList<>();
    interceptors.addAll(client.interceptors());
    //重定向与重试
    interceptors.add(retryAndFollowUpInterceptor);
    //Header,Body处理
    interceptors.add(new BridgeInterceptor(client.cookieJar()));
    //缓存处理
    interceptors.add(new CacheInterceptor(client.internalCache()));
    //连接处理
    interceptors.add(new ConnectInterceptor(client));
    if (!forWebSocket) {
        interceptors.addAll(client.networkInterceptors());
    }
    //服务器通讯
    interceptors.add(new CallServerInterceptor(forWebSocket));

    Interceptor.Chain chain = new RealInterceptorChain(interceptors, null, null, null, 0,
            originalRequest, this, eventListener, client.connectTimeoutMillis(),
            client.readTimeoutMillis(), client.writeTimeoutMillis());

    return chain.proceed(originalRequest);
}
```

### 责任链模式：

**（一排，最后一位往前一个个传纸条[请求]，传到第一个又一个个往后传[响应]）**

为请求创建了一个接收者对象的链，在处理请求的时候执行过滤(各司其职)。

责任链上的处理者负责处理请求，客户只需要将请求发送到责任链即可，无须关心请求的处理细节和请求的传递，所以职责链将请求的发送者和请求的处理者解耦了。

### 拦截器责任链：

![1596641859673](.\拦截器责任链.png)

### 五大拦截器功能：

1. 重试拦截器在交出(交给下一个拦截器)之前，负责判断用户是否取消了请求；在获得了结果之后，会根据响应码判断是否需要重定向，如果满足条件那么就会重启执行所有拦截器。

2. 桥接拦截器在交出之前，负责将HTTP协议必备的请求头加入其中(如：Host)并添加一些默认的行为(如：GZIP压缩)；在获得了结果后，调用保存cookie接口并解析GZIP数据。

3. 缓存拦截器顾名思义，交出之前读取并判断是否使用缓存；获得结果后判断是否缓存。

4. 连接拦截器在交出之前，负责找到或者新建一个连接，并获得对应的socket流；在获得结果后不进行额外的处理。

5. 请求服务器拦截器进行真正的与服务器的通信，向服务器发送数据，解析读取的响应数据。

### 拦截器详情：

#### 一、重试及重定向拦截器

第一个拦截器:`RetryAndFollowUpInterceptor`，主要就是完成两件事情：重试与重定向。



##### 重试

场景：请求超时；域名解析后多个IP，如果一个IP失败了，重试其他IP

![重试](.\retry.png)

**设置是否允许重试**

```java
//设置是否允许重试 默认是允许
new OkHttpClient().newBuilder().retryOnConnectionFailure(true);
```

在`RetryAndFollowUpInterceptor`中失败时，进入recover方法

```java
try {
    //todo 请求出现了异常，那么releaseConnection依旧为true。
    response = realChain.proceed(request, streamAllocation, null, null);
    releaseConnection = false;
} catch (RouteException e) {
    //todo 路由异常，连接未成功，请求还没发出去
    //The attempt to connect via a route failed. The request will not have been sent.
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
    if (!recover(e, streamAllocation, requestSendStarted, request)) throw e;
    releaseConnection = false;
    continue;
}
```

在recover中 获取是否允许重试，如果不允许就抛异常，结束。

```java
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

        //todo 4、是不是存在更多的路线 （多个ip，多个代理）
        //No more routes to attempt.
        if (!streamAllocation.hasMoreRoutes()) return false;

        // For failure recovery, use the same route selector with a new connection.
        return true;
    }
```

**重试的异常包括哪些：**

在 `todo 3`的isRecoverable方法中

```java
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

    //3.//SSL证书不正确  可能证书格式损坏 有问题：不重试
    if (e instanceof SSLHandshakeException) {
        // If the problem was a CertificateException from the X509TrustManager,
        // do not retry.
        if (e.getCause() instanceof CertificateException) {
            return false;
        }
    }
    //4.SSL证书校验 ：不重试
    if (e instanceof SSLPeerUnverifiedException) {
        return false;
    }

    return true;
}
```

所以在socket超时异常时会进行重试，其他异常不再进行重试



##### 重定向

场景：30X，资源改变

![image-20200806144545891](.\重定向.png)

**最大重写向次数为：20**

```java
//todo 处理3和4xx的一些状态码，如301 302重定向
Request followUp = followUpRequest(response, streamAllocation.route());
if (followUp == null) {
    if (!forWebSocket) {
        streamAllocation.release();
    }
    return response;
}
```

```java
Request followUpRequest(Response userResponse, Route route)
```


**在followUpRequest中响应码**

-------------------------------------

407:

```java
//407 身份校验
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
```

用户设置身份校验

```java
//设置身份校验的代理
new OkHttpClient.Builder().proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(
        "localhost", 8080
)))
//设置身份校验(默认不设置这个）
.proxyAuthenticator(new Authenticator() {
    @Nullable
    @Override
    public Request authenticate(Route route, Response response) throws IOException {
        //参照Authenticator接口注释
        return response.request().newBuilder()
                .header("Proxy-Authorization", Credentials.basic("用户名","密码"))
                .build();
    }
}).build();
```
401:

```java
// 401 需要身份验证 有些服务器接口需要验证使用者身份 在请求头中添加 “Authorization”
case HTTP_UNAUTHORIZED:
    //类似407身份验证，设置authenticator()
    return client.authenticator().authenticate(route, userResponse);
```

重定向：

```java
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
```

408请求超时：

```java
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

```

503:

// 503 服务不可用 和408差不多，但是只在服务器告诉你 Retry-After：0（意思就是立即重试） 才重请求

```java
case HTTP_UNAVAILABLE:
    if (userResponse.priorResponse() != null
            && userResponse.priorResponse().code() == HTTP_UNAVAILABLE) {
        return null;
    }
if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) {
    return userResponse.request();
}

return null;
```
-------------------------------------

重定向总结：服务器返回300 301 302 303需要重定向，会获取返回头的Location中的新地址。

如果此方法followUpRequest返回空，那就表示不需要再重定向了，直接返回响应；但是如果返回非空，那就要重新请求返回的`Request`，但是需要注意的是，我们的`followup`在拦截器中定义的最大次数为**20**次。

##### 总结

本拦截器是整个责任链中的第一个，这意味着它会是首次接触到`Request`与最后接收到`Response`的角色，在这个拦截器中主要功能就是判断是否需要重试与重定向。

重试的前提是出现了`RouteException`或者`IOException`。一但在后续的拦截器执行过程中出现这两个异常，就会通过`recover`方法进行判断是否进行连接重试。

重定向发生在重试的判定之后，如果不满足重试的条件，还需要进一步调用`followUpRequest`根据`Response` 的响应码(当然，如果直接请求失败，`Response`都不存在就会抛出异常)。`followup`最大发生20次。



#### 二、桥接拦截器

两大作用：补全请求头，处理响应（保存cookie，GzipSource）

补全请求与响应后处理

| 请求头                               | 说明                                               |
| ------------------------------------ | -------------------------------------------------- |
| `Content-Type`                       | 请求体类型,如：`application/x-www-form-urlencoded` |
| `Content-Length`/`Transfer-Encoding` | 请求体解析方式                                     |
| `Host`                               | 请求的主机站点                                     |
| `Connection: Keep-Alive`             | 保持长连接                                         |
| `Accept-Encoding: gzip`              | 接受响应支持gzip压缩                               |
| `Cookie`                             | cookie身份辨别                                     |
| `User-Agent`                         | 请求的用户信息，如:操作系统、浏览器等              |

得到响应：
	1、读取Set-Cookie响应头并调用接口告知用户，在下次请求则会读取对应的数据设置进入请求头，默认CookieJar无实现；

​	2、响应头Content-Encoding为gzip，使用GzipSource包装便于解析。

##### 总结

桥接拦截器的执行逻辑主要就是以下几点

对用户构建的`Request`进行添加或者删除相关头部信息，以转化成能够真正进行网络请求的`Request`
将符合网络请求规范的Request交给下一个拦截器处理，并获取`Response`
如果响应体经过了GZIP压缩，那就需要解压，再构建成用户可用的`Response`并返回



#### 三、缓存拦截器

`CacheInterceptor`，在发出请求前，判断是否命中缓存。如果命中则可以不请求，直接使用缓存的响应。 (只会存在Get请求的缓存)

步骤为:

1、从缓存中获得对应请求的响应缓存

2、创建`CacheStrategy` ,创建时会判断是否能够使用缓存，在`CacheStrategy` 中存在两个成员:`networkRequest`与`cacheResponse`。他们的组合如下:

| networkRequest | cacheResponse | 说明                                                    |
| -------------- | ------------- | ------------------------------------------------------- |
| Null           | Not Null      | 直接使用缓存                                            |
| Not Null       | Null          | 向服务器发起请求                                        |
| Null           | Null          | 直接gg，okhttp直接返回504                               |
| Not Null       | Not Null      | 发起请求，若得到响应为304(无修改)，则更新缓存响应并返回 |

即：networkRequest存在则优先发起网络请求，否则使用cacheResponse缓存，若都不存在则请求失败！



3、交给下一个责任链继续处理

4、后续工作，返回304则用缓存的响应；否则使用网络响应并缓存本次响应（只缓存Get请求的响应）



缓存拦截器的工作说起来比较简单，但是具体的实现，需要处理的内容很多。在缓存拦截器中判断是否可以使用缓存，或是请求服务器都是通过`CacheStrategy`判断。

##### 缓存策略

```java
//todo 缓存策略:根据各种条件(请求头)组成 请求与缓存
CacheStrategy strategy =
        new CacheStrategy.Factory(now, chain.request(), cacheCandidate).get();
```

```java
public CacheStrategy get() {
    CacheStrategy candidate = getCandidate();
    //todo 如果可以使用缓存，那networkRequest必定为null；指定了只使用缓存但是networkRequest又不为null，冲突。那就gg(拦截器返回504)
    if (candidate.networkRequest != null && request.cacheControl().onlyIfCached()) {
        // We're forbidden from using the network and the cache is insufficient.
        return new CacheStrategy(null, null);
    }

    return candidate;
}
```

![image-20200807103702763](.\缓存策略-缓存检测.png)

##### 流程：

1. 没有缓存，就进行网络请求

2. 如果是Https请求，缓存中没有保存握手信息，发起网络请求

3. 通过响应码以及头部缓存控制字段判断响应能不能缓存，不能缓存那就进行网络请求（isCacheable方法）：不允许用

4. 如果请求包含：CacheControl:no-cache 需要与服务器验证缓存有效性（用户配置不进行缓存）：不想用

5. 如果缓存响应中存在 Cache-Control:immutable 响应内容将一直不会改变,可以使用缓存

6. 响应的缓存有效期

   这一步为进一步根据缓存响应中的一些信息判定缓存是否处于有效期内。如果满足：

	> **缓存存活时间 < 缓存新鲜度 - 缓存最小新鲜度 + 过期后继续使用时长**

	代表可以使用缓存。其中新鲜度可以理解为有效时间，而这里的 **"缓存新鲜度-缓存最小新鲜度"** 就代表了缓存真正有效的时间。

7. 缓存过期处理

   如果继续执行，表示缓存已经过期无法使用。此时我们判定缓存的响应中如果存在`Etag`，则使用`If-None-Match`交给服务器进行验证；如果存在`Last-Modified`或者`Data`，则使用`If-Modified-Since`交给服务器验证。服务器如果无修改则会返回304，这时候注意：

   **由于是缓存过期而发起的请求(与第4个判断用户的主动设置不同)，如果服务器返回304，那框架会自动更新缓存，所以此时`CacheStrategy`既包含`networkRequest`也包含`cacheResponse`**

##### 详细流程：

```java
private CacheStrategy getCandidate() {
    // No cached response.
    //todo 1、没有缓存,进行网络请求
    if (cacheResponse == null) {
        return new CacheStrategy(request, null);
    }
    //todo 2、https请求，但是没有握手信息,进行网络请求
    // OkHttp会保存ssl握手信息 handshake,如果这次发起了https请求，
    // 但是缓存的响应信息中没有握手信息，发起网络请求
    //Drop the cached response if it's missing a required handshake.
    if (request.isHttps() && cacheResponse.handshake() == null) {
        return new CacheStrategy(request, null);
    }

    //todo 3、主要是通过响应码以及头部缓存控制字段判断响应能不能缓存，不能缓存那就进行网络请求
    //If this response shouldn't have been stored, it should never be used
    //as a response source. This check should be redundant as long as the
    //persistence store is well-behaved and the rules are constant.
    if (!isCacheable(cacheResponse, request)) {
        return new CacheStrategy(request, null);
    }

    CacheControl requestCaching = request.cacheControl();
    //todo 4、如果 请求包含：CacheControl:no-cache 需要与服务器验证缓存有效性
    // 或者请求头包含 If-Modified-Since：时间 值为lastModified或者data 如果服务器没有在该头部指定的时间之后修改了请求的数据，服务器返回304(无修改)
    // 或者请求头包含 If-None-Match：值就是Etag（资源标记）服务器将其与存在服务端的Etag值进行比较；如果匹配，返回304
    // 请求头中只要存在三者中任意一个，进行网络请求
    if (requestCaching.noCache() || hasConditions(request)) {
        return new CacheStrategy(request, null);
    }

    //todo 5、如果缓存响应中存在 Cache-Control:immutable 响应内容将一直不会改变,可以使用缓存
    CacheControl responseCaching = cacheResponse.cacheControl();
    if (responseCaching.immutable()) {
        return new CacheStrategy(null, cacheResponse);
    }

    //todo 6、根据 缓存响应的 控制缓存的响应头 判断是否允许使用缓存
    // 6.1、获得缓存的响应从创建到现在的时间
    long ageMillis = cacheResponseAge();
    //todo
    // 6.2、获取这个响应有效缓存的时长
    long freshMillis = computeFreshnessLifetime();
    if (requestCaching.maxAgeSeconds() != -1) {
        //todo 如果请求中指定了 max-age 表示指定了能拿的缓存有效时长，就需要综合响应有效缓存时长与请求能拿缓存的时长，获得最小的能够使用响应缓存的时长
        freshMillis = Math.min(freshMillis,
                SECONDS.toMillis(requestCaching.maxAgeSeconds()));
    }
    //todo
    // 6.3 请求包含  Cache-Control:min-fresh=[秒]  能够使用还未过指定时间的缓存 （请求认为的缓存有效时间）
    long minFreshMillis = 0;
    if (requestCaching.minFreshSeconds() != -1) {
        minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds());
    }

    //todo
    // 6.4
    //  6.4.1、Cache-Control:must-revalidate 可缓存但必须再向源服务器进行确认
    //  6.4.2、Cache-Control:max-stale=[秒] 缓存过期后还能使用指定的时长  如果未指定多少秒，则表示无论过期多长时间都可以；如果指定了，则只要是指定时间内就能使用缓存
    // 前者会忽略后者，所以判断了不必须向服务器确认，再获得请求头中的max-stale
    long maxStaleMillis = 0;
    if (!responseCaching.mustRevalidate() && requestCaching.maxStaleSeconds() != -1) {
        maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds());
    }

    //todo
    // 6.5 不需要与服务器验证有效性 && 响应存在的时间+请求认为的缓存有效时间 小于 缓存有效时长+过期后还可以使用的时间
    // 允许使用缓存
    if (!responseCaching.noCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
        Response.Builder builder = cacheResponse.newBuilder();
        //todo 如果已过期，但未超过 过期后继续使用时长，那还可以继续使用，只用添加相应的头部字段
        if (ageMillis + minFreshMillis >= freshMillis) {
            builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"");
        }
        //todo 如果缓存已超过一天并且响应中没有设置过期时间也需要添加警告
        long oneDayMillis = 24 * 60 * 60 * 1000L;
        if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
            builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
        }
        return new CacheStrategy(null, builder.build());
    }

    // Find a condition to add to the request. If the condition is satisfied, the
    // response body
    // will not be transmitted.
    //todo 7、缓存过期了
    String conditionName;
    String conditionValue;
    if (etag != null) {
        conditionName = "If-None-Match";
        conditionValue = etag;
    } else if (lastModified != null) {
        conditionName = "If-Modified-Since";
        conditionValue = lastModifiedString;
    } else if (servedDate != null) {
        conditionName = "If-Modified-Since";
        conditionValue = servedDateString;
    } else {
        return new CacheStrategy(request, null); // No condition! Make a regular request.
    }
    //todo 如果设置了 If-None-Match/If-Modified-Since 服务器是可能返回304(无修改)的,使用缓存的响应体
    Headers.Builder conditionalRequestHeaders = request.headers().newBuilder();
    Internal.instance.addLenient(conditionalRequestHeaders, conditionName, conditionValue);

    Request conditionalRequest = request.newBuilder()
            .headers(conditionalRequestHeaders.build())
            .build();
    return new CacheStrategy(conditionalRequest, cacheResponse);
}
```

##### **PS：请求头与响应头**

| 响应头        | 说明                                                     | 例子                                         |
| ------------- | -------------------------------------------------------- | -------------------------------------------- |
| Date          | 消息发送的时间                                           | Date: Sat, 18 Nov 2028 06:17:41 GMT          |
| Expires       | 资源过期的时间                                           | Expires: Sat, 18 Nov 2028 06:17:41 GMT       |
| Last-Modified | 资源最后修改时间                                         | Last-Modified: Fri, 22 Jul 2016 02:57:17 GMT |
| ETag          | 资源在服务器的唯一标识                                   | ETag: "16df0-5383097a03d40"                  |
| Age           | 服务器用缓存响应请求，该缓存从产生到现在经过多长时间(秒) | Age: 3825683                                 |
| Cache-Control | -                                                        | -                                            |

| 请求头              | 说明                                                     | 例子                                             |
| ------------------- | -------------------------------------------------------- | ------------------------------------------------ |
| `If-Modified-Since` | 服务器没有在指定的时间后修改请求对应资源,返回304(无修改) | If-Modified-Since: Fri, 22 Jul 2016 02:57:17 GMT |
| `If-None-Match`     | 服务器将其与请求对应资源的`Etag`值进行比较，匹配返回304  | If-None-Match: "16df0-5383097a03d40"             |
| `Cache-Control`     | -                                                        | -                                                |

其中`Cache-Control`可以在请求头存在，也能在响应头存在，对应的value可以设置多种组合：

1. `max-age=[秒]` ：资源最大有效时间;
2. `public` ：表明该资源可以被任何用户缓存，比如客户端，代理服务器等都可以缓存资源;
3. `private`：表明该资源只能被单个用户缓存，默认是private。
4. `no-store`：资源不允许被缓存
5. `no-cache`：(请求)不使用缓存
6. `immutable`：(响应)资源不会改变
7.  ` min-fresh=[秒]`：(请求)缓存最小新鲜度(用户认为这个缓存有效的时长)
8. `must-revalidate`：(响应)不允许使用过期缓存
9. `max-stale=[秒]`：(请求)缓存过期后多久内仍然有效

> 假设存在max-age=100，min-fresh=20。这代表了用户认为这个缓存的响应，从服务器创建响应 到 能够缓存使用的时间为100-20=80s。但是如果max-stale=100。这代表了缓存有效时间80s过后，仍然允许使用100s，可以看成缓存有效时长为180s。



#### 四、连接拦截器

##### 连接流程：

![image-20200807134938354](.\连接流程.png)

`ConnectInterceptor`，打开与目标服务器的连接，并执行下一个拦截器。它简短的可以直接完整贴在这里：

```java
public final class ConnectInterceptor implements Interceptor {
  public final OkHttpClient client;

  public ConnectInterceptor(OkHttpClient client) {
    this.client = client;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    Request request = realChain.request();
    StreamAllocation streamAllocation = realChain.streamAllocation();

    // We need the network to satisfy this request. Possibly for validating a conditional GET.
    boolean doExtensiveHealthChecks = !request.method().equals("GET");
    HttpCodec httpCodec = streamAllocation.newStream(client, chain, doExtensiveHealthChecks);
    RealConnection connection = streamAllocation.connection();

    return realChain.proceed(request, streamAllocation, httpCodec, connection);
  }
}
```

 首先我们看到的`StreamAllocation`这个对象是在第一个拦截器：重定向拦截器创建的，但是真正使用的地方却在这里。

*"当一个请求发出，需要建立连接，连接建立后需要使用流用来读写数据"*；而这个StreamAllocation就是协调请求、连接与数据流三者之间的关系，它负责为一次请求寻找连接，然后获得流来实现网络通信。

这里使用的`newStream`方法实际上就是去查找或者建立一个与请求主机有效的连接，返回的`HttpCodec`中包含了输入输出流，并且封装了对HTTP请求报文的编码与解码，直接使用它就能够与请求主机完成HTTP通信。

```java
public HttpCodec newStream(
        OkHttpClient client, Interceptor.Chain chain, boolean doExtensiveHealthChecks) {
    int connectTimeout = chain.connectTimeoutMillis();
    int readTimeout = chain.readTimeoutMillis();
    int writeTimeout = chain.writeTimeoutMillis();
    int pingIntervalMillis = client.pingIntervalMillis();
    boolean connectionRetryEnabled = client.retryOnConnectionFailure();

    try {
        //todo  找到一个健康的连接
        RealConnection resultConnection = findHealthyConnection(connectTimeout, readTimeout,
                writeTimeout, pingIntervalMillis, connectionRetryEnabled,
                doExtensiveHealthChecks);
        //todo 利用连接实例化流HttpCodec对象，如果是HTTP/2返回Http2Codec，否则返回Http1Codec
        HttpCodec resultCodec = resultConnection.newCodec(client, chain, this);

        synchronized (connectionPool) {
            codec = resultCodec;
            return resultCodec;
        }
    } catch (IOException e) {
        throw new RouteException(e);
    }
}
```

**`StreamAllocation`中简单来说就是维护连接：`RealConnection`——封装了Socket与一个Socket连接池。可复用的`RealConnection`**

findHealthyConnection方法中

```java
private RealConnection findHealthyConnection(int connectTimeout, int readTimeout,
                                             int writeTimeout, int pingIntervalMillis,
                                             boolean connectionRetryEnabled,
                                             boolean doExtensiveHealthChecks) throws IOException {
    while (true) {
        //todo 找到一个连接
        RealConnection candidate = findConnection(connectTimeout, readTimeout, writeTimeout,
                pingIntervalMillis, connectionRetryEnabled);

        //todo 如果这个连接是新建立的，那肯定是健康的，直接返回
        //If this is a brand new connection, we can skip the extensive health checks.
        synchronized (connectionPool) {
            if (candidate.successCount == 0) {
                return candidate;
            }
        }

        //todo 如果不是新创建的，需要检查是否健康
        //Do a (potentially slow) check to confirm that the pooled connection is still good.
      // If it
        // isn't, take it out of the pool and start again.
        if (!candidate.isHealthy(doExtensiveHealthChecks)) {
            //todo 不健康 关闭连接，释放Socket,从连接池移除
            // 继续下次寻找连接操作
            noNewStreams();
            continue;
        }

        return candidate;
    }
}
```

findConnection方法中的

- 尝试从连接池获取连接，如果有可复用的连接,会给第三个参数 this的connection赋值

```java
Internal.instance.get(connectionPool, address, this, null);
```

调到了（ConnectionPool）connectionPool.get方法

```java
@Nullable RealConnection get(Address address, StreamAllocation streamAllocation, Route route) {
  assert (Thread.holdsLock(this));
  for (RealConnection connection : connections) {
    if (connection.isEligible(address, route)) {
      streamAllocation.acquire(connection, true);
      return connection;
    }
  }
  return null;
}
```

isEligible判断是否能够复用

- 使用http1.1就不能用
- 如果地址不同就不能复用（Address.equalsNonHost）DNS、代理、SSL证书、服务器域名、端口
- 都相同那就可以复用

```java
public boolean isEligible(Address address, @Nullable Route route) {
    // If this connection is not accepting new streams, we're done.
    // TODO: 实际上就是在使用http1.1就不能用
    if (allocations.size() >= allocationLimit || noNewStreams) return false;

    // If the non-host fields of the address don't overlap, we're done.
    // TODO: 如果地址不同就不能复用（Address.equalsNonHost）DNS、代理、SSL证书、服务器域名、端口（域名没有判断，所以下面马上判断）
    if (!Internal.instance.equalsNonHost(this.route.address(), address)) return false;

    // If the host exactly matches, we're done: this connection can carry the address.
    //todo: 都相同那就可以复用了
    if (address.url().host().equals(this.route().address().url().host())) {
        return true; // This connection is a perfect match.
    }

    // At this point we don't have a hostname match. But we still be able to carry the
  // request if
    // our connection coalescing requirements are met. See also:
    // https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
    // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/

    // 1. This connection must be HTTP/2.
    if (http2Connection == null) return false;

    // 2. The routes must share an IP address. This requires us to have a DNS address for both
    // hosts, which only happens after route planning. We can't coalesce connections that use a
    // proxy, since proxies don't tell us the origin server's IP address.
    if (route == null) return false;
    if (route.proxy().type() != Proxy.Type.DIRECT) return false;
    if (this.route.proxy().type() != Proxy.Type.DIRECT) return false;
    if (!this.route.socketAddress().equals(route.socketAddress())) return false;

    // 3. This connection's server certificate's must cover the new host.
    if (route.address().hostnameVerifier() != OkHostnameVerifier.INSTANCE) return false;
    if (!supportsUrl(address.url())) return false;

    // 4. Certificate pinning must match the host.
    try {
        address.certificatePinner().check(address.url().host(), handshake().peerCertificates());
    } catch (SSLPeerUnverifiedException e) {
        return false;
    }

    return true; // The caller's address can be carried by this connection.
}
```

没找到，必须新建一个连接了

```java
 if (!foundPooledConnection) {
        if (selectedRoute == null) {
            selectedRoute = routeSelection.next();
        }

        // Create a connection and assign it to this allocation immediately. This makes
      // it possible
        // for an asynchronous cancel() to interrupt the handshake we're about to do.
        route = selectedRoute;
        refusedStreamCount = 0;
        result = new RealConnection(connectionPool, selectedRoute);
        acquire(result, false);
    }
}
```

##### 连接池清理：

![image-20200807135800214](.\连接池清理.png)

findConnection方法中：

```java
//todo 将新创建的连接放到连接池中
Internal.instance.put(connectionPool, result);
```

调的是ConnectionPool.put方法

```java
void put(RealConnection connection) {
  assert (Thread.holdsLock(this));
  if (!cleanupRunning) {
    cleanupRunning = true;
    //启动清理
    executor.execute(cleanupRunnable);
  }
  connections.add(connection);
}
```

```java
private final Runnable cleanupRunnable = new Runnable() {
  @Override public void run() {
    while (true) {
      //todo:最快多久后需要清理
      long waitNanos = cleanup(System.nanoTime());
      if (waitNanos == -1) return;
      if (waitNanos > 0) {
        //todo:因为等待是纳秒级，wait方法可以接收纳秒级控制，但是把毫秒与纳秒分开
        long waitMillis = waitNanos / 1000000L;
        waitNanos -= (waitMillis * 1000000L);
        synchronized (ConnectionPool.this) {
          try {
            //todo:参数多一个纳秒，控制更加精确
            ConnectionPool.this.wait(waitMillis, (int) waitNanos);
          } catch (InterruptedException ignored) {
          }
        }
      }
    }
  }
};
```

```java
long cleanup(long now) {
  int inUseConnectionCount = 0;
  int idleConnectionCount = 0;
  RealConnection longestIdleConnection = null;
  long longestIdleDurationNs = Long.MIN_VALUE;

  // Find either a connection to evict, or the time that the next eviction is due.
  synchronized (this) {
    for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
      RealConnection connection = i.next();

      // If the connection is in use, keep searching.
      if (pruneAndGetAllocationCount(connection, now) > 0) {
        inUseConnectionCount++;
        continue;
      }

      idleConnectionCount++;
      // TODO: 获得这个连接闲置多久
      // If the connection is ready to be evicted, we're done.
      long idleDurationNs = now - connection.idleAtNanos;
      if (idleDurationNs > longestIdleDurationNs) {
        longestIdleDurationNs = idleDurationNs;
        longestIdleConnection = connection;
      }
    }
    //超过保活时间（5分钟）或者池内数量超过了5个，马上移除，然后返回0，表示不等待，马上再次检查
    if (longestIdleDurationNs >= this.keepAliveDurationNs
        || idleConnectionCount > this.maxIdleConnections) {
      // We've found a connection to evict. Remove it from the list, then close it below (outside
      // of the synchronized block).
      connections.remove(longestIdleConnection);
    } else if (idleConnectionCount > 0) {
      // A connection will be ready to evict soon.
      // TODO: 池内存在闲置连接，就等待，保活时间（5分钟）-最长闲置时间=还能闲置多久 再检查
      return keepAliveDurationNs - longestIdleDurationNs;
    } else if (inUseConnectionCount > 0) {
      // All connections are in use. It'll be at least the keep alive duration 'til we run again.
      // TODO: 有使用中的连接就等待5分钟，再检查
      return keepAliveDurationNs;
    } else {
      // No connections, idle or in use.
      // TODO: 都不满足，可能池内没任何连接，直接停止清理（put后再次启用）
      cleanupRunning = false;
      return -1;
    }
  }
```

##### 代理连接：

![image-20200807135905696](.\代理连接.png)

findConnection中

```java
//todo 实际上就是创建socket连接，但是要注意的是如果存在http代理的情况
result.connect(connectTimeout, readTimeout, writeTimeout, pingIntervalMillis,
        connectionRetryEnabled, call, eventListener);
```

RealConnection.connect

```java
if (route.requiresTunnel()) {
    //todo http隧道代理
    connectTunnel(connectTimeout, readTimeout, writeTimeout, call, eventListener);
    if (rawSocket == null) {
        // We were unable to connect the tunnel but properly closed down our
      // resources.
        break;
    }
} else {
    //todo 创建socket连接
    connectSocket(connectTimeout, readTimeout, call, eventListener);
}
```

有http代理先设置代理头，最终都用调用connectSocket方法

```java
private void connectTunnel(int connectTimeout, int readTimeout, int writeTimeout, Call call,
                           EventListener eventListener) throws IOException {
    Request tunnelRequest = createTunnelRequest();
    HttpUrl url = tunnelRequest.url();
    for (int i = 0; i < MAX_TUNNEL_ATTEMPTS; i++) {
        connectSocket(connectTimeout, readTimeout, call, eventListener);
        tunnelRequest = createTunnel(readTimeout, writeTimeout, tunnelRequest, url);

        if (tunnelRequest == null) break; // Tunnel successfully created.

        // The proxy decided to close the connection after an auth challenge. We need to
      // create a new
        // connection, but this time with the auth credentials.
        closeQuietly(rawSocket);
        rawSocket = null;
        sink = null;
        source = null;
        eventListener.connectEnd(call, route.socketAddress(), route.proxy(), null);
    }
}
```

```java
private Request createTunnelRequest() {
    return new Request.Builder()
            .url(route.address().url())
            .header("Host", Util.hostHeader(route.address().url(), true))
            .header("Proxy-Connection", "Keep-Alive") // For HTTP/1.0 proxies like Squid.
            .header("User-Agent", Version.userAgent())
            .build();
}
```

```java
/**
 * todo:创建socket连接
 * Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket.
 */
private void connectSocket(int connectTimeout, int readTimeout, Call call,
                           EventListener eventListener) throws IOException {
    Proxy proxy = route.proxy();
    Address address = route.address();
    //todo:没有代理直接new一个Socket（），有代理就创建一个带代理参数的socket
    rawSocket = proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP
            ? address.socketFactory().createSocket()
            : new Socket(proxy);

    eventListener.connectStart(call, route.socketAddress(), proxy);
    rawSocket.setSoTimeout(readTimeout);
    try {
        // TODO: socket.connect
        Platform.get().connectSocket(rawSocket, route.socketAddress(), connectTimeout);
    } catch (ConnectException e) {
        ConnectException ce =
                new ConnectException("Failed to connect to " + route.socketAddress());
        ce.initCause(e);
        throw ce;
    }

    // The following try/catch block is a pseudo hacky way to get around a crash on Android 7.0
    // More details:
    // https://github.com/square/okhttp/issues/3245
    // https://android-review.googlesource.com/#/c/271775/
    try {
        source = Okio.buffer(Okio.source(rawSocket));
        sink = Okio.buffer(Okio.sink(rawSocket));
    } catch (NullPointerException npe) {
        if (NPE_THROW_WITH_NULL.equals(npe.getMessage())) {
            throw new IOException(npe);
        }
    }
}
```



#### 五、请求服务器拦截器

##### Expect: 100-continue

一般出现于上传大容量请求体或者需要验证。代表了先询问服务器是否原因接收发送请求体数据。（先只发送请求头）

OkHttp的做法：
如果服务器允许则返回100，客户端继续发送请求体；
如果服务器不允许则直接返回给用户。

同时服务器也可能会忽略此请求头，一直无法读取应答，此时抛出超时异常。

![image-20200807142539638](.\100-continue.png)

`CallServerInterceptor`，利用`HttpCodec`发出请求到服务器并且解析生成`Response`。

首先调用`httpCodec.writeRequestHeaders(request);` 将请求头写入到缓存中(直到调用`flushRequest()`才真正发送给服务器)。然后马上进行第一个逻辑判断


```java
public final class CallServerInterceptor implements Interceptor {
    private final boolean forWebSocket;

    public CallServerInterceptor(boolean forWebSocket) {
        this.forWebSocket = forWebSocket;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        RealInterceptorChain realChain = (RealInterceptorChain) chain;
        HttpCodec httpCodec = realChain.httpStream();
        StreamAllocation streamAllocation = realChain.streamAllocation();
        RealConnection connection = (RealConnection) realChain.connection();
        Request request = realChain.request();

        long sentRequestMillis = System.currentTimeMillis();

        realChain.eventListener().requestHeadersStart(realChain.call());
        //todo:拼接请求的数据
        httpCodec.writeRequestHeaders(request);
        realChain.eventListener().requestHeadersEnd(realChain.call(), request);

        Response.Builder responseBuilder = null;
        //todo:如果没有请求体或者不是post跳过
        if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
            // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
            // Continue" response before transmitting the request body. If we don't get that, return
            // what we did get (such as a 4xx response) without ever transmitting the request body.
            // todo: 如果是post请求，并包含了100-continue,不发请求体，读服务器的响应
            if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
                // todo: 发送请求头
                httpCodec.flushRequest();
                realChain.eventListener().responseHeadersStart(realChain.call());
                responseBuilder = httpCodec.readResponseHeaders(true);
            }
            //服务返回100，responseBuilder会置为null
            if (responseBuilder == null) {
                // Write the request body if the "Expect: 100-continue" expectation was met.
                realChain.eventListener().requestBodyStart(realChain.call());
                long contentLength = request.body().contentLength();
                CountingSink requestBodyOut =
                        new CountingSink(httpCodec.createRequestBody(request, contentLength));
                BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);
                //todo：写入请求体
                request.body().writeTo(bufferedRequestBody);
                bufferedRequestBody.close();
                realChain.eventListener()
                        .requestBodyEnd(realChain.call(), requestBodyOut.successfulCount);
            } else if (!connection.isMultiplexed()) {
                // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1
              // connection
                // from being reused. Otherwise we're still obligated to transmit the request
              // body to
                // leave the connection in a consistent state.
                streamAllocation.noNewStreams();
            }
        }

        httpCodec.finishRequest();
        // TODO: 读取服务器响应
        if (responseBuilder == null) {
            realChain.eventListener().responseHeadersStart(realChain.call());
            responseBuilder = httpCodec.readResponseHeaders(false);
        }

        Response response = responseBuilder
                .request(request)
                .handshake(streamAllocation.connection().handshake())
                .sentRequestAtMillis(sentRequestMillis)
                .receivedResponseAtMillis(System.currentTimeMillis())
                .build();

        int code = response.code();
        // todo: 服务器允许继续发送响应体
        if (code == 100) {
            // server sent a 100-continue even though we did not request one.
            // try again to read the actual response
            responseBuilder = httpCodec.readResponseHeaders(false);

            response = responseBuilder
                    .request(request)
                    .handshake(streamAllocation.connection().handshake())
                    .sentRequestAtMillis(sentRequestMillis)
                    .receivedResponseAtMillis(System.currentTimeMillis())
                    .build();

            code = response.code();
        }

        realChain.eventListener()
                .responseHeadersEnd(realChain.call(), response);

        if (forWebSocket && code == 101) {
            // Connection is upgrading, but we need to ensure interceptors see a non-null
          // response body.
            response = response.newBuilder()
                    .body(Util.EMPTY_RESPONSE)
                    .build();
        } else {
            response = response.newBuilder()
                    .body(httpCodec.openResponseBody(response))
                    .build();
        }

        if ("close".equalsIgnoreCase(response.request().header("Connection"))
                || "close".equalsIgnoreCase(response.header("Connection"))) {
            streamAllocation.noNewStreams();
        }

        if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
            throw new ProtocolException(
                    "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
        }

        return response;
    }

    static final class CountingSink extends ForwardingSink {
        long successfulCount;

        CountingSink(Sink delegate) {
            super(delegate);
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
            super.write(source, byteCount);
            successfulCount += byteCount;
        }
    }
}
```

整个if都和一个请求头有关： `Expect: 100-continue`。这个请求头代表了在发送请求体之前需要和服务器确定是否愿意接受客户端发送的请求体。所以`permitsRequestBody`判断为是否会携带请求体的方式(POST)，如果命中if，则会先给服务器发起一次查询是否愿意接收请求体，这时候如果服务器愿意会响应100(没有响应体，responseBuilder 即为nul)。这时候才能够继续发送剩余请求数据。

但是如果服务器不同意接受请求体，那么我们就需要标记该连接不能再被复用，调用`noNewStreams()`关闭相关的Socket。



```java
// TODO: 读取服务器响应
if (responseBuilder == null) {
    realChain.eventListener().responseHeadersStart(realChain.call());
    responseBuilder = httpCodec.readResponseHeaders(false);
}

Response response = responseBuilder
        .request(request)
        .handshake(streamAllocation.connection().handshake())
        .sentRequestAtMillis(sentRequestMillis)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .build();
```

这时`responseBuilder`的情况即为：

1、POST方式请求，请求头中包含`Expect`，服务器允许接受请求体，并且已经发出了请求体，`responseBuilder`为null;

2、POST方式请求，请求头中包含`Expect`，服务器不允许接受请求体，`responseBuilder`不为null

3、POST方式请求，未包含`Expect`，直接发出请求体，`responseBuilder`为null;

4、POST方式请求，没有请求体，`responseBuilder`为null;

5、GET方式请求，`responseBuilder`为null;

对应上面的5种情况，读取响应头并且组成响应`Response`，注意：此`Response`没有响应体。同时需要注意的是，如果服务器接受 `Expect: 100-continue`这是不是意味着我们发起了两次`Request`？那此时的响应头是第一次查询服务器是否支持接受请求体的，而不是真正的请求对应的结果响应。

所以 如果响应是100，这代表了是请求`Expect: 100-continue`成功的响应，需要马上再次读取一份响应头，这才是真正的请求对应结果响应头。

最后：

`forWebSocket`代表websocket的请求，我们直接进入else，这里就是读取响应体数据。然后判断请求和服务器是不是都希望长连接，一旦有一方指明`close`，那么就需要关闭`socket`。而如果服务器返回204/205，一般情况而言不会存在这些返回码，但是一旦出现这意味着没有响应体，但是解析到的响应头中包含`Content-Lenght`且不为0，这表响应体的数据字节长度。此时出现了冲突，直接抛出协议异常！

##### 总结

在这个拦截器中就是完成HTTP协议报文的封装与解析。



## 自定义拦截器

```java
new OkHttpClient().newBuilder().addInterceptor(new Interceptor() {
    @Override
    public Response intercept(Chain chain) throws IOException {
        // todo: .......
        final Response response = chain.proceed(chain.request());
        // todo: .......
        return response;
    }
});
```

一定要调用chain.proceed,并将response返回。

不调用的话，会使责任链中断，后面其他就没法执行了。



## OkHttp总结

整个OkHttp功能的实现就在这五个默认的拦截器中，所以先理解拦截器模式的工作机制是先决条件。这五个拦截器分别为: 重试拦截器、桥接拦截器、缓存拦截器、连接拦截器、请求服务拦截器。每一个拦截器负责的工作不一样，就好像工厂流水线，最终经过这五道工序，就完成了最终的产品。

但是与流水线不同的是，OkHttp中的拦截器每次发起请求都会在交给下一个拦截器之前干一些事情，在获得了结果之后又干一些事情。整个过程在请求向是顺序的，而响应向则是逆序。

当用户发起一个请求后，会由任务分发起`Dispatcher`将请求包装并交给重试拦截器处理。

1、重试拦截器在交出(交给下一个拦截器)之前，负责判断用户是否取消了请求；在获得了结果之后，会根据响应码判断是否需要重定向，如果满足条件那么就会重启执行所有拦截器。

2、桥接拦截器在交出之前，负责将HTTP协议必备的请求头加入其中(如：Host)并添加一些默认的行为(如：GZIP压缩)；在获得了结果后，调用保存cookie接口并解析GZIP数据。

3、缓存拦截器顾名思义，交出之前读取并判断是否使用缓存；获得结果后判断是否缓存。

4、连接拦截器在交出之前，负责找到或者新建一个连接，并获得对应的socket流；在获得结果后不进行额外的处理。

5、请求服务器拦截器进行真正的与服务器的通信，向服务器发送数据，解析读取的响应数据。

在经过了这一系列的流程后，就完成了一次HTTP请求！



## 补充: 代理

在使用OkHttp时，如果用户在创建`OkHttpClient`时，配置了`proxy`或者`proxySelector`，则会使用配置的代理，并且`proxy`优先级高于`proxySelector`。而如果未配置，则会获取机器配置的代理并使用。

```java
//JDK : ProxySelector
try {
	URI uri = new URI("http://restapi.amap.com");
	List<Proxy> proxyList = ProxySelector.getDefault().select(uri);
	System.out.println(proxyList.get(0).address());
	System.out.println(proxyList.get(0).type());
} catch (URISyntaxException e) {
	e.printStackTrace();
}
```

因此，如果我们不需要自己的App中的请求走代理，则可以配置一个`proxy(Proxy.NO_PROXY)`，这样也可以避免被抓包。`NO_PROXY`的定义如下：

```java
public static final Proxy NO_PROXY = new Proxy();
private Proxy() {
	this.type = Proxy.Type.DIRECT;
	this.sa = null;
}
```

代理在Java中对应的抽象类有三种类型:

```java
public static enum Type {
        DIRECT,
        HTTP,
        SOCKS;
	private Type() {
	}
}
```

`DIRECT`：无代理，`HTTP`：http代理，`SOCKS`：socks代理。第一种自然不用多说，而Http代理与Socks代理有什么区别？

对于Socks代理，在HTTP的场景下，代理服务器完成TCP数据包的转发工作;
而Http代理服务器，在转发数据之外，还会解析HTTP的请求及响应，并根据请求及响应的内容做一些处理。



> `RealConnection`的`connectSocket`方法:

```java
//如果是Socks代理则 new Socket(proxy); 否则相当于直接:new Socket()
rawSocket = proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP
                ? address.socketFactory().createSocket()
                : new Socket(proxy);
//connect方法
socket.connect(address);
```

设置了SOCKS代理的情况下，创建Socket时，为其传入proxy，连接时还是以HTTP服务器为目标地址；但是如果设置的是Http代理，创建Socket是与Http代理服务器建立连接。

> 在`connect`方法时传递的`address`来自于下面的集合`inetSocketAddresses`
> `RouteSelector`的`resetNextInetSocketAddress`方法：

```java
private void resetNextInetSocketAddress(Proxy proxy) throws IOException {
    // ......
    if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
        //无代理和socks代理，使用http服务器域名与端口
      socketHost = address.url().host();
      socketPort = address.url().port();
    } else {
      SocketAddress proxyAddress = proxy.address();
      if (!(proxyAddress instanceof InetSocketAddress)) {
        throw new IllegalArgumentException(
            "Proxy.address() is not an " + "InetSocketAddress: " + proxyAddress.getClass());
      }
      InetSocketAddress proxySocketAddress = (InetSocketAddress) proxyAddress;
      socketHost = getHostString(proxySocketAddress);
      socketPort = proxySocketAddress.getPort();
    }

    // ......

    if (proxy.type() == Proxy.Type.SOCKS) {
        //socks代理 connect http服务器 （DNS没用，由代理服务器解析域名）
      inetSocketAddresses.add(InetSocketAddress.createUnresolved(socketHost, socketPort));
    } else {
        //无代理，dns解析http服务器
        //http代理,dns解析http代理服务器
      List<InetAddress> addresses = address.dns().lookup(socketHost);
      //......
      for (int i = 0, size = addresses.size(); i < size; i++) {
        InetAddress inetAddress = addresses.get(i);
        inetSocketAddresses.add(new InetSocketAddress(inetAddress, socketPort));
      }
    }
}
```

设置代理时，Http服务器的域名解析会被交给代理服务器执行。但是如果是设置了Http代理，会对Http代理服务器的域名使用`OkhttpClient`配置的dns解析代理服务器，Http服务器的域名解析被交给代理服务器解析。



上述代码就是代理与DNS在OkHttp中的使用，但是还有一点需要注意，Http代理也分成两种类型：普通代理与隧道代理。

其中普通代理不需要额外的操作，扮演「中间人」的角色，在两端之间来回传递报文。这个“中间人”在收到客户端发送的请求报文时，需要正确的处理请求和连接状态，同时向服务器发送新的请求，在收到响应后，将响应结果包装成一个响应体返回给客户端。在普通代理的流程中，代理两端都是有可能察觉不到"中间人“的存在。

但是隧道代理不再作为中间人，无法改写客户端的请求，而仅仅是在建立连接后，将客户端的请求，通过建立好的隧道，无脑的转发给终端服务器。隧道代理需要发起Http **CONNECT**请求，这种请求方式没有请求体，仅供代理服务器使用，并不会传递给终端服务器。请求头 部分一旦结束，后面的所有数据，都被视为应该转发给终端服务器的数据，代理需要把他们无脑的直接转发，直到从客户端的 TCP 读通道关闭。**CONNECT** 的响应报文，在代理服务器和终端服务器建立连接后，可以向客户端返回一个 `200 Connect established` 的状态码，以此表示和终端服务器的连接，建立成功。

> RealConnection的connect方法

```java
if (route.requiresTunnel()) {         
	connectTunnel(connectTimeout, readTimeout, writeTimeout, call, eventListener);
	if (rawSocket == null) {
		// We were unable to connect the tunnel but properly closed down our
		// resources.
		break;
	}
} else {
	connectSocket(connectTimeout, readTimeout, call, eventListener);
}

```

`requiresTunnel`方法的判定为：当前请求为https并且存在http代理，这时候`connectTunnel`中会发起:

```http
CONNECT xxxx HTTP/1.1
Host: xxxx
Proxy-Connection: Keep-Alive
User-Agent: okhttp/${version}
```

的请求，连接成功代理服务器会返回200；如果返回407表示代理服务器需要鉴权(如：付费代理)，这时需要在请求头中加入`Proxy-Authorization`：

```java
 Authenticator authenticator = new Authenticator() {
        @Nullable
        @Override
        public Request authenticate(Route route, Response response) throws IOException {
          if(response.code == 407){
            //代理鉴权
            String credential = Credentials.basic("代理服务用户名", "代理服务密码");
            return response.request().newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build();
          }
          return null;
        }
      };
new OkHttpClient.Builder().proxyAuthenticator(authenticator);
```



