/*
 * Copyright (C) 2015 Square, Inc.
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
package retrofit2;

import java.io.IOException;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

import static retrofit2.Utils.checkNotNull;

final class OkHttpCall<T> implements Call<T> {
  private final ServiceMethod<T, ?> serviceMethod;
  private final @Nullable Object[] args;

  private volatile boolean canceled;

  @GuardedBy("this")
  private @Nullable okhttp3.Call rawCall;
  @GuardedBy("this")
  private @Nullable Throwable creationFailure; // Either a RuntimeException or IOException.
  @GuardedBy("this")
  private boolean executed;

  OkHttpCall(ServiceMethod<T, ?> serviceMethod, @Nullable Object[] args) {
    this.serviceMethod = serviceMethod;
    this.args = args;
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override public OkHttpCall<T> clone() {
    return new OkHttpCall<>(serviceMethod, args);
  }

  @Override public synchronized Request request() {
    okhttp3.Call call = rawCall;
    if (call != null) {
      return call.request();
    }
    //判断是不是存在异常,如果存在异常，则抛出异常,这里如果call为null表示只有两种可能：1.没有创建过，首次创建2.创建过，失败了。
    //如果是1.createFailture肯定=null
    //如果是2，则createFailture!=null,没必要再创建一遍，浪费。
    if (creationFailure != null) {
      if (creationFailure instanceof IOException) {
        throw new RuntimeException("Unable to create request.", creationFailure);
      } else {
        throw (RuntimeException) creationFailure;
      }
    }
    try {
      //创建一个Okhttp中的call
      return (rawCall = createRawCall()).request();
    } catch (RuntimeException e) {
      creationFailure = e;
      throw e;
    } catch (IOException e) {
      creationFailure = e;
      throw new RuntimeException("Unable to create request.", e);
    }
  }

  @Override public void enqueue(final Callback<T> callback) {
    checkNotNull(callback, "callback == null");

    okhttp3.Call call;
    Throwable failure;

    synchronized (this) {
      if (executed) throw new IllegalStateException("Already executed.");
      executed = true;

      call = rawCall;
      failure = creationFailure;
      if (call == null && failure == null) {
        try {
          //创建OkHttp中的Call
          call = rawCall = createRawCall();
        } catch (Throwable t) {
          failure = creationFailure = t;
        }
      }
    }

    if (failure != null) {
      callback.onFailure(this, failure);
      return;
    }

    if (canceled) {
      call.cancel();
    }

    call.enqueue(new okhttp3.Callback() {
      @Override public void onResponse(okhttp3.Call call, okhttp3.Response rawResponse)
          throws IOException {
        Response<T> response;
        try {
          response = parseResponse(rawResponse);
        } catch (Throwable e) {
          callFailure(e);
          return;
        }
        callSuccess(response);
      }

      @Override public void onFailure(okhttp3.Call call, IOException e) {
        callFailure(e);
      }

      private void callFailure(Throwable e) {
        try {
          callback.onFailure(OkHttpCall.this, e);
        } catch (Throwable t) {
          t.printStackTrace();
        }
      }

      private void callSuccess(Response<T> response) {
        try {
          callback.onResponse(OkHttpCall.this, response);
        } catch (Throwable t) {
          t.printStackTrace();
        }
      }
    });
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public Response<T> execute() throws IOException {
    okhttp3.Call call;

    synchronized (this) {
      //请求过了
      if (executed) throw new IllegalStateException("Already executed.");
      executed = true;
      //如果之前创建过程中出现过异常，则没必要再创建一次
      if (creationFailure != null) {
        if (creationFailure instanceof IOException) {
          throw (IOException) creationFailure;
        } else {
          throw (RuntimeException) creationFailure;
        }
      }

      call = rawCall;
      if (call == null) {
        try {
          //创建okhttp中的call
          call = rawCall = createRawCall();
        } catch (IOException | RuntimeException e) {
          creationFailure = e;
          throw e;
        }
      }
    }

    if (canceled) {
      call.cancel();
    }

    return parseResponse(call.execute());
  }

  private okhttp3.Call createRawCall() throws IOException {
    //得到OkHttp中的Request
    Request request = serviceMethod.toRequest(args);
    //默认callFactory = OkHttpClient,对应得到OkHttp中的Call
    okhttp3.Call call = serviceMethod.callFactory.newCall(request);
    if (call == null) {
      throw new NullPointerException("Call.Factory returned null.");
    }
    return call;
  }

  Response<T> parseResponse(okhttp3.Response rawResponse) throws IOException {
    ResponseBody rawBody = rawResponse.body();

    // Remove the body's source (the only stateful object) so we can pass the response along.
    //构造一个没有body的Resposne
    rawResponse = rawResponse.newBuilder()
        .body(new NoContentResponseBody(rawBody.contentType(), rawBody.contentLength()))
        .build();

    int code = rawResponse.code();
    if (code < 200 || code >= 300) {
      //错误
      try {
        // Buffer the entire body to avoid future I/O.
        ResponseBody bufferedBody = Utils.buffer(rawBody);
        return Response.error(bufferedBody, rawResponse);
      } finally {
        rawBody.close();
      }
    }

    if (code == 204 || code == 205) {
      //204和205返回一个没有body的Resposne
      rawBody.close();
      return Response.success(null, rawResponse);
    }

    ExceptionCatchingRequestBody catchingBody = new ExceptionCatchingRequestBody(rawBody);
    try {
      //默认是BuiltInConverters中的BufferingResponseBodyConverter来进行缓冲流转换
      T body = serviceMethod.toResponse(catchingBody);
      return Response.success(body, rawResponse);
    } catch (RuntimeException e) {
      // If the underlying source threw an exception, propagate that rather than indicating it was
      // a runtime exception.
      catchingBody.throwIfCaught();
      throw e;
    }
  }

  public void cancel() {
    canceled = true;

    okhttp3.Call call;
    synchronized (this) {
      call = rawCall;
    }
    if (call != null) {
      call.cancel();
    }
  }

  @Override public boolean isCanceled() {
    if (canceled) {
      return true;
    }
    synchronized (this) {
      return rawCall != null && rawCall.isCanceled();
    }
  }

  static final class NoContentResponseBody extends ResponseBody {
    private final MediaType contentType;
    private final long contentLength;

    NoContentResponseBody(MediaType contentType, long contentLength) {
      this.contentType = contentType;
      this.contentLength = contentLength;
    }

    @Override public MediaType contentType() {
      return contentType;
    }

    @Override public long contentLength() {
      return contentLength;
    }

    @Override public BufferedSource source() {
      throw new IllegalStateException("Cannot read raw response body of a converted body.");
    }
  }

  static final class ExceptionCatchingRequestBody extends ResponseBody {
    private final ResponseBody delegate;
    IOException thrownException;

    ExceptionCatchingRequestBody(ResponseBody delegate) {
      this.delegate = delegate;
    }

    @Override public MediaType contentType() {
      return delegate.contentType();
    }

    @Override public long contentLength() {
      return delegate.contentLength();
    }

    @Override public BufferedSource source() {
      return Okio.buffer(new ForwardingSource(delegate.source()) {
        @Override public long read(Buffer sink, long byteCount) throws IOException {
          try {
            return super.read(sink, byteCount);
          } catch (IOException e) {
            thrownException = e;
            throw e;
          }
        }
      });
    }

    @Override public void close() {
      delegate.close();
    }

    void throwIfCaught() throws IOException {
      if (thrownException != null) {
        throw thrownException;
      }
    }
  }
}
