import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * CrewBoard client (Event Crew theme) - Swing GUI.
 *
 * Threading rules:
 *  - Connecting and reading from the socket happen on a background thread ("net-reader").
 *  - Writing to the socket happens on a single-thread executor ("sender").
 *  - Every GUI change is made on the Event Dispatch Thread (SwingUtilities.invokeLater).
 */
public class ChatClient extends JFrame {
    private static final Color PURPLE = new Color(0x4B2E83);
    private static final Color PASTEL = new Color(0xC9B6F2);
    private static final Color PAPER = new Color(0xF3F2F6);
    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_-]{2,20}");

    // connection controls
    private final JTextField hostField = new JTextField("localhost", 12);
    private final JTextField portField = new JTextField("5000", 5);
    private final JTextField nickField = new JTextField(10);
    private final JButton connectBtn = new JButton("Connect");

    // chat
    private final JTextArea chatArea = new JTextArea();
    private final JTextField msgField = new JTextField();
    private final JButton sendBtn = new JButton("Send");

    // side panels
    private final DefaultListModel<String> usersModel = new DefaultListModel<>();
    private final DefaultListModel<String> readyModel = new DefaultListModel<>();
    private final DefaultListModel<String> pendingModel = new DefaultListModel<>();
    private final DefaultTableModel taskModel = new DefaultTableModel(
            new Object[]{"#", "Task", "Assigned to", "Status"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable taskTable = new JTable(taskModel);
    private final JButton addTaskBtn = new JButton("Add task");
    private final JButton acceptBtn = new JButton("Accept");
    private final JButton doneBtn = new JButton("Mark done");
    private final JButton startReadyBtn = new JButton("Start check");
    private final JButton confirmReadyBtn = new JButton("I'm ready");
    private final JLabel readyTitle = new JLabel("No readiness check running");
    private final JLabel statusLabel = new JLabel("Not connected");

    // network state
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private volatile Socket socket;
    private volatile Writer out;

    // GUI-thread state
    private boolean loggedIn;
    private String myNick = "";
    private String endReason;
    private boolean endIsError;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> new ChatClient().setVisible(true));
    }

    public ChatClient() {
        super("CrewBoard - Event Crew Chat");
        buildUi();
        setUiState(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (loggedIn) send(Proto.join("CHAT", "/exit"));
                sender.execute(() -> closeQuietly(socket));
                sender.shutdown();
                dispose();
            }
        });
    }

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(PAPER);

        root.add(buildTop(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildChat(), buildSide());
        split.setResizeWeight(0.55);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        statusLabel.setBorder(new EmptyBorder(2, 4, 0, 4));
        root.add(statusLabel, BorderLayout.SOUTH);

        setContentPane(root);
        setSize(1080, 700);
        setMinimumSize(new Dimension(900, 580));
        setLocationRelativeTo(null);

        connectBtn.addActionListener(e -> {
            if (loggedIn) disconnect();
            else connect();
        });
        nickField.addActionListener(e -> connectBtn.doClick());
        sendBtn.addActionListener(e -> sendChat());
        msgField.addActionListener(e -> sendChat());
        addTaskBtn.addActionListener(e -> addTaskDialog());
        acceptBtn.addActionListener(e -> taskAction("/accept"));
        doneBtn.addActionListener(e -> taskAction("/done"));
        startReadyBtn.addActionListener(e -> startReadyDialog());
        confirmReadyBtn.addActionListener(e -> send(Proto.join("CHAT", "/confirm")));
    }

    private JComponent buildTop() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        p.setBackground(PURPLE);
        JLabel title = new JLabel("CrewBoard");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setForeground(PASTEL);
        p.add(title);
        p.add(whiteLabel("   Server IP:"));
        p.add(hostField);
        p.add(whiteLabel("Port:"));
        p.add(portField);
        p.add(whiteLabel("Nickname:"));
        p.add(nickField);
        connectBtn.setBackground(PASTEL);
        connectBtn.setForeground(Color.BLACK);
        p.add(connectBtn);
        return p;
    }

    private static JLabel whiteLabel(String s) {
        JLabel l = new JLabel(s);
        l.setForeground(Color.WHITE);
        return l;
    }

    private JComponent buildChat() {
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        JPanel input = new JPanel(new BorderLayout(6, 0));
        input.add(msgField, BorderLayout.CENTER);
        input.add(sendBtn, BorderLayout.EAST);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(titled("Crew chat"));
        p.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        p.add(input, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildSide() {
        JPanel side = new JPanel(new BorderLayout(0, 8));

        // online users
        JList<String> users = new JList<>(usersModel);
        JScrollPane usersScroll = new JScrollPane(users);
        usersScroll.setPreferredSize(new Dimension(200, 90));
        usersScroll.setBorder(titled("Online crew"));
        side.add(usersScroll, BorderLayout.NORTH);

        // task board
        taskTable.setRowHeight(22);
        taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        taskTable.getColumnModel().getColumn(0).setMaxWidth(36);
        taskTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        taskTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc,
                                                           int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                if (!sel) {
                    String st = String.valueOf(t.getValueAt(r, 3));
                    comp.setBackground(st.equals("DONE") ? new Color(0xDCCFF5)
                            : st.equals("ACCEPTED") ? new Color(0xEFE9FA) : Color.WHITE);
                    comp.setForeground(Color.BLACK);
                }
                return comp;
            }
        });
        JPanel taskBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        taskBtns.add(addTaskBtn);
        taskBtns.add(acceptBtn);
        taskBtns.add(doneBtn);
        JPanel tasks = new JPanel(new BorderLayout());
        tasks.setBorder(titled("Task board"));
        tasks.add(new JScrollPane(taskTable), BorderLayout.CENTER);
        tasks.add(taskBtns, BorderLayout.SOUTH);
        side.add(tasks, BorderLayout.CENTER);

        // readiness
        readyTitle.setFont(readyTitle.getFont().deriveFont(Font.BOLD));
        JScrollPane readyScroll = new JScrollPane(new JList<>(readyModel));
        readyScroll.setBorder(titled("Ready"));
        JScrollPane pendingScroll = new JScrollPane(new JList<>(pendingModel));
        pendingScroll.setBorder(titled("Pending"));
        JPanel lists = new JPanel(new GridLayout(1, 2, 6, 0));
        lists.add(readyScroll);
        lists.add(pendingScroll);
        JPanel readyBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        readyBtns.add(startReadyBtn);
        readyBtns.add(confirmReadyBtn);
        JPanel ready = new JPanel(new BorderLayout(0, 4));
        ready.setBorder(titled("Readiness check"));
        ready.setPreferredSize(new Dimension(200, 190));
        ready.add(readyTitle, BorderLayout.NORTH);
        ready.add(lists, BorderLayout.CENTER);
        ready.add(readyBtns, BorderLayout.SOUTH);
        side.add(ready, BorderLayout.SOUTH);
        return side;
    }

    private static TitledBorder titled(String s) {
        return BorderFactory.createTitledBorder(s);
    }

    /** Enable/disable controls for the connected / disconnected states. */
    private void setUiState(boolean connected) {
        connectBtn.setText(connected ? "Disconnect" : "Connect");
        connectBtn.setEnabled(true);
        hostField.setEnabled(!connected);
        portField.setEnabled(!connected);
        nickField.setEnabled(!connected);
        msgField.setEnabled(connected);
        sendBtn.setEnabled(connected);
        addTaskBtn.setEnabled(connected);
        acceptBtn.setEnabled(connected);
        doneBtn.setEnabled(connected);
        startReadyBtn.setEnabled(connected);
        confirmReadyBtn.setEnabled(false);
        if (connected) msgField.requestFocusInWindow();
    }

    private void status(String s) {
        statusLabel.setText(s);
    }

    private void append(String s) {
        chatArea.append(s + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    // ------------------------------------------------------- connecting
    private void connect() {
        String host = hostField.getText().trim();
        if (host.isEmpty() || host.contains(" ")) {
            fail("Enter a valid server IP address or host name.");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            fail("The port must be a number from 1 to 65535.");
            return;
        }
        String nick = nickField.getText().trim();
        if (!NICK.matcher(nick).matches()) {
            fail("Nickname must be 2-20 characters: letters, digits, _ or -.");
            return;
        }

        endReason = null;
        endIsError = false;
        myNick = nick;
        connectBtn.setEnabled(false);
        hostField.setEnabled(false);
        portField.setEnabled(false);
        nickField.setEnabled(false);
        status("Connecting to " + host + ":" + port + " ...");

        Thread t = new Thread(() -> runConnection(host, port, nick), "net-reader");
        t.setDaemon(true);
        t.start();
    }

    private void fail(String msg) {
        status(msg);
        JOptionPane.showMessageDialog(this, msg, "Cannot connect", JOptionPane.ERROR_MESSAGE);
    }

    /** Runs on the background thread: connect, send JOIN, then read lines until the connection ends. */
    private void runConnection(String host, int port, String nick) {
        Socket s = new Socket();
        String problem = null;
        boolean established = false;
        try {
            InetSocketAddress addr = new InetSocketAddress(host, port);
            if (addr.isUnresolved()) throw new UnknownHostException(host);
            s.connect(addr, 4000);
            established = true;
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            w.write(Proto.join("JOIN", nick) + "\n");
            w.flush();
            socket = s;
            out = w;
            String line;
            while ((line = in.readLine()) != null) {
                final String l = line;
                SwingUtilities.invokeLater(() -> handleLine(l));
            }
        } catch (UnknownHostException e) {
            problem = "Unknown server address: " + host;
        } catch (SocketTimeoutException e) {
            problem = "Connection timed out. Check the IP address and make sure both computers are on the same network.";
        } catch (ConnectException e) {
            problem = "Server unavailable at " + host + ":" + port
                    + ". Make sure the server is running and the port is correct.";
        } catch (IOException e) {
            if (!established) problem = "Could not connect: " + e.getMessage();
            // otherwise: the connection dropped while chatting; onEnded reports it
        } finally {
            closeQuietly(s);
        }
        final String p = problem;
        SwingUtilities.invokeLater(() -> onEnded(p));
    }

    /** GUI thread: the connection is finished (for any reason). */
    private void onEnded(String connectProblem) {
        boolean wasLoggedIn = loggedIn;
        loggedIn = false;
        socket = null;
        out = null;
        usersModel.clear();
        readyModel.clear();
        pendingModel.clear();
        taskModel.setRowCount(0);
        readyTitle.setText("No readiness check running");
        setUiState(false);

        String msg;
        boolean error;
        if (connectProblem != null) {
            msg = connectProblem;
            error = true;
        } else if (endReason != null) {
            msg = endReason;
            error = endIsError;
        } else if (wasLoggedIn) {
            msg = "The connection to the server was lost.";
            error = true;
        } else {
            msg = "The server closed the connection.";
            error = true;
        }
        status(msg);
        if (wasLoggedIn) append("[!] " + msg);
        if (error) JOptionPane.showMessageDialog(this, msg, "CrewBoard", JOptionPane.WARNING_MESSAGE);
    }

    private void disconnect() {
        status("Disconnecting ...");
        send(Proto.join("CHAT", "/exit"));
        // safety net in case the server does not answer
        Timer t = new Timer(2000, e -> closeQuietly(socket));
        t.setRepeats(false);
        t.start();
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------- sending
    private void send(String line) {
        sender.execute(() -> {
            Writer w = out;
            if (w == null) return;
            try {
                w.write(line + "\n");
                w.flush();
            } catch (IOException e) {
                closeQuietly(socket); // reader thread will report the lost connection
            }
        });
    }

    private void sendChat() {
        if (!loggedIn) return;
        String text = msgField.getText().trim();
        if (text.isEmpty()) {
            status("A blank message cannot be sent.");
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        send(Proto.join("CHAT", text));
        msgField.setText("");
    }

    private void addTaskDialog() {
        JTextField title = new JTextField(22);
        JComboBox<String> who = new JComboBox<>();
        who.addItem("(anyone can accept)");
        for (int i = 0; i < usersModel.size(); i++) who.addItem(usersModel.get(i));
        JPanel p = new JPanel(new GridLayout(2, 2, 6, 6));
        p.add(new JLabel("Task:"));
        p.add(title);
        p.add(new JLabel("Assign to:"));
        p.add(who);
        int r = JOptionPane.showConfirmDialog(this, p, "New crew task",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        String t = title.getText().trim();
        if (t.isEmpty()) {
            status("The task title cannot be blank.");
            return;
        }
        String assignee = who.getSelectedIndex() == 0 ? "-" : (String) who.getSelectedItem();
        send(Proto.join("CHAT", "/task " + assignee + " " + t));
    }

    private void taskAction(String cmd) {
        int row = taskTable.getSelectedRow();
        if (row < 0) {
            status("Select a task in the board first.");
            return;
        }
        send(Proto.join("CHAT", cmd + " " + taskModel.getValueAt(row, 0)));
    }

    private void startReadyDialog() {
        String label = JOptionPane.showInputDialog(this, "What is the crew getting ready for?",
                "Start readiness check", JOptionPane.QUESTION_MESSAGE);
        if (label == null) return;
        send(Proto.join("CHAT", "/ready " + label.trim()));
    }

    // -------------------------------------------------------- receiving
    /** GUI thread: one line from the server. */
    private void handleLine(String line) {
        String[] p = Proto.split(line);
        switch (p[0]) {
            case "YOU":
                myNick = Proto.field(p, 1);
                if (!loggedIn) {
                    loggedIn = true;
                    setUiState(true);
                    status("Connected as " + myNick);
                    append("Connected as " + myNick + ". Type /help for commands.");
                } else {
                    status("You are now known as " + myNick);
                }
                break;
            case "ERR":
                if (!loggedIn) {
                    endReason = "Could not join: " + Proto.field(p, 1);
                    endIsError = true;
                    closeQuietly(socket);
                } else {
                    append("[!] " + Proto.field(p, 1));
                    status(Proto.field(p, 1));
                }
                break;
            case "BYE":
                endReason = "You left the chat.";
                endIsError = false;
                break;
            case "CHAT":
                append("[" + Proto.field(p, 1) + "] " + Proto.field(p, 2) + ": " + Proto.field(p, 3));
                break;
            case "SYS":
                append("[" + Proto.field(p, 1) + "] * " + Proto.field(p, 2));
                break;
            case "USERS":
                usersModel.clear();
                for (String u : Proto.field(p, 1).split(",")) if (!u.isEmpty()) usersModel.addElement(u);
                break;
            case "TASK":
                upsertTask(p);
                break;
            case "READY_STATE":
                updateReady(p);
                break;
            default:
                break;
        }
    }

    private void upsertTask(String[] p) {
        int id;
        try {
            id = Integer.parseInt(Proto.field(p, 1));
        } catch (NumberFormatException e) {
            return;
        }
        String title = Proto.field(p, 2);
        String assignee = Proto.field(p, 3).isEmpty() ? "-" : Proto.field(p, 3);
        String st = Proto.field(p, 4);
        for (int r = 0; r < taskModel.getRowCount(); r++) {
            if (taskModel.getValueAt(r, 0).equals(id)) {
                taskModel.setValueAt(title, r, 1);
                taskModel.setValueAt(assignee, r, 2);
                taskModel.setValueAt(st, r, 3);
                return;
            }
        }
        taskModel.addRow(new Object[]{id, title, assignee, st});
    }

    private void updateReady(String[] p) {
        boolean active = "1".equals(Proto.field(p, 1));
        String label = Proto.field(p, 2);
        readyModel.clear();
        pendingModel.clear();
        for (String s : Proto.field(p, 3).split(",")) if (!s.isEmpty()) readyModel.addElement(s);
        boolean mePending = false;
        for (String s : Proto.field(p, 4).split(",")) {
            if (s.isEmpty()) continue;
            pendingModel.addElement(s);
            if (s.equalsIgnoreCase(myNick)) mePending = true;
        }
        if (!active) readyTitle.setText("No readiness check running");
        else if (pendingModel.isEmpty()) readyTitle.setText(label + " - EVERYONE IS READY");
        else readyTitle.setText(label + " (" + readyModel.size() + "/"
                + (readyModel.size() + pendingModel.size()) + " ready)");
        confirmReadyBtn.setEnabled(loggedIn && active && mePending);
    }
}
