# CrewBoard – Event Crew Chat

**A Java LAN chat application for student event organizers: chat, assign tasks, and run "are we ready?" checks before the event starts.**

## 1. Project Overview

CrewBoard is a client–server group chat built with Java `Socket` / `ServerSocket` and a Swing GUI. A multithreaded server handles several clients at the same time over a local network (LAN / Wi-Fi). On top of ordinary group chat, it adds two features made for event crews: a shared **Task Board** and a **Readiness Check**.

## 2. Members and Course Information

| | |
|---|---|
| **Member 1** | Danielle Grace B. Colacion – GitHub: [`DaneGrace`](https://github.com/DaneGrace) |
| **Member 2** | Crystal Ann P. Naol – GitHub: [`crytalannpaloga-cloud`](https://github.com/crytalannpaloga-cloud) |
| **Course** | ITAS 2112 – Network Programming |
| **Section** | BSIT 2B-SD |
| **School** | Iloilo State University of Fisheries Science and Technology |
| **Instructor** | Kenth Joshua Epina |

## 3. Concept Proposal and Approval

- **Audience:** Student event organizers (org officers, foundation week committees, program crews).
- **User problem:** In an ordinary group chat, tasks get buried in messages. Nobody can tell who owns what, and before the event nobody knows whether everyone is actually ready.
- **Theme:** Event Crew.
- **Original feature 1 – Task Board:** Any member adds a task, either assigned to an online member or left open for anyone to accept. The assignee accepts it (OPEN → ACCEPTED), then marks it done (→ DONE). The server validates every change, and the board updates on every client at the same time.
- **Original feature 2 – Readiness Check:** Any member starts a check with a label (e.g., "Program starts at 8AM"). Each connected member confirms. The panel shows **Ready** and **Pending** lists live, and the server announces when everyone is ready.
- **Instructor approval:** Approved by Kenth Joshua Epina  Date: ____________  Signature/Remarks: ____________

## 4. Features

**Core features**
- Separate server and client applications using TCP sockets; one reader thread per client (cached thread pool) plus a dedicated writer thread and outgoing queue per client.
- Three or more simultaneous clients; one client disconnecting does not stop the server or other clients.
- Server IP and port are entered in the client; works across computers on the same LAN/Wi-Fi.
- Swing GUI: connection controls, nickname field, message input, Send button, conversation area, online crew list.
- Group chat: each message is broadcast once to every connected client (including the sender) with sender nickname and time (`HH:mm`).
- Unique, validated nicknames (2–20 characters: letters, digits, `_`, `-`; case-insensitive uniqueness).
- Commands: `/name`, `/exit`, `/help`.
- Join, leave, and rename events update the online list on all clients.
- Input validation: blank messages rejected, messages over 500 characters rejected, invalid IP/port and unavailable-server errors shown without freezing the GUI.

**Original theme features**
- Task Board (`/task`, `/accept`, `/done`, or the **Add task / Accept / Mark done** buttons).
- Readiness Check (`/ready <label>`, `/confirm`, or the **Start check / I'm ready** buttons).

## 5. Commands

| Command | What it does |
|---|---|
| `/name <newname>` | Change your nickname (validated, must be unique) |
| `/exit` | Leave the chat cleanly |
| `/help` | Show the command list |
| `/task <assignee\|-> <title>` | Add a task. `-` means anyone can accept it |
| `/accept <#>` | Accept a task (only if open, or assigned to you) |
| `/done <#>` | Mark your accepted task as done |
| `/ready <label>` | Start a readiness check |
| `/confirm` | Confirm you are ready |

## 6. Architecture

```
 ┌────────────────────────────┐                  ┌──────────────────────────────────┐
 │ ChatClient (Swing)         │                  │ ChatServer                       │
 │  • EDT: all GUI updates    │                  │  • ServerSocket.accept() loop    │
 │  • net-reader thread       │ ◄──── TCP ─────► │  • Handler reader thread/client  │
 │  • sender executor thread  │   one text line  │  • Writer thread + queue/client  │
 └────────────────────────────┘   per message    │  • One lock guards shared state: │
                                                  │    clients, tasks, readiness     │
                                                  └──────────────────────────────────┘
          Proto.java: shared message encoding/decoding used by both sides
```

**Message path (client → server → recipients):**
1. The user types text and clicks Send. The client's single-thread sender executor writes `CHAT|text` to the socket, so the GUI never blocks.
2. The server's `Handler` reader thread reads the line, validates it (blank, too long, command or normal chat), and, while holding the shared lock, calls `broadcast()`.
3. `broadcast()` puts `CHAT|time|nick|text` into every client's outgoing queue. Each client's own writer thread is the only code writing to that socket, so messages can never interleave.
4. Each client's `net-reader` thread reads the line and uses `SwingUtilities.invokeLater` to append it to the conversation area on the Event Dispatch Thread.

**Thread safety:** all shared state (users, tasks, readiness) is changed only inside `synchronized (lock)`. Per-client queues mean a slow client cannot block the others.

### Message format

Every message is **one line**: `TYPE|field1|field2|...`. Fields are escaped by `Proto.enc` (`\` → `\\`, `|` → `\p`, newline → `\n`) so user text can never break the format.

| Direction | Message | Meaning |
|---|---|---|
| Client → Server | `JOIN\|nick` | Request to join (must be first) |
| Client → Server | `CHAT\|text` | Chat text, or a `/command` |
| Client → Server | `PING` | Keep-alive (ignored) |
| Server → Client | `YOU\|nick` | Your accepted/current nickname |
| Server → Client | `CHAT\|time\|nick\|text` | Broadcast chat message |
| Server → Client | `SYS\|time\|text` | Join/leave/rename/task/readiness notices, help text |
| Server → Client | `USERS\|nick1,nick2,...` | Online list |
| Server → Client | `TASK\|id\|title\|assignee\|status\|creator` | Create/update one task row |
| Server → Client | `READY_STATE\|active\|label\|readyList\|pendingList` | Readiness panel data |
| Server → Client | `ERR\|text` | Error message shown to the user |
| Server → Client | `BYE` | Clean exit acknowledged |

### Source files
| File | Role |
|---|---|
| `ChatServer.java` | Server: sockets, per-client threads, validation, shared state, broadcasting |
| `ChatClient.java` | Swing GUI client: connection, chat, task board, readiness panel |
| `Proto.java` | Shared protocol helpers (`join`, `split`, `enc`, `dec`, `now`) |

## 7. Setup Instructions

**Requirements**
- JDK: Java ______ (JDK 8 or newer works; fill in your version, e.g. `java -version`)
- IDE: Apache NetBeans (or any IDE / command line)
- Dependencies: none (standard Java library only)
- All three `.java` files in the same folder / package

**Compile (command line)**
```
javac ChatServer.java ChatClient.java Proto.java
```

**Run the server (Computer A)**
```
java ChatServer 5000
```
The console prints the LAN address clients should use, for example:
```
[01:29:48] CrewBoard server listening on port 143
[01:29:48] Clients on the LAN can connect to: 10.182.58.86:143
```
Default port is `5000` if none is given. (Our demo run used port `143`.)

**Run clients (Computer A and Computer B)**
```
java ChatClient
```
1. Enter the **Server IP** printed by the server (`localhost` only on the server computer itself).
2. Enter the **Port** (same as the server).
3. Enter a **Nickname** and click **Connect**.
4. Open at least three client windows in total, with clients on two different computers.

**Troubleshooting**
- *"Cannot connect"*: check the server is running, the IP/port match, and both computers are on the same Wi-Fi/LAN.
- *Firewall:* allow Java through Windows Defender Firewall, or open the chosen TCP port.
- *"Port already in use"*: close the other server or pick another port.
- *"Nickname already in use"*: choose another nickname.

## 8. Screenshots

| # | Description | File |
|---|---|---|
| 1 | Server running and printing its LAN address | `screenshots/01-server-start.png` |
| 2 | Client before connecting (Server IP, Port, Nickname, Connect) | `screenshots/02-client-not-connected.png` |
| 3 | First client connected (Dane online) | `screenshots/03-first-client-connected.png` |
| 4 | Two clients online (Dane and Tala) | `screenshots/04-two-clients-online.png` |
| 5 | Group chat messages and leave notice | `screenshots/05-group-chat-and-leave.png` |
| 6 | Client after disconnect | `screenshots/06-client-after-disconnect.png` |

![Server start](screenshots/01-server-start.png)
![Not connected](screenshots/02-client-not-connected.png)
![First client](screenshots/03-first-client-connected.png)
![Two clients](screenshots/04-two-clients-online.png)
![Group chat](screenshots/05-group-chat-and-leave.png)
![After disconnect](screenshots/06-client-after-disconnect.png)

> Add screenshots for the Task Board (add / accept / done) and the Readiness Check (start / confirm / "Everyone is ready") before submitting.

## 9. Test Results

| # | Test | Steps | Expected result | Actual result | Status |
|---|---|---|---|---|---|
| 1 | Three concurrent clients | Start server; connect 3 clients (2 computers) | All three appear in online list | | ☐ |
| 2 | Broadcast to all | Client A sends "hi" | Every client receives it once with nickname and time | Dane: "hi" and Tala: "hello" appeared with time (Screenshot 5) | ✅ Pass |
| 3 | Online list updates | Join, leave | List updates on all clients | Dane, then Dane + Tala shown; Tala removed after leaving (Screenshots 3–5) | ✅ Pass |
| 4 | Valid nickname change | `/name Ana` | All clients see the new name | | ☐ |
| 5 | Duplicate nickname | `/name <existing>` / join with taken name | Error shown, name unchanged | | ☐ |
| 6 | Blank message rejection | Send empty message | "Message cannot be blank." / rejected | | ☐ |
| 7 | Invalid IP or port | IP `abc`, port `99999` | Error dialog, GUI still responsive | | ☐ |
| 8 | Unavailable server | Connect with server off | "Cannot connect" dialog, no freeze | | ☐ |
| 9 | Clean `/exit` | Type `/exit` | Client disconnects; others see "left the room" | Tala left the room notice shown (Screenshot 5) | ✅ Pass |
| 10 | Abrupt disconnect | Kill a client process | Server keeps running; others notified | | ☐ |
| 11 | Chat continues after disconnect | Remaining clients chat | Messages still delivered | | ☐ |
| 12 | Task Board sync | Client A adds task for B; B accepts; B marks done | Both boards show same status | | ☐ |
| 13 | Task rules | Non-assignee tries `/accept` or `/done` | Error message | | ☐ |
| 14 | Readiness Check sync | A starts check; A and B confirm | Ready/Pending lists update on all; "Everyone is ready" announced | | ☐ |

Fill in every blank row from your real LAN test and attach screenshots in `screenshots/`.

## 10. Contribution Log

| Date | Member | Task | Commit link |
|---|---|---|---|
| | Danielle Grace B. Colacion | | |
| | Crystal Ann P. Naol | | |
| | Danielle Grace B. Colacion | | |
| | Crystal Ann P. Naol | | |

*(Add dated entries with the links to each member's own commits.)*

## 11. Citations and AI Disclosure

- Java SE documentation: `java.net.Socket`, `ServerSocket`, `javax.swing` (Oracle).
- AI assistance: *(state honestly which tool(s) you used and for what, e.g. "Claude by Anthropic was used for drafting part of the code and this README"; both partners reviewed, tested, and can explain all submitted code.)*

## 12. Repository

Suggested repository name: `ITAS2112-BSIT2B-SD-Colacion-Naol`
Final submission: repository URL + final commit hash `________` submitted through the instructor's designated channel.
