package com.utechat.backend.support;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import okhttp3.OkHttpClient;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Biên dịch chính source client/test thuần Java của app; không sao chép DTO hoặc cần Android/Room/Gradle. */
public final class AndroidContactClient implements AutoCloseable {
    private final URLClassLoader loader;
    private final Class<?> apiType;
    private final List<OkHttpClient> clients = new ArrayList<>();

    public AndroidContactClient() throws Exception {
        Path backend = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path app = backend.resolve("../app/src").normalize();
        assertTrue(Files.isRegularFile(backend.resolve("pom.xml")), "Chạy Maven với -f backend/pom.xml");
        Path output = Files.createTempDirectory(Files.createDirectories(backend.resolve("target")), "android-contact-contract-");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Cần JDK có javac để kiểm source Android thật");
        List<Path> sources = new ArrayList<>();
        for (String path : List.of("main/java/com/utechat/data/remote/api/ContactApi.java",
                "main/java/com/utechat/data/remote/model/ContactDtos.java", "main/java/com/utechat/data/repository/ContactsResult.java",
                "main/java/com/utechat/data/repository/ContactErrorMapper.java", "test/java/com/utechat/data/remote/api/ContactApiTest.java",
                "test/java/com/utechat/data/remote/api/ContactTransportTest.java", "test/java/com/utechat/data/remote/model/ContactDtosTest.java",
                "test/java/com/utechat/data/repository/ContactErrorMapperTest.java")) sources.add(app.resolve(path));
        // Surefire có thể dùng manifest JAR; bổ sung CodeSource của các dependency để javac vẫn thấy đủ class.
        Set<String> classpath = new LinkedHashSet<>();
        classpath.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        for (Class<?> type : List.of(Retrofit.class, GsonConverterFactory.class, Gson.class, OkHttpClient.class,
                okio.Buffer.class, kotlin.Unit.class, JUnitCore.class, org.hamcrest.Matcher.class))
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, files, diagnostics, List.of("--release", "17", "-encoding", "UTF-8", "-classpath",
                    String.join(File.pathSeparator, classpath), "-d", output.toString()), null, files.getJavaFileObjectsFromPaths(sources)).call();
            assertTrue(ok, () -> "Không biên dịch được client app: " + diagnostics.getDiagnostics());
        }
        loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
        apiType = loader.loadClass("com.utechat.data.remote.api.ContactApi");
        // Không để một bản class app cũ trên parent classpath thay thế source vừa biên dịch.
        assertEquals(output, Path.of(apiType.getProtectionDomain().getCodeSource().getLocation().toURI()));
        assertEquals(output, Path.of(loader.loadClass("com.utechat.data.repository.ContactErrorMapper")
                .getProtectionDomain().getCodeSource().getLocation().toURI()));
    }

    /** Giữ nguyên bốn suite app; không giả PASS hoặc bỏ test TCP khi máy chạy bị chặn mạng. */
    public Result runUnitContracts() throws Exception {
        return new JUnitCore().run(loader.loadClass("com.utechat.data.remote.api.ContactApiTest"),
                loader.loadClass("com.utechat.data.remote.model.ContactDtosTest"),
                loader.loadClass("com.utechat.data.repository.ContactErrorMapperTest"),
                loader.loadClass("com.utechat.data.remote.api.ContactTransportTest"));
    }

    /** JWT test ký bởi backend thật; chỉ gắn header trên origin loopback mà fixture vừa khởi động. */
    public Object api(String base, String token) {
        var origin = okhttp3.HttpUrl.get(base);
        var client = new OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    var request = chain.request();
                    assertEquals(origin.host(), request.url().host()); assertEquals(origin.port(), request.url().port());
                    assertNull(request.url().queryParameter("token"));
                    if (token != null) request = request.newBuilder().header("Authorization", "Bearer " + token).build();
                    return chain.proceed(request);
                }).build();
        clients.add(client);
        return new Retrofit.Builder().baseUrl(base).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(apiType);
    }

    public Object sendBody(UUID id) throws Exception {
        return loader.loadClass("com.utechat.data.remote.model.ContactDtos$Send").getConstructor(String.class).newInstance(id.toString());
    }

    public Response<?> call(Object api, String method, Object... args) throws Exception {
        Class<?>[] types = Arrays.stream(args).map(Object::getClass).toArray(Class<?>[]::new);
        return ((Call<?>) apiType.getMethod(method, types).invoke(api, args)).execute();
    }

    public Object httpError(Response<?> response) throws Exception {
        return loader.loadClass("com.utechat.data.repository.ContactErrorMapper").getMethod("fromHttp", Response.class).invoke(null, response);
    }

    public Object networkError(Throwable failure) throws Exception {
        return loader.loadClass("com.utechat.data.repository.ContactErrorMapper").getMethod("fromFailure", Throwable.class).invoke(null, failure);
    }

    public static Object field(Object dto, String field) throws Exception { return dto.getClass().getField(field).get(dto); }

    @Override public void close() throws Exception {
        for (var client : clients) { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
        loader.close(); // Class tạm ở target để phục vụ chẩn đoán, không xóa source/project của người dùng.
    }
}
