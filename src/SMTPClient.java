import java.io.*;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/3/31.
 * SMTP 客户端
 */
public class SMTPClient {

    public enum Step {
        CONNECTING,
        HELO,
        EHLO,
        AUTH,
        AUTH_USER,
        AUTH_PASSWORD,
        READY,
        QUIT,
        STOP,
        MAIL_FROM,
        RCPT_TO,
        DATA
    }

    public SMTPClient(String address) {
        mServerAddress = address;
        mNeedAuth = false;
        mUser = mPassword = null;

        startThread();
    }

    public SMTPClient(String address, String user, String password) {
        mServerAddress = address;
        mNeedAuth = true;
        mUser = user;
        mPassword = password;

        startThread();
    }

    private final String mServerAddress;
    private final boolean mNeedAuth;
    private final String mUser;
    private final String mPassword;
    private final ReentrantLock mStepLock = new ReentrantLock();
    private final Condition mStepCondition = mStepLock.newCondition();

    private Step mCurrentStep = Step.CONNECTING;
    private MailContent mCurrentMail = null;

    private void startThread() {
        new ClientThread().start();
    }

    /**
     * 下一步
     */
    public void nextStep() {
        if (mStepLock.tryLock()) {
            try {
                mStepCondition.signalAll();
            } finally {
                mStepLock.unlock();
            }
        }
    }

    public void sendMail(MailContent mailContent) {
        if (mStepLock.tryLock()) {
            try {
                if (mCurrentStep != Step.READY) {
                    return;
                }
                mCurrentStep = Step.MAIL_FROM;
                mCurrentMail = mailContent;
                nextStep();
            } finally {
                mStepLock.unlock();
            }
        }
    }

    public void quit() {
        if (mStepLock.tryLock()) {
            try {
                mCurrentStep = Step.QUIT;
                nextStep();
            } finally {
                mStepLock.unlock();
            }
        }
    }

    private class ServerRespond {
        public int mRespondCode;
        public String[] mRespond;
    }

    private final ServerRespond RESPOND_ERROR;

    {
        RESPOND_ERROR = new ServerRespond();
        RESPOND_ERROR.mRespondCode = -1;
    }

    private class ClientThread extends Thread {

        private final ReentrantLock mRespondQueueLock = new ReentrantLock();
        private final Condition mRespondQueueCondition = mRespondQueueLock.newCondition();
        private final Queue<ServerRespond> mQueuedRespond = new LinkedList<ServerRespond>();

        private Socket mSocket = null;
        private PrintWriter mWriter = null;
        private BufferedReader mReader = null;
        private Logger mLogger = Logger.getInstance();

        private boolean mAuthSupportLogin = false;

        @Override
        public void run() {
            mStepLock.lock();
            try {
                mLogger.println("Client inited! Ready to connect to server.");
                while (mCurrentStep != Step.STOP) {
                    try {
                        mStepCondition.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        return;
                    }
                    try {
                        doWork();
                    } catch (IOException e) {
                        e.printStackTrace();
                        break;
                    } catch (RespondCodeMismatchException e) {
                        e.printStackTrace();
                        break;
                    }
                }
            } finally {
                mStepLock.unlock();
                silentClose(mWriter);
                silentClose(mReader);
                silentClose(mSocket);
                mLogger.println("Client exit!");
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

        private void silentClose(Socket socket) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void doWork() throws IOException, RespondCodeMismatchException {
            switch (mCurrentStep) {
                case CONNECTING:
                    doConnecting();
                    break;
                case HELO:
                    doHelo();
                    break;
                case EHLO:
                    doEhlo();
                    break;
                case AUTH:
                    doAuth();
                    break;
                case AUTH_USER:
                    doAuthUser();
                    break;
                case AUTH_PASSWORD:
                    doAuthPsw();
                    break;
                case READY:
                    // do nothing
                    break;
                case QUIT:
                    doQuit();
                    break;
                case MAIL_FROM:
                    doMailFrom();
                    break;
                case RCPT_TO:
                    doRcptTo();
                    break;
                case DATA:
                    doData();
                    break;
            }
        }

        private void doConnecting() throws IOException, RespondCodeMismatchException {
            Socket socket = new Socket(mServerAddress, 25);
            mWriter = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), Charset.forName("UTF-8")));
            mReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            new ServerRespondThread().start();
            checkRespondCode(peekNextRespond(), SMTPDefine.SERVER_READY);
            if (mNeedAuth) {
                mCurrentStep = Step.EHLO;
            } else {
                mCurrentStep = Step.HELO;
            }
        }

