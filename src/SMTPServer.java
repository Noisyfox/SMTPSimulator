import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Noisyfox on 2015/3/31.
 * SMTP 服务端
 */
public class SMTPServer {

    private final MailBox mMailBox;
    private final String mServerName;
    private final boolean mNeedAuth;
    private final String mUser;
    private final String mPassword;

    public SMTPServer(MailBox mailBox, String serverName) {
        mMailBox = mailBox;
        mServerName = serverName;
        mNeedAuth = false;
        mUser = mPassword = null;

        startThread();
    }

    public SMTPServer(MailBox mailBox, String serverName, String user, String password) {
        mMailBox = mailBox;
        mServerName = serverName;
        mNeedAuth = true;
        mUser = user;
        mPassword = password;

        startThread();
    }

    private ServerThread mThread;

    private void startThread() {
        mThread = new ServerThread();
        mThread.start();
    }

    public void stop() {
        mThread.stopServer();
    }

    private class ServerThread extends Thread {
        private final ExecutorService mThreadPool = Executors.newCachedThreadPool();

        private ServerSocketChannel serverSocketChannel;
        private Selector selector;

        public void stopServer() {
            silentClose(selector);
            silentClose(serverSocketChannel);
        }

        @Override
        public void run() {
            try {
                selector = Selector.open();
                serverSocketChannel = ServerSocketChannel.open();
                serverSocketChannel.configureBlocking(false);
                serverSocketChannel.socket().bind(new InetSocketAddress(25));
                serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
                while (true) {
                    selector.select();
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> iter = keys.iterator();
                    SocketChannel sc;
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        if (key.isAcceptable()) {
                            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
                            sc = ssc.accept();
                            mThreadPool.execute(new ServerWorker(sc));
                        } else if (key.isReadable()) {
                            sc = (SocketChannel) key.channel();
                            mThreadPool.execute(new ServerWorker(sc));
                        }
                        iter.remove();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                silentClose(selector);
                silentClose(serverSocketChannel);
                mThreadPool.shutdownNow();
            }
        }
    }

