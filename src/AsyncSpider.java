import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Асинхронный паук, который перемещается по серверу, отображая график ресурсов.
 */
public class AsyncSpider {
    private static final Pattern MESSAGE_P = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
    private static final Pattern SUCCESSORS_P = Pattern.compile("\"successors\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern STRING_IN_ARRAY_P = Pattern.compile("\"(.*?)\"");
    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(13);
    private static final int MAX_RETRIES = 2;

    /**
     * POJO запись, представляющая проанализированный узел.
     *
     * @param message message value
     * @param successors list of successor paths
     */
    record Node(String message, List<String> successors) {

    }

    /**
     * Общий HttpClient, настроенный с помощью исполнителя виртуального потока
     * и коротким тайм-аутом подключения.
     */
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final Queue<String> allMessages = new ConcurrentLinkedQueue<>();

    /**
     * Точка входа в программу.
     *
     * @param args {@code [0]} base URL (default {@code http://localhost:8080}),
     *      *             {@code [1]} start path (default {@code /})
     * @throws Exception if traversal fails unexpectedly
     */
    static void main(String[] args) throws Exception {

        String base;
        if (args.length > 0)
            base = args[0];
        else
            base = "http://localhost:8080";

        String startPath;
        if (args.length > 1)
            startPath = args[1];
        else
            startPath = "/";

        AsyncSpider spider = new AsyncSpider();
        List<String> result = spider.crawl(URI.create(base), startPath, Duration.ofSeconds(180));

        result.forEach(System.out::println);
    }

    /**
     * Выполняет параллельный обход графика ресурсов.
     *
     * @param base base server URI
     * @param startPath initial path to visit
     * @param globalTimeout max allowed wall-clock time for the whole traversal
     * @return lexicographically sorted list of collected messages
     * @throws InterruptedException if the waiting thread is interrupted while awaiting completion
     * @throws TimeoutException if the traversal exceeds globalTimeout
     */
    public List<String> crawl(URI base, String startPath, Duration globalTimeout)
            throws InterruptedException, TimeoutException {

        try (ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicInteger inFlight = new AtomicInteger(0);

            CountDownLatch done = new CountDownLatch(1);

            long deadlineNanos = System.nanoTime() + globalTimeout.toNanos();

            Runnable submit = new Runnable() {
                void fork(String path) {

                    if (!visited.add(path))
                        return;

                    inFlight.incrementAndGet();

                    vexec.submit(() -> {
                        try {
                            Node node = fetchNode(base.resolve(path));
                            if (node != null) {
                                if (node.message() != null && !node.message().isBlank())
                                    allMessages.add(node.message());

                                for (String next : node.successors()) {
                                    fork(next);
                                }
                            }
                        } finally {
                            if (inFlight.decrementAndGet() == 0)
                                done.countDown();
                        }
                    });
                }

                @Override
                public void run() {
                    fork(startPath);
                }
            };

            submit.run();

            long remaining;
            while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
                if (done.await(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 250), TimeUnit.MILLISECONDS))
                    break;
            }

            if (System.nanoTime() >= deadlineNanos)
                throw new TimeoutException("Глобальный таймаут обхода истёк");
        }

        var list = new ArrayList<>(allMessages);
        list.sort(Comparator.naturalOrder());

        return list;
    }

    /**
     * Извлекает и анализирует один узел из заданного URI
     * с помощью простой логики повторных попыток.
     *
     * @param uri absolute resource URI to fetch
     * @return parsed Node or null if not available/failed
     */
    private Node fetchNode(URI uri) {
        for (int attempt = 0; true; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(REQ_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() != 200)
                    return null;

                return parseNode(resp.body());
            } catch (IOException e) {
                if (attempt == MAX_RETRIES)
                    return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                return null;
            }
        }
    }

    /**
     * Анализирует полезную нагрузку JSON в узле,
     * используя упрощенное извлечение регулярных выражений.
     *
     * @param json raw JSON string from the server
     * @return a Node instance
     */
    private static Node parseNode(String json) {
        String message = null;

        Matcher m = MESSAGE_P.matcher(json);

        if (m.find())
            message = unescape(m.group(1));

        List<String> successors = new ArrayList<>();

        Matcher s = SUCCESSORS_P.matcher(json);

        if (s.find()) {
            String arr = s.group(1);

            Matcher each = STRING_IN_ARRAY_P.matcher(arr);

            while (each.find())
                successors.add(unescape(each.group(1)));
        }

        return new Node(message, successors);
    }

    /**
     * Минимальный объем доступа для строк в JSON-кавычках, поддерживающих {@code \"} и {@code \\}.
     *
     * @param s JSON-escaped string content (without surrounding quotes)
     * @return unescaped Java string
     */
    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