        private void doHelo() throws IOException, RespondCodeMismatchException {
            writeLine("HELO " + mServerAddress);
            if (peekNextRespond() == RESPOND_ERROR) {
                mCurrentStep = Step.STOP;
            } else {
                mCurrentStep = Step.READY;
            }
        }

        private void doEhlo() throws IOException, RespondCodeMismatchException {
            writeLine("EHLO " + mServerAddress);
            ServerRespond respond = peekNextRespond();
            checkRespondCode(respond, SMTPDefine.OK);

            for (String s : respond.mRespond) {
                s = s.toLowerCase();
                if (s.startsWith("auth ")) {
                    if (s.contains("login")) {
                        mAuthSupportLogin = true;
                    }
                    break;
                }
            }

            mCurrentStep = Step.AUTH;
        }

        private void doAuth() throws IOException, RespondCodeMismatchException {
            if (!mAuthSupportLogin) {
                mLogger.println("Auth doesn't support LOGIN! Exit.");
                mCurrentStep = Step.STOP;
                return;
            }

            writeLine("AUTH LOGIN");
            checkRespondCode(peekNextRespond(), SMTPDefine.WAIT_INPUT);

            mCurrentStep = Step.AUTH_USER;
        }

        private void doAuthUser() throws IOException, RespondCodeMismatchException {
            String userBase64 = Base64.encodeToString(mUser.getBytes(), Base64.NO_WRAP);
            writeLine(userBase64);
            checkRespondCode(peekNextRespond(), SMTPDefine.WAIT_INPUT);

            mCurrentStep = Step.AUTH_PASSWORD;
        }

        private void doAuthPsw() throws IOException, RespondCodeMismatchException {
            String userBase64 = Base64.encodeToString(mPassword.getBytes(), Base64.NO_WRAP);
            writeLinePsw(userBase64);
            checkRespondCode(peekNextRespond(), SMTPDefine.AUTH_SUCCESS);

            mCurrentStep = Step.READY;
        }

        private void doMailFrom() throws IOException, RespondCodeMismatchException {
            writeLine("MAIL FROM: <" + mCurrentMail.from + "> ");
            checkRespondCode(peekNextRespond(), SMTPDefine.OK);

            mCurrentStep = Step.RCPT_TO;
        }

        private void doRcptTo() throws IOException, RespondCodeMismatchException {
            writeLine("RCPT TO: <" + mCurrentMail.to + "> ");
            checkRespondCode(peekNextRespond(), SMTPDefine.OK);

            mCurrentStep = Step.DATA;
        }

        private void doData() throws IOException, RespondCodeMismatchException {
            writeLine("DATA");
            checkRespondCode(peekNextRespond(), SMTPDefine.MAIL_START);
            writeLine("FROM: <" + mCurrentMail.from + "> ");
            writeLine("TO: <" + mCurrentMail.to + "> ");
            writeLine("SUBJECT: " + mCurrentMail.subject);
            writeLine("X-Mailer: noisyfox's mailer");
            writeLine("MIME-Version: 1.0");
            writeLine("Content-type: text/plain");
            writeLine("charset=\"utf-8\"");
            writeLine("");
            writeLine(mCurrentMail.content);
            writeLine(".");
            checkRespondCode(peekNextRespond(), SMTPDefine.OK);

            mCurrentStep = Step.READY;
        }

        private void doQuit() throws IOException, RespondCodeMismatchException {
            writeLine("QUIT");
            checkRespondCode(peekNextRespond(), SMTPDefine.CONNECT_CLOSE);

            mCurrentStep = Step.STOP;
        }

        private void checkRespondCode(ServerRespond respond, int code) throws RespondCodeMismatchException {
            if (respond.mRespondCode != code) {
                throw new RespondCodeMismatchException();
            }
        }