    private void silentClose(Selector selector) {
        if (selector != null) {
            try {
                selector.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class ServerWorker implements Runnable {
        private final Pattern mPattern_mailFrom = Pattern.compile("mail +from: +<(.*)> *");
        private final Pattern mPattern_rcptTo = Pattern.compile("rcpt +to: +<(.*)> *");
        private final Pattern mPattern_email = Pattern.compile("^\\w+(?:\\.\\w+)*@\\w+(?:\\.\\w+)+$");

        private final Socket mSocket;
        private final SocketChannel mSocketChannel;

        private PrintWriter mWriter;
        private BufferedReader mReader;

        private boolean mHELOSend = false;
        private boolean mEHLOSend = false;

        private boolean mAuthSucc = false;
        private MailContent mCurrentMail = null;

        public ServerWorker(SocketChannel socketChannel) throws IOException {
            mSocketChannel = socketChannel;
            mSocketChannel.configureBlocking(true);
            mSocket = mSocketChannel.socket();
            mWriter = new PrintWriter(new OutputStreamWriter(mSocket.getOutputStream(), "US-ASCII"));
            mReader = new BufferedReader(new InputStreamReader(mSocket.getInputStream(), "UTF-8"));
        }

        @Override
        public void run() {
            try {
                doWork();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                silentClose(mWriter);
                silentClose(mReader);
                silentClose(mSocketChannel);
            }
        }

        private void doWork() throws IOException {
            writeLine(SMTPDefine.SERVER_READY, mServerName + " Noisyfox SMTP Server Simulator");
            // 等待客户端helo
            while (!mHELOSend && !mEHLOSend) {
                String line = nextLine();
                if (startWithIgnoreCase(line, "helo")) {
                    doHelo(line);
                } else if (startWithIgnoreCase(line, "ehlo")) {
                    doEhlo(line);
                } else if (startWithIgnoreCase(line, "quit")) {
                    doQuit();
                    return;
                } else {
                    writeLine(SMTPDefine.WRONG_SEQUENCE, "Error: send HELO/EHLO first");
                }
                if (Thread.interrupted()) {
                    return;
                }
            }
            while (!Thread.interrupted()) {
                String line = nextLine();
                if (startWithIgnoreCase(line, "helo")) {
                    doHelo(line);
                } else if (startWithIgnoreCase(line, "ehlo")) {
                    doEhlo(line);
                } else if (startWithIgnoreCase(line, "auth")) {
                    doAuth(line);
                } else if (startWithIgnoreCase(line, "mail")) {
                    doMail(line);
                } else if (startWithIgnoreCase(line, "rcpt")) {
                    doRcpt(line);
                } else if (startWithIgnoreCase(line, "data")) {
                    doData();
                } else if (startWithIgnoreCase(line, "quit")) {
                    doQuit();
                    return;
                } else {
                    writeLine(SMTPDefine.UNKNOWN_CMD, "Error: command not implemented");
                }
            }
        }

        private void doHelo(String cmd) throws IOException {
            if (!startWithIgnoreCase(cmd, "helo ") || cmd.substring(5).trim().isEmpty()) {
                writeLine(SMTPDefine.BAD_ARGUMENT, "Syntax: HELO hostname");
            } else {
                writeLine(SMTPDefine.OK, mServerName);
                mHELOSend = true;
            }
        }

        private void doEhlo(String cmd) throws IOException {
            if (!startWithIgnoreCase(cmd, "ehlo ") || cmd.substring(5).trim().isEmpty()) {
                writeLine(SMTPDefine.BAD_ARGUMENT, "Syntax: HELO hostname");
            } else {
                writeLines(SMTPDefine.OK, new String[]{
                        mServerName, "AUTH LOGIN", "AUTH=LOGIN", "PIPELINING", "8BITMIME"
                });
                mEHLOSend = true;
            }
        }

        private void doAuth(String cmd) throws IOException {
            if (!startWithIgnoreCase(cmd, "auth ")) {
                writeLine(SMTPDefine.UNKNOWN_CMD, "Error: auth command not implemented");
                return;
            }
            String authCmd = cmd.substring(5).trim();
            if (!"login".equalsIgnoreCase(authCmd)) {
                writeLine(SMTPDefine.UNKNOWN_CMD, "Error: auth command not implemented");
                return;
            }

            mAuthSucc = false;

            writeLine(SMTPDefine.WAIT_INPUT, "VXNlcm5hbWU6");
            cmd = nextLine();
            try {
                String user = new String(Base64.decode(cmd, Base64.DEFAULT), "US-ASCII");
                if (!mUser.equals(user)) {
                    writeLine(SMTPDefine.AUTH_FAILED, "Error: authentication failed, system busy");
                    return;
                }
            } catch (Exception e) {
                writeLine(SMTPDefine.AUTH_FAILED, "Error: authentication failed, system busy");
                return;
            }

            writeLine(SMTPDefine.WAIT_INPUT, "UGFzc3dvcmQ6");
            cmd = nextLine();
            try {
                String psw = new String(Base64.decode(cmd, Base64.DEFAULT), "US-ASCII");
                if (!mPassword.equals(psw)) {
                    writeLine(SMTPDefine.AUTH_FAILED, "Error: authentication failed, system busy");
                    return;
                }
            } catch (Exception e) {
                writeLine(SMTPDefine.AUTH_FAILED, "Error: authentication failed, system busy");
                return;
            }

            writeLine(SMTPDefine.AUTH_SUCCESS, "Authentication successful");
            mAuthSucc = true;
        }

        private void doMail(String cmd) throws IOException {
            if (mNeedAuth && !mAuthSucc) {
                writeLine(SMTPDefine.WRONG_SEQUENCE, "Error: need EHLO and AUTH first !");
                return;
            }

            cmd = cmd.toLowerCase();
            Matcher matcher = mPattern_mailFrom.matcher(cmd);
            if (!matcher.matches()) {
                writeLine(SMTPDefine.BAD_ARGUMENT, "Syntax: MAIL FROM: <address>");
                return;
            }

            String address = matcher.group(1);
            if (!mPattern_email.matcher(address).matches()) {
                writeLine(SMTPDefine.BAD_ARGUMENT, "Bad address syntax");
                return;
            }

            writeLine(SMTPDefine.OK, "Ok");
            mCurrentMail = new MailContent();
            mCurrentMail.from = address;
        }

        private void doRcpt(String cmd) throws IOException {
            if (mCurrentMail == null || mCurrentMail.from == null) {
                writeLine(SMTPDefine.WRONG_SEQUENCE, "Error: need MAIL command");
                return;
            }

            cmd = cmd.toLowerCase();
            Matcher matcher = mPattern_rcptTo.matcher(cmd);
            if (!matcher.matches()) {
                writeLine(SMTPDefine.BAD_ARGUMENT, "Syntax: RCPT TO: <address>");
                return;
            }

            String address = matcher.group(1);
            if (!mPattern_email.matcher(address).matches()) {
                writeLine(SMTPDefine.BAD_ARGUMENT, "Bad address syntax");
                return;
            }

            writeLine(SMTPDefine.OK, "Ok");
            mCurrentMail.to = address;
        }

        private void doData() throws IOException {
            if (mCurrentMail == null || mCurrentMail.to == null) {
                writeLine(SMTPDefine.WRONG_SEQUENCE, "Error: need RCPT command");
                return;
            }
            writeLine(SMTPDefine.MAIL_START, "End data with <CR><LF>.<CR><LF>");

            StringBuilder sb = new StringBuilder();
            while (true) {
                int cp = mReader.read();
                if (cp == -1) {
                    throw new IOException();
                }
                char c = (char) cp;
                if (c != '\r') {
                    sb.append(c);
                } else {
                    String cs = checkDataEnd();
                    if (cs != null) {
                        sb.append(cs);
                    } else {
                        break;
                    }
                }
            }
            mCurrentMail.content = sb.toString();

            writeLine(SMTPDefine.OK, "Ok: queued as");
            // 插入收件箱
            mMailBox.deliverMail(mCurrentMail);
        }

        private String checkDataEnd() throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append('\r');
            int cp = mReader.read();
            if (cp == -1) {
                throw new IOException();
            }
            char c = (char) cp;
            sb.append(c);
            if (c != '\n') {
                return sb.toString();
            }
            cp = mReader.read();
            if (cp == -1) {
                throw new IOException();
            }
            c = (char) cp;
            sb.append(c);
            if (c != '.') {
                return sb.toString();
            }
            cp = mReader.read();
            if (cp == -1) {
                throw new IOException();
            }
            c = (char) cp;
            sb.append(c);
            if (c != '\r') {
                return sb.toString();
            }
            cp = mReader.read();
            if (cp == -1) {
                throw new IOException();
            }
            c = (char) cp;
            sb.append(c);
            if (c != '\n') {
                return sb.toString();
            }

            return null;
        }

        private void doQuit() throws IOException {
            writeLine(SMTPDefine.CONNECT_CLOSE, "BYE");
        }

        public boolean startWithIgnoreCase(String src, String obj) {
            return obj.length() <= src.length() && src.substring(0, obj.length()).equalsIgnoreCase(obj);
        }

        private String nextLine() throws IOException {
            String line;
            while (true) {
                mSocket.setSoTimeout(20000);
                line = mReader.readLine();
                mSocket.setSoTimeout(0);
                if (line == null) {
                    throw new IOException();
                }
                if (!line.isEmpty()) break;
            }
            return line;
        }

        private void writeLine(int returnCode, String message) throws IOException {
            mWriter.print(returnCode);
            mWriter.print(' ');
            mWriter.print(message);
            mWriter.print(SMTPDefine.LINE_SP);
            mWriter.flush();

            if (mWriter.checkError()) {
                throw new IOException();
            }
        }

        private void writeLines(int returnCode, String lines[]) throws IOException {
            int len = lines.length - 1;
            for (int i = 0; i < len; i++) {
                mWriter.print(returnCode);
                mWriter.print('-');
                mWriter.print(lines[i]);
                mWriter.print(SMTPDefine.LINE_SP);
            }
            mWriter.print(returnCode);
            mWriter.print(' ');
            mWriter.print(lines[len]);
            mWriter.print(SMTPDefine.LINE_SP);
            mWriter.flush();

            if (mWriter.checkError()) {
                throw new IOException();
            }
        }
    }

    private void silentClose(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
