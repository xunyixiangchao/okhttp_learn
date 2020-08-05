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

#### 责任链模式：（一排，最后一位往前一个个传纸条[请求]，传到第一个又一个个往后传[响应]）

为请求创建了一个接收者对象的链，在处理请求的时候执行过滤(各司其职)。

责任链上的处理者负责处理请求，客户只需要将请求发送到责任链即可，无须关心请求的处理细节和请求的传递，所以职责链将请求的发送者和请求的处理者解耦了。

#### 拦截器责任链：

![1596641859673](.\拦截器责任链.png)

#### 五大拦截器功能：

1. 重试拦截器在交出(交给下一个拦截器)之前，负责判断用户是否取消了请求；在获得了结果之后，会根据响应码判断是否需要重定向，如果满足条件那么就会重启执行所有拦截器。

2. 桥接拦截器在交出之前，负责将HTTP协议必备的请求头加入其中(如：Host)并添加一些默认的行为(如：GZIP压缩)；在获得了结果后，调用保存cookie接口并解析GZIP数据。

3. 缓存拦截器顾名思义，交出之前读取并判断是否使用缓存；获得结果后判断是否缓存。

4. 连接拦截器在交出之前，负责找到或者新建一个连接，并获得对应的socket流；在获得结果后不进行额外的处理。

5. 请求服务器拦截器进行真正的与服务器的通信，向服务器发送数据，解析读取的响应数据。





























































