        private void writeLine(String line) throws IOException {
            mWriter.print(line);
            mWriter.print(SMTPDefine.LINE_SP);
            mWriter.flush();
            mLogger.println(">" + line);

            if (mWriter.checkError()) {
                throw new IOException();
            }
        }

        private void writeLinePsw(String psw) throws IOException {
            mWriter.print(psw);
            mWriter.print(SMTPDefine.LINE_SP);
            mWriter.flush();
            mLogger.println(">(hidden)");

            if (mWriter.checkError()) {
                throw new IOException();
            }
        }

        private ServerRespond peekNextRespond() {
            mRespondQueueLock.lock();
            try {
                long startTime = System.currentTimeMillis();
                long timeMax = 10000; // 10秒超时
                if (mQueuedRespond.isEmpty()) {
                    while (true) {
                        try {
                            mRespondQueueCondition.await(timeMax, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            return RESPOND_ERROR;
                        }
                        if (!mQueuedRespond.isEmpty()) {
                            break;
                        }
                        timeMax = 10000 - (System.currentTimeMillis() - startTime);
                        if (timeMax <= 0) {
                            return RESPOND_ERROR;
                        }
                    }
                }
                return mQueuedRespond.poll();
            } finally {
                mRespondQueueLock.unlock();
            }
        }

        private void offerRespond(ServerRespond respond) {
            mRespondQueueLock.lock();
            try {
                mQueuedRespond.offer(respond);
                mRespondQueueCondition.signalAll();
            } finally {
                mRespondQueueLock.unlock();
            }
        }

        /**
         * 负责从流中读取服务器响应并压入响应队列供主客户端线程读取
         */
        private class ServerRespondThread extends Thread {
            @Override
            public void run() {
                String line;
                try {
                    mainLoop:
                    while ((line = mReader.readLine()) != null) {
                        mLogger.println(line);
                        int firstSpaceIndex = line.indexOf(' ');
                        int firstDashIndex = line.indexOf('-');
                        if (firstDashIndex == -1 && firstSpaceIndex == -1) {
                            // wrong!
                            offerRespond(RESPOND_ERROR);
                            break;
                        } else {
                            boolean dash;
                            dash = firstSpaceIndex == -1 || (firstDashIndex != -1 && firstSpaceIndex > firstDashIndex);
                            int headLen = dash ? firstDashIndex : firstSpaceIndex;
                            String codeStr = line.substring(0, headLen);
                            int code;
                            try {
                                code = Integer.parseInt(codeStr);
                            } catch (NumberFormatException e) {
                                e.printStackTrace();
                                offerRespond(RESPOND_ERROR);
                                break;
                            }
                            if (dash) {
                                LinkedList<String> respondsList = new LinkedList<String>();
                                headLen++;
                                respondsList.add(line.substring(headLen));
                                String headEnd = codeStr + " ";
                                String headContinue = codeStr + "-";
                                while ((line = mReader.readLine()) != null) {
                                    mLogger.println(line);
                                    if (line.startsWith(headEnd)) {
                                        respondsList.add(line.substring(headLen));
                                        break;
                                    } else if (line.startsWith(headContinue)) {
                                        respondsList.add(line.substring(headLen));
                                    } else {
                                        offerRespond(RESPOND_ERROR);
                                        break mainLoop;
                                    }
                                }
                                String[] responds = new String[respondsList.size()];
                                respondsList.toArray(responds);
                                ServerRespond respond = new ServerRespond();
                                respond.mRespondCode = code;
                                respond.mRespond = responds;
                                offerRespond(respond);
                            } else {
                                ServerRespond respond = new ServerRespond();
                                respond.mRespondCode = code;
                                respond.mRespond = new String[]{line.substring(headLen + 1)};
                                offerRespond(respond);
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    offerRespond(RESPOND_ERROR);
                }
            }
        }

        private class RespondCodeMismatchException extends Exception {
            public RespondCodeMismatchException() {
                super();
            }

            public RespondCodeMismatchException(String message) {
                super(message);
            }

            public RespondCodeMismatchException(String message, Throwable cause) {
                super(message, cause);
            }

            public RespondCodeMismatchException(Throwable cause) {
                super(cause);
            }
        }
    }
}
