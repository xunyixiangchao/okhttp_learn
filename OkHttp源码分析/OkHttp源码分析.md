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

## 使用流程：



![使用流程](.\使用流程.png)

## 调用流程：

OkHttp请求过程中最少需要接触OkHttpClient、Request、Call、Response，但是框架内部进行大量的逻辑处理。

所有的逻辑大部分集中在拦截器中，但是在进入拦截器之前还需要依靠分发器来调配请求任务。

**分发器：**内部维护队列与线程池，完成请求调配；Dispatcher

**拦截器：**五大默认拦截器完成整个请求过程； Interceptors

## 分发器：

### 异步请求工作流程：

![1596634272799](D:\soft\typora_data\OkHttp源码分析\异步请求工作.png)

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

3、交给下一个责任链继续处理

4、后续工作，返回304则用缓存的响应；否则使用网络响应并缓存本次响应（只缓存Get请求的响应）



缓存拦截器的工作说起来比较简单，但是具体的实现，需要处理的内容很多。在缓存拦截器中判断是否可以使用缓存，或是请求服务器都是通过`CacheStrategy`判断。

1：20

##### 缓存策略

#### 四、连接拦截器



#### 五、请求服务器拦截器























































































