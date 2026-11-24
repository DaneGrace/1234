import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * CrewBoard server (Event Crew theme).
 *
 * - One ServerSocket, one reader thread per client (managed by a cached thread pool).
 * - Each client also has an outgoing queue + writer thread, so a slow client can never
 *   block the others and two sends can never interleave on the same socket.
 * - All shared state (users, tasks, readiness check) is guarded by one lock.
 *
 * Usage: java ChatServer [port]   (default 5000)
 */
public class ChatServer {
    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_-]{2,20}");
    private static final int MAX_MSG = 500;
    private static final String CLOSE = "\u0000CLOSE";
    private static final String HELP = "Commands:\n"
            + "/name <newname>          change your nickname\n"
            + "/exit                    leave the chat\n"
            + "/help                    show this help\n"
            + "/task <assignee|-> <title>  add a crew task ('-' = anyone can accept)\n"
            + "/accept <#>              accept a task\n"
            + "/done <#>                mark your accepted task as done\n"
            + "/ready <label>           start a readiness check\n"
            + "/confirm                 confirm that you are ready";

    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Object lock = new Object();

    // ---- shared state, guarded by 'lock' ----
    private final Map<String, Handler> clients = new LinkedHashMap<>(); // key = lower-case nickname
    private final List<Task> tasks = new ArrayList<>();
    private int nextTaskId = 1;
    private boolean readyActive;
    private String readyLabel = "";
    private boolean readyAnnounced;
    private final Set<String> confirmed = new LinkedHashSet<>(); // lower-case nicknames

    private static class Task {
        final int id;
        final String title;
        String creator;
        String assignee = "";
        String status = "OPEN"; // OPEN -> ACCEPTED -> DONE

        Task(int id, String title, String creator) {
            this.id = id;
            this.title = title;
            this.creator = creator;
        }
    }

    ChatServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws IOException {
        int port = 5000;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[0] + "'. Use a number from 1 to 65535.");
                return;
            }
        }
        new ChatServer(port).start();
    }

    void start() throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            log("CrewBoard server listening on port " + port);
            printAddresses();
            while (true) {
                Socket s = server.accept();
                pool.execute(new Handler(s));
            }
        } catch (BindException e) {
            System.err.println("Port " + port + " is already in use. Close the other server or pick another port.");
        }
    }

    private void printAddresses() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        log("Clients on the LAN can connect to: " + a.getHostAddress() + ":" + port);
                    }
                }
            }
        } catch (SocketException ignored) {
        }
    }

    private static void log(String s) {
        System.out.println("[" + LocalTime.now().withNano(0) + "] " + s);
    }

    private static String key(String nick) {
        return nick.toLowerCase(Locale.ROOT);
    }

    private static String err(String msg) {
        return Proto.join("ERR", msg);
    }

    // ---- broadcasting (call while holding 'lock') ----
    private void broadcast(String line) {
        synchronized (lock) {
            for (Handler h : clients.values()) h.send(line);
        }
    }

    private void broadcastSys(String text) {
        broadcast(Proto.join("SYS", Proto.now(), text));
    }

    private void broadcastUsers() {
        List<String> names = new ArrayList<>();
        for (Handler h : clients.values()) names.add(h.nick);
        broadcast(Proto.join("USERS", String.join(",", names)));
    }

    private String taskMsg(Task t) {
        return Proto.join("TASK", String.valueOf(t.id), t.title, t.assignee, t.status, t.creator);
    }

    private void broadcastReady() {
        List<String> ready = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (Handler h : clients.values()) {
            (confirmed.contains(key(h.nick)) ? ready : pending).add(h.nick);
        }
        if (readyActive && pending.isEmpty() && !clients.isEmpty()) {
            if (!readyAnnounced) {
                readyAnnounced = true;
                broadcastSys("Everyone is ready: " + readyLabel);
            }
        } else {
            readyAnnounced = false;
        }
        broadcast(Proto.join("READY_STATE", readyActive ? "1" : "0", readyLabel,
                String.join(",", ready), String.join(",", pending)));
    }

    private Task findTask(int id) {
        for (Task t : tasks) if (t.id == id) return t;
        return null;
    }

    // =====================================================================
    private class Handler implements Runnable {
        final Socket socket;
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        String nick;
        boolean joined;
        boolean closing;
        boolean cleaned;

        Handler(Socket socket) {
            this.socket = socket;
        }

        void send(String line) {
            queue.offer(line);
        }

        void sendClose() {
            queue.offer(CLOSE);
        }

        /** Writer thread: the only code that writes to this client's socket. */
        private void writeLoop() {
            try (Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                while (true) {
                    String line = queue.take();
                    if (line.equals(CLOSE)) break;
                    out.write(line);
                    out.write('\n');
                    out.flush();
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        /** Reader thread. */
        @Override
        public void run() {
            pool.execute(this::writeLoop);
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (!closing && (line = in.readLine()) != null) {
                    if (line.length() > MAX_MSG + 100) {
                        send(err("Message too long (max " + MAX_MSG + " characters)."));
                        continue;
                    }
                    handle(line);
                }
            } catch (IOException ignored) {
                // abrupt disconnect: fall through to cleanup
            } finally {
                cleanup();
            }
        }

        private void cleanup() {
            synchronized (lock) {
                if (cleaned) return;
                cleaned = true;
                if (joined) {
                    clients.remove(key(nick));
                    confirmed.remove(key(nick));
                    broadcastSys(nick + " left the room");
                    broadcastUsers();
                    broadcastReady();
                    log(nick + " disconnected (" + clients.size() + " online)");
                }
            }
            sendClose(); // writer thread closes the socket after flushing pending output
        }

        private void handle(String line) {
            String[] p = Proto.split(line);
            String type = p[0];
            if (!joined) {
                if (type.equals("JOIN")) join(Proto.field(p, 1).trim());
                else send(err("Send JOIN first."));
                return;
            }
            switch (type) {
                case "CHAT": {
                    String text = Proto.field(p, 1).trim();
                    if (text.isEmpty()) {
                        send(err("Message cannot be blank."));
                    } else if (text.length() > MAX_MSG) {
                        send(err("Message too long (max " + MAX_MSG + " characters)."));
                    } else if (text.startsWith("/")) {
                        command(text);
                    } else {
                        broadcast(Proto.join("CHAT", Proto.now(), nick, text));
                    }
                    break;
                }
                case "PING":
                    break;
                default:
                    send(err("Unknown message type."));
            }
        }

        private void join(String wanted) {
            if (!NICK.matcher(wanted).matches()) {
                send(err("Nickname must be 2-20 characters: letters, digits, _ or -."));
                return;
            }
            synchronized (lock) {
                if (clients.containsKey(key(wanted))) {
                    send(err("Nickname '" + wanted + "' is already in use."));
                    return;
                }
                nick = wanted;
                joined = true;
                clients.put(key(nick), this);
                send(Proto.join("YOU", nick));
                for (Task t : tasks) send(taskMsg(t));
                broadcastSys(nick + " joined the room");
                broadcastUsers();
                broadcastReady();
                log(nick + " joined from " + socket.getRemoteSocketAddress() + " (" + clients.size() + " online)");
            }
        }

        private void command(String text) {
            String[] w = text.split("\\s+", 2);
            String cmd = w[0].toLowerCase(Locale.ROOT);
            String rest = w.length > 1 ? w[1].trim() : "";
            switch (cmd) {
                case "/help":
                    send(Proto.join("SYS", Proto.now(), HELP));
                    break;
                case "/exit":
                    closing = true;
                    send("BYE");
                    break;
                case "/name":
                    rename(rest);
                    break;
                case "/task":
                    addTask(rest);
                    break;
                case "/accept":
                case "/done":
                    updateTask(cmd, rest);
                    break;
                case "/ready":
                    startReady(rest);
                    break;
                case "/confirm":
                    confirmReady();
                    break;
                default:
                    send(err("Unknown command " + cmd + ". Type /help."));
            }
        }

        private void rename(String wanted) {
            if (!NICK.matcher(wanted).matches()) {
                send(err("Usage: /name <newname> (2-20 characters: letters, digits, _ or -)."));
                return;
            }
            synchronized (lock) {
                if (wanted.equals(nick)) {
                    send(err("That is already your nickname."));
                    return;
                }
                String oldKey = key(nick), newKey = key(wanted);
                if (!oldKey.equals(newKey) && clients.containsKey(newKey)) {
                    send(err("Nickname '" + wanted + "' is already in use."));
                    return;
                }
                String old = nick;
                clients.remove(oldKey);
                nick = wanted;
                clients.put(newKey, this);
                if (confirmed.remove(oldKey)) confirmed.add(newKey);
                for (Task t : tasks) {
                    boolean changed = false;
                    if (t.assignee.equalsIgnoreCase(old)) {
                        t.assignee = wanted;
                        changed = true;
                    }
                    if (t.creator.equalsIgnoreCase(old)) {
                        t.creator = wanted;
                        changed = true;
                    }
                    if (changed) broadcast(taskMsg(t));
                }
                send(Proto.join("YOU", nick));
                broadcastSys(old + " is now known as " + wanted);
                broadcastUsers();
                broadcastReady();
                log(old + " renamed to " + wanted);
            }
        }

        private void addTask(String rest) {
            String[] a = rest.split("\\s+", 2);
            if (a.length < 2 || a[1].trim().isEmpty()) {
                send(err("Usage: /task <assignee|-> <title>"));
                return;
            }
            String title = a[1].trim();
            if (title.length() > 80) {
                send(err("Task title is too long (max 80 characters)."));
                return;
            }
            synchronized (lock) {
                String assignee = "";
                if (!a[0].equals("-")) {
                    String name = a[0].startsWith("@") ? a[0].substring(1) : a[0];
                    Handler h = clients.get(key(name));
                    if (h == null) {
                        send(err("User '" + name + "' is not online."));
                        return;
                    }
                    assignee = h.nick;
                }
                Task t = new Task(nextTaskId++, title, nick);
                t.assignee = assignee;
                tasks.add(t);
                broadcast(taskMsg(t));
                broadcastSys(nick + " added task #" + t.id + ": " + title
                        + (assignee.isEmpty() ? " (unassigned)" : " -> " + assignee));
            }
        }

        private void updateTask(String cmd, String rest) {
            int id;
            try {
                id = Integer.parseInt(rest.replace("#", "").trim());
            } catch (NumberFormatException e) {
                send(err("Usage: " + cmd + " <task number>"));
                return;
            }
            synchronized (lock) {
                Task t = findTask(id);
                if (t == null) {
                    send(err("There is no task #" + id + "."));
                    return;
                }
                if (cmd.equals("/accept")) {
                    if (!t.status.equals("OPEN")) {
                        send(err("Task #" + id + " is already " + t.status.toLowerCase(Locale.ROOT) + "."));
                    } else if (!t.assignee.isEmpty() && !t.assignee.equalsIgnoreCase(nick)) {
                        send(err("Task #" + id + " is assigned to " + t.assignee + "."));
                    } else {
                        t.assignee = nick;
                        t.status = "ACCEPTED";
                        broadcast(taskMsg(t));
                        broadcastSys(nick + " accepted task #" + id + ": " + t.title);
                    }
                } else {
                    if (!t.status.equals("ACCEPTED")) {
                        send(err("Task #" + id + " must be accepted before it can be marked done."));
                    } else if (!t.assignee.equalsIgnoreCase(nick)) {
                        send(err("Only " + t.assignee + " can finish task #" + id + "."));
                    } else {
                        t.status = "DONE";
                        broadcast(taskMsg(t));
                        broadcastSys(nick + " finished task #" + id + ": " + t.title);
                    }
                }
            }
        }

        private void startReady(String label) {
            if (label.isEmpty()) label = "Ready check";
            if (label.length() > 60) {
                send(err("Label is too long (max 60 characters)."));
                return;
            }
            synchronized (lock) {
                readyActive = true;
                readyLabel = label;
                readyAnnounced = false;
                confirmed.clear();
                broadcastSys(nick + " started a readiness check: " + label);
                broadcastReady();
            }
        }

        private void confirmReady() {
            synchronized (lock) {
                if (!readyActive) {
                    send(err("No readiness check is running. Start one with /ready <label>."));
                    return;
                }
                if (!confirmed.add(key(nick))) {
                    send(err("You already confirmed."));
                    return;
                }
                broadcastSys(nick + " is ready");
                broadcastReady();
            }
        }
    }
}
